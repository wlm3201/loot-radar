# 四个版本分别跑 DecorationIndexDumpTest，再用 pin-decoration-indices.py 写回目录。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
if ($env:LR_DEV_PROXY) {
    $uri = [System.Uri]$env:LR_DEV_PROXY
    $env:GRADLE_OPTS = "-Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port) -Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port)"
}
$env:LOOTRADAR_NO_BROWSER = '1'
foreach ($ver in @('26.1.2', '26.2', '26.3', '26.4-snapshot-7')) {
    Write-Output ('=== calibrate ' + $ver + ' [' + (Get-Date -Format 'HH:mm:ss') + ']')
    $p = Start-Process -FilePath 'cmd.exe' `
        -ArgumentList "/c gradlew.bat :engine:test --tests dev.lootradar.engine.DecorationIndexDumpTest -Pminecraft_version=$ver --no-daemon > cal.log 2>&1" `
        -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
    if (-not $p.WaitForExit(420000)) { $p.Kill(); Write-Output ('TIMEOUT ' + $ver) }
    Select-String -Path cal.log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|错误' | Select-Object -First 3 |
        ForEach-Object { $_.Line.Trim().Substring(0, [Math]::Min(120, $_.Line.Trim().Length)) }
}
Remove-Item cal.log -Force -ErrorAction SilentlyContinue
python tools\pin-decoration-indices.py
