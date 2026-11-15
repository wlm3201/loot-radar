# 跑 engine 单测，$args[0] 可传 --tests <pattern>；结果从 test-results/*.xml 汇总。
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path $PSScriptRoot)
$filter = $args[0]
if ($filter) {
    $arg = "/c gradlew.bat :engine:test --tests $filter --no-daemon > tc.log 2>&1"
} else {
    $arg = '/c gradlew.bat :engine:test --no-daemon > tc.log 2>&1'
}
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList $arg -WorkingDirectory (Split-Path $PSScriptRoot) -PassThru -NoNewWindow
if (-not $p.WaitForExit(600000)) { $p.Kill(); Write-Output 'TIMEOUT' }
Select-String -Path tc.log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED|error:|符号|位置' |
    Select-Object -First 12 |
    ForEach-Object { $_.Line.Trim().Substring(0, [Math]::Min(140, $_.Line.Trim().Length)) }
Get-ChildItem 'engine\build\test-results\test' -Filter '*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
    [xml]$x = Get-Content $_.FullName -Encoding UTF8
    $fails = $x.testsuite.failures
    Write-Output ('  ' + $x.testsuite.name + ': failures=' + $fails)
    if ([int]$fails -gt 0) {
        $x.testsuite.testcase | Where-Object { $_.failure } | ForEach-Object {
            Write-Output ('    FAIL ' + $_.name + ': ' + ($_.failure.message -replace "`n", ' ').Substring(0, [Math]::Min(200, ($_.failure.message -replace "`n", ' ').Length)))
        }
    }
}
Remove-Item tc.log -Force -ErrorAction SilentlyContinue
