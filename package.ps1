
# 打发行包：构建前端 + :engine:distZip，拼出 web-dist/data/lib 与启动器，最后打 zip。
param(
    [switch]$SkipWebBuild,
    [switch]$KeepClientLibs,
    [int]$TimeoutSec = 600
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
Set-Location $root
$version = (Select-String -Path (Join-Path $root "dump-mod\gradle.properties") -Pattern "^version=").Line.Split("=")[1].Trim()

if (-not $SkipWebBuild) {
    Write-Host "building web UI (pnpm)..."
    Push-Location (Join-Path $root "web")
    try {
        & cmd /c "pnpm install --frozen-lockfile > ..\build-web.log 2>&1 && pnpm build >> ..\build-web.log 2>&1"
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) { throw "web build failed (exit $LASTEXITCODE) - see build-web.log" }
}

Write-Host "building engine distribution..."
& cmd /c "gradlew.bat :engine:distZip --no-daemon > package-build.log 2>&1"
if ($LASTEXITCODE -ne 0) { throw "gradle distZip failed (exit $LASTEXITCODE) - see package-build.log" }

$out = Join-Path $root "dist\loot-radar-$version"
try {
    if (Test-Path $out) { Remove-Item $out -Recurse -Force -ErrorAction Stop }
} catch {
    Write-Host "dist folder busy - refreshing in place"
}
New-Item -ItemType Directory -Force -Path $out | Out-Null

$zip = Get-ChildItem (Join-Path $root "engine\build\distributions") -Filter "*.zip" |
    Sort-Object LastWriteTime | Select-Object -Last 1
Expand-Archive -Path $zip.FullName -DestinationPath $out -Force

$libPath = Join-Path $out (Join-Path $zip.BaseName 'lib')
if (-not $KeepClientLibs) {
    $clientOnlyJarPatterns = @(
        'lwjgl-*',                                         # GLFW/OpenGL/Vulkan/音频及全部 natives
        'icu4j-*.jar',                                      # 客户端文本整形
        'jna-*.jar', 'jna-platform-*.jar', 'oshi-core-*.jar', # F3 硬件/系统信息
        'msal4j-*.jar', 'azure-json-*.jar',                 # 微软账号登录
        'jorbis-*.jar', 'text2speech-*.jar',                # 声音/旁白
        'patchy-*.jar', 'blocklist-*.jar',                  # 客户端过滤
        'fabric-log4j-util-*.jar', 'dev-launch-injector-*.jar', # Loom 开发启动器
        'netty-transport-native-*.jar'                      # Linux/macOS 原生传输
    )
    $toRemove = @()
    foreach ($pattern in $clientOnlyJarPatterns) {
        $toRemove += @(Get-ChildItem $libPath -File -Filter $pattern -ErrorAction SilentlyContinue)
    }
    $toRemove = @($toRemove | Sort-Object FullName -Unique)
    $removedMb = [math]::Round((($toRemove | Measure-Object Length -Sum).Sum) / 1MB, 2)
    foreach ($file in $toRemove) { Remove-Item $file.FullName -Force }
    Write-Host ("pruned {0} client-only jars ({1} MB)" -f $toRemove.Count, $removedMb)

    # Gradle 生成的 bin 启动脚本由 "Loot Radar.bat" 取代，logs 是运行时产物。
    Remove-Item (Join-Path $out (Join-Path $zip.BaseName 'bin')) -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $out 'logs') -Recurse -Force -ErrorAction SilentlyContinue

    $required = @('minecraft-merged-*.jar', 'fastutil-*.jar', 'guava-*.jar', 'log4j-core-*.jar',
                  'jtracy-*.jar', 'engine-*.jar', 'core-*.jar')
    foreach ($pattern in $required) {
        if (-not (Get-ChildItem $libPath -File -Filter $pattern -ErrorAction SilentlyContinue)) {
            throw "required runtime jar missing after pruning: $pattern"
        }
    }
}

Copy-Item (Join-Path $root "web\dist") -Destination (Join-Path $out "web-dist") -Recurse -Force
Copy-Item (Join-Path $root "data") -Destination (Join-Path $out "data") -Recurse -Force

$libDir = Join-Path $zip.BaseName 'lib'
@"
@echo off
setlocal
rem Loot Radar launcher - requires Java 25+ (JAVA_HOME or PATH).
cd /d "%~dp0"
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA (
  for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%j"
)
if not defined JAVA goto :nojava
"%JAVA%" -version 2>&1 | findstr /r /c:"version .2[5-9]\." /c:"version .3[0-9]\." >nul
if errorlevel 1 goto :nojava
"%JAVA%" --sun-misc-unsafe-memory-access=allow -cp "%~dp0$libDir\*" dev.lootradar.app.Main
goto :eof

:nojava
echo [loot-radar] Java 25+ not found. Install JDK 25 first:
echo https://adoptium.net/temurin/releases/?version=25
pause
exit /b 1
"@ | Set-Content -Path (Join-Path $out "Loot Radar.bat") -Encoding ASCII

$zipOut = "$out.zip"
Remove-Item $zipOut -Force -ErrorAction SilentlyContinue
for ($try = 1; $try -le 2; $try++) {
    try {
        Compress-Archive -Path $out -DestinationPath $zipOut -Force -ErrorAction Stop
        break
    } catch {
        if ($try -eq 2) { throw "zip creation failed: $_" }
        Write-Host "zip creation failed (files locked?), retrying in 5s..."
        Start-Sleep -Seconds 5
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($zipOut)
try {
    if ($archive.Entries.Count -lt 10) { throw "zip looks empty: $($archive.Entries.Count) entries" }
    Write-Host ("zip verified: " + $archive.Entries.Count + " entries")
} finally {
    $archive.Dispose()
}
Write-Host "distributable ready: $zipOut"
