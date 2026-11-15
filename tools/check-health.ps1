# 健康检查：依次跑 engine 单测与 web 构建，输出各自日志摘要。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)

Write-Output '=== engine tests ==='
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c gradlew.bat :engine:test --no-daemon > th.log 2>&1' -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(600000)) { $p.Kill(); Write-Output 'TIMEOUT' }
Select-String -Path 'th.log' -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|FAILED' | Select-Object -First 6 | ForEach-Object { $_.Line.Trim().Substring(0, [Math]::Min(120, $_.Line.Trim().Length)) }
Get-ChildItem 'engine\build\test-results\test' -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
    [xml]$x = Get-Content $_.FullName -Encoding UTF8
    Write-Output ("  " + $x.testsuite.name + ": tests=" + $x.testsuite.tests + " failures=" + $x.testsuite.failures)
}
Remove-Item th.log -Force -ErrorAction SilentlyContinue

Write-Output '=== web build ==='
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c pnpm build > wh.log 2>&1' -WorkingDirectory (Join-Path (Split-Path $PSScriptRoot) 'web') -PassThru -NoNewWindow
if (-not $p.WaitForExit(180000)) { $p.Kill(); Write-Output 'web TIMEOUT' }
Select-String -Path 'wh.log' -Pattern 'built in|error TS' | Select-Object -First 4 | ForEach-Object { $_.Line }
Remove-Item wh.log -Force -ErrorAction SilentlyContinue
