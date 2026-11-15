
# 旧版质量循环：autoDump 真值 → :engine:runVerify 报告 → :engine:runBench 基准。
param(
    [int]$Radius = 512,
    [int]$BenchRadius = 3000,
    [switch]$SkipDump,
    [int]$TimeoutSec = 900
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Invoke-Stage([string]$Name, [string]$Exe, [string]$Arguments, [string]$WorkDir) {
    Write-Host "=== $Name ==="
    $log = Join-Path $root "$Name.log"
    $okFile = Join-Path $root "$Name.ok"
    if (Test-Path $okFile) { Remove-Item $okFile -Force }
    $wrapped = "$Arguments && echo OK > `"$okFile`""
    $p = Start-Process -FilePath $Exe -ArgumentList $wrapped -WorkingDirectory $WorkDir `
        -PassThru -NoNewWindow -RedirectStandardOutput $log -RedirectStandardError "$Name.err"
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        Write-Host "TIMEOUT after $TimeoutSec s, killing $Name"
        Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
        $p.Kill()
        return $false
    }
    if (-not (Test-Path $okFile)) {
        Write-Host "$Name failed (no .ok marker)"
        return $false
    }
    Write-Host (Get-Content $log | Select-String "verify:|bench:|BUILD|dumped" | Select-Object -Last 4)
    return $true
}

if (-not $SkipDump) {
    $proxy = if ($env:LR_DEV_PROXY) { "set HTTP_PROXY=$($env:LR_DEV_PROXY)&& set HTTPS_PROXY=$($env:LR_DEV_PROXY)&& " } else { "" }
    $ok = Invoke-Stage "q-dump" "cmd.exe" `
        "/c ${proxy}gradlew.bat runServer -Dlootradar.autoDump=$Radius --no-daemon" `
        (Join-Path $root "dump-mod")
    $fresh = Get-ChildItem (Join-Path $root "dump-mod\versions\26.2\run\loot-radar-dump") `
        -Filter "containers-*.json" -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -gt (Get-Date).AddMinutes(-15) }
    if (-not $ok -and -not $fresh) { exit 1 }
}

$dump = Get-ChildItem (Join-Path $root "dump-mod\versions\26.2\run\loot-radar-dump") `
    -Filter "containers-*.json" | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $dump) { Write-Host "no dump file found"; exit 1 }
Write-Host "truth: $($dump.FullName)"

$ok = Invoke-Stage "q-verify" "cmd.exe" `
    "/c gradlew.bat :engine:runVerify --no-daemon -Pdump=$($dump.FullName -replace '\\','/')" `
    $root
if (-not $ok) { exit 1 }

$ok = Invoke-Stage "q-bench" "cmd.exe" `
    "/c gradlew.bat :engine:runBench --no-daemon -Pradius=$BenchRadius" `
    $root
if (-not $ok) { exit 1 }

Write-Host ""
Write-Host "quality cycle done:"
Write-Host "  docs\verify-report.md    accuracy + coverage"
Write-Host "  docs\bench-report.{md,csv}  query speed"
Write-Host "  战利品覆盖.md            hand-maintained status table"
