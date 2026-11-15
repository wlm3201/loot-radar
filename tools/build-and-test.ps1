# 构建前端并运行 engine 单测，日志写到 build-web.log / t11.log，超时后打印摘要。
Set-Location (Join-Path (Split-Path $PSScriptRoot) 'web')
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c pnpm build > build-web.log 2>&1' -WorkingDirectory (Join-Path (Split-Path $PSScriptRoot) 'web') -PassThru -NoNewWindow
if (-not $p.WaitForExit(180000)) { $p.Kill(); Write-Output 'web TIMEOUT' }
Select-String -Path 'build-web.log' -Pattern 'built in|error TS' | Select-Object -First 8 | ForEach-Object { $_.Line }

Set-Location (Split-Path $PSScriptRoot)
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c gradlew.bat :engine:test --no-daemon > t11.log 2>&1' -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(600000)) { $p.Kill(); Write-Output 'test TIMEOUT' }
Select-String -Path 't11.log' -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|FAILED' | Select-Object -First 4 | ForEach-Object { $_.Line.Trim() }
Remove-Item t11.log -Force -ErrorAction SilentlyContinue
