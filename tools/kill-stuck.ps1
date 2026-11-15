# 清理带 loot-radar/lootradar 字样的残留 java / launcher 进程。
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' or Name='java.exe' or Name='loot-radar.exe'" |
    Where-Object { $_.CommandLine -like '*loot-radar*' -or $_.CommandLine -like '*lootradar*' } |
    ForEach-Object {
        Write-Output ("killing pid=" + $_.ProcessId + " " + $_.Name)
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
    }
Get-Process -Name 'loot-radar' -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Output ("killing loot-radar pid=" + $_.Id)
    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
}
Write-Output "done"
