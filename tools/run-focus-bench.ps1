# 聚焦性能基准：林地府邸 + 沙漠水井，分别生成 prediction/truth 并计时。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
$case = 'build\focus-mansion-well\26.2_0\overworld'
$log = 'build\focus-bench.log'

function Step($name, $seconds, $command) {
    Write-Output ('=== ' + $name + ' [countdown ' + $seconds + 's] ' + (Get-Date -Format 'HH:mm:ss')) | Tee-Object -FilePath $log -Append
    Remove-Item 'build\fcode.txt' -Force -ErrorAction SilentlyContinue
    $wrapped = '/c (' + $command + ') > build\fstep.log 2>&1 && echo 0 >build\fcode.txt || echo 1 >build\fcode.txt'
    $p = Start-Process -FilePath 'cmd.exe' -ArgumentList $wrapped `
        -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
    if (-not $p.WaitForExit($seconds * 1000)) {
        $p.Kill()
        Write-Output ('!!! TIMEOUT ' + $name) | Tee-Object -FilePath $log -Append
        exit 1
    }
    Get-Content 'build\fstep.log' -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 4 | Tee-Object -FilePath $log -Append
    $code = ((Get-Content 'build\fcode.txt' -ErrorAction SilentlyContinue | Select-Object -First 1) + '').Trim()
    if ($code -ne '0') {
        Write-Output ('!!! FAILED ' + $name + ' code=' + $code) | Tee-Object -FilePath $log -Append
        exit 1
    }
}

Write-Output ('=== focus bench start ' + (Get-Date -Format 'HH:mm:ss')) | Set-Content $log
cmd /c "del /f /q $case\prediction.json $case\prediction.log $case\truth.json $case\truth.log 2>nul & rd /s /q $case\server 2>nul"
Write-Output 'stale evidence wiped' | Tee-Object -FilePath $log -Append

Step 'build engine native' 600 'gradlew.bat --offline "-Pminecraft_version=26.2" "-PcoverageBuild=source-table" :engine:writeRoomCoverageLaunch'
Step 'build dump-mod' 600 'dump-mod\gradlew.bat -p dump-mod --offline :v26_2:writeCoverageLaunch'
Step 'prediction mansion+well r20000' 1800 'python tools\run-source-tests.py prediction --versions 26.2 --seeds 0 --dimensions overworld --radius 20000 --focus minecraft:mansion --output build\focus-mansion-well'
Step 'truth mansion+well r20000' 3600 'python tools\run-source-tests.py truth --versions 26.2 --seeds 0 --dimensions overworld --radius 20000 --batch 128 --sets woodland_mansions --parallel-features --output build\focus-mansion-well'
Write-Output ('=== focus bench evidence ready ' + (Get-Date -Format 'HH:mm:ss')) | Tee-Object -FilePath $log -Append
