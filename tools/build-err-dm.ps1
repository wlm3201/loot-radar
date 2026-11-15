# 打印 dump-mod 构建错误；可用 $env:LR_DEV_PROXY 走代理，默认任务 :v26_2:build。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
if ($env:LR_DEV_PROXY) {
    $uri = [System.Uri]$env:LR_DEV_PROXY
    $env:GRADLE_OPTS = "-Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port) -Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port)"
}
$task = ':v26_2:build'
if ($args[0]) { $task = $args[0] }
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList "/c dump-mod\gradlew.bat -p dump-mod $task --no-daemon -x test > dm.log 2>&1" -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(600000)) { $p.Kill(); Write-Output 'TIMEOUT' }
$lines = Get-Content dm.log -Encoding UTF8
$show = $false
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match 'What went wrong') { $show = $true }
    if ($show) {
        Write-Output $lines[$i].Trim().Substring(0, [Math]::Min(170, $lines[$i].Trim().Length))
        if ($lines[$i] -match '^\* Try' -or $i -gt 200) { break }
    }
    if ($lines[$i] -match 'BUILD SUCCESSFUL') { Write-Output $lines[$i].Trim(); break }
}
Remove-Item dm.log -Force -ErrorAction SilentlyContinue
