# 结束命令行里含 dev.lootradar.app.Main 的后端进程。
Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'dev.lootradar.app.Main' } |
    ForEach-Object {
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        Write-Output ("killed " + $_.ProcessId)
    }
Write-Output 'clean'
