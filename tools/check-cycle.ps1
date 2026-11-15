# 列出残留的普查/单测进程；加 -Kill 直接结束它们。
param([switch]$Kill)
$procs = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -and $_.CommandLine -match 'run-source-tests|fill-source|audit-source|run-bounce-census|runBounceCoverage|unittest discover'
}
if (-not $procs) { Write-Output 'no cycle processes' }
foreach ($p in $procs) {
    Write-Output ('pid=' + $p.ProcessId + ' ' + $p.Name)
    if ($Kill) { Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue }
}
if ($Kill) { Write-Output 'kill issued' }
