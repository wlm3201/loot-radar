# 用 -Pminecraft_version=26.4-snapshot-7 跑 engine 单测并打印 XML 失败明细。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
if ($env:LR_DEV_PROXY) {
    $uri = [System.Uri]$env:LR_DEV_PROXY
    $env:GRADLE_OPTS = "-Dhttps.proxyHost=$($uri.Host) -Dhttps.proxyPort=$($uri.Port) -Dhttp.proxyHost=$($uri.Host) -Dhttp.proxyPort=$($uri.Port)"
}
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c gradlew.bat :engine:test -Pminecraft_version=26.4-snapshot-7 --no-daemon > t264.log 2>&1' -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(900000)) { $p.Kill(); Write-Output 'TIMEOUT' }
Select-String -Path t264.log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|FAILED' | Select-Object -First 6 | ForEach-Object { $_.Line.Trim().Substring(0, [Math]::Min(130, $_.Line.Trim().Length)) }
Get-ChildItem 'engine\build\test-results\test' -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
    [xml]$x = Get-Content $_.FullName -Encoding UTF8
    Write-Output ('  ' + $x.testsuite.name + ': tests=' + $x.testsuite.tests + ' failures=' + $x.testsuite.failures)
    if ([int]$x.testsuite.failures -gt 0) {
        $x.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object {
            $msg = ($_.failure.message -replace "`n", ' ')
            Write-Output ('    FAIL ' + $_.name + ': ' + $msg.Substring(0, [Math]::Min(180, $msg.Length)))
        }
    }
}
Remove-Item t264.log -Force -ErrorAction SilentlyContinue
