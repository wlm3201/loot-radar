# 用 26.2 版 dump-mod 采集 0..3 级抢夺的远古守卫者掉落真值；-FreshWorld 清空旧存档。
param([int]$Kills = 100, [int]$TimeoutSec = 600, [switch]$FreshWorld)
$root = Split-Path -Parent $PSScriptRoot
$dump = Join-Path $root 'dump-mod'
if ($FreshWorld) {
    Remove-Item -Recurse -Force (Join-Path $dump 'versions\26.2\run\world') -ErrorAction SilentlyContinue
    Write-Output 'world reset (fresh sequence state)'
}
$log = Join-Path $root 'build\looting-run.log'
$p = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList "/c gradlew.bat --offline :v26_2:runServer -Dlootradar.autoDump=1 -Dlootradar.lootingCalibration=$Kills --no-daemon > `"$log`" 2>&1" `
    -WorkingDirectory $dump -WindowStyle Hidden -PassThru
if (-not $p.WaitForExit($TimeoutSec * 1000)) {
    Write-Output "TIMEOUT after $TimeoutSec s, killing"
    Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    $p.Kill()
}
Write-Output '--- server run finished ---'
Get-Content $log -Encoding UTF8 -ErrorAction SilentlyContinue |
    Select-String -Pattern 'looting|calibration|FAILED|Exception|halt' | Select-Object -Last 8
