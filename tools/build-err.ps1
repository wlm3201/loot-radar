# 打印 engine 编译错误；可用 $env:LR_DEV_PROXY 走代理，默认任务 :engine:compileTestJava。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
if ($env:LR_DEV_PROXY) {
    $uri = [System.Uri]$env:LR_DEV_PROXY
    $env:GRADLE_OPTS = "-Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port) -Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port)"
}
$task = ':engine:compileTestJava'
if ($args[0]) { $task = $args[0] }
$arg = "/c gradlew.bat $task --no-daemon > be.log 2>&1"
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList $arg -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(600000)) { $p.Kill(); Write-Output 'TIMEOUT' }
$lines = Get-Content be.log -Encoding UTF8
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '\.java:\d+: ') {
        for ($j = $i; $j -lt [Math]::Min($i + 3, $lines.Count); $j++) {
            Write-Output $lines[$j].Trim().Substring(0, [Math]::Min(190, $lines[$j].Trim().Length))
        }
        Write-Output '---'
    }
    if ($lines[$i] -match 'BUILD SUCCESSFUL') { Write-Output $lines[$i].Trim() }
}
Remove-Item be.log -Force -ErrorAction SilentlyContinue
