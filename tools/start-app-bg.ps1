# 后台启动应用（调用 tools/run-app.ps1），记录 app-bg.pid 并等待 API 就绪。
param([int]$Port = 8765)
$ErrorActionPreference = 'Continue'
$root = Split-Path $PSScriptRoot
Set-Location $root
if (Test-Path 'app-bg.pid') {
    $old = Get-Content 'app-bg.pid' -ErrorAction SilentlyContinue
    if ($old) { Stop-Process -Id ([int]$old) -Force -ErrorAction SilentlyContinue }
    Remove-Item 'app-bg.pid' -Force -ErrorAction SilentlyContinue
}
$p = Start-Process -FilePath 'powershell.exe' `
    -ArgumentList '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'run-app.ps1'), '-Port', "$Port" `
    -WorkingDirectory $root -PassThru -NoNewWindow `
    -RedirectStandardOutput (Join-Path $root 'run-app.log') `
    -RedirectStandardError (Join-Path $root 'run-app.err.log')
$p.Id | Set-Content 'app-bg.pid'
Write-Output ('wrapper pid=' + $p.Id)
& (Join-Path $PSScriptRoot 'wait-api.ps1') -Port $Port
Write-Output ('api ready: ' + ($LASTEXITCODE -eq 0))
