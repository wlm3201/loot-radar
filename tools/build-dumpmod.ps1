# 离线构建 26.2 版 dump-mod（跳过测试），日志写入 dm.log。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c dump-mod\gradlew.bat -p dump-mod :v26_2:build --no-daemon -x test > dm.log 2>&1' -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(600000)) { $p.Kill(); Write-Output 'TIMEOUT' }
Get-Content dm.log -Encoding UTF8 | Where-Object { $_ -match 'BUILD|error|错误|\.java' } |
    Select-Object -First 15 |
    ForEach-Object { $_.Trim().Substring(0, [Math]::Min(150, $_.Trim().Length)) }
Remove-Item dm.log -Force -ErrorAction SilentlyContinue
