# 三版本 Bounce 全流程：mod 真值 → 引擎穷举预测 → compare-bounce-coverage.py 汇总。
param(
    [string[]]$Versions = @('26.1.2', '26.2', '26.3'),
    [string]$Seed = '0',
    [int]$Radius = 5000,
    [switch]$TruthOnly
)
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

Write-Output '== building dump-mod coverage launches =='
& cmd /c "dump-mod\gradlew.bat -p dump-mod --offline -q :v26_1_2:writeCoverageLaunch :v26_2:writeCoverageLaunch :v26_3:writeCoverageLaunch > build\b3v-launch.log 2>&1"
Get-Content 'build\b3v-launch.log' -Encoding UTF8 | Select-String -Pattern 'error|错误' | Select-Object -First 3

$versionArgs = $Versions -join ' '
Write-Output "== truth side: $versionArgs seed=$Seed radius=$Radius =="
$truthCmd = "cmd /c (python tools\run-bounce-census.py --versions $versionArgs --seeds $Seed --radius $Radius --output build\bounce-3v > build\b3v-truth.log 2>&1)"
Start-Process -FilePath 'cmd.exe' -ArgumentList $truthCmd -WorkingDirectory $root -WindowStyle Hidden

if ($TruthOnly) {
    Write-Output 'truth launched (TruthOnly); predictions skipped'
    exit 0
}

foreach ($version in $Versions) {
    Write-Output "== predictions: $version =="
    & cmd /c "gradlew.bat :engine:runBounceCoverage -Pminecraft_version=$version -PcoverageBuild=bounce-3v --args=`"build\bounce-3v $Radius $Seed`" --offline --no-daemon > build\b3v-pred-$version.log 2>&1"
    Get-Content "build\b3v-pred-$version.log" -Encoding UTF8 | Select-String -Pattern 'PREDICTION|error|错误|FAILED' | Select-Object -First 4
}

Write-Output '== waiting for truth side to finish =='
$deadline = (Get-Date).AddMinutes(60)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 20
    $alive = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'run-bounce-census' }
    if (-not $alive) { break }
}
Get-Content 'build\b3v-truth.log' -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 5

Write-Output '== compare =='
& cmd /c "python tools\compare-bounce-coverage.py build\bounce-3v build\bounce-3v\report --seeds $Seed > build\b3v-compare.log 2>&1"
Get-Content 'build\b3v-compare.log' -Encoding UTF8 | Select-Object -Last 12
Write-Output '== done =='
