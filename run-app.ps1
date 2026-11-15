# 开发态启动入口：前端产物过期则先 pnpm build，再起 :engine:runApp。
param(
    [int]$Port = 8765,
    [int]$TimeoutSec = 0
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$distIndex = Join-Path $PSScriptRoot "web\dist\index.html"
$webStale = -not (Test-Path $distIndex)
if (-not $webStale) {
    $distTime = (Get-Item $distIndex).LastWriteTime
    $webSources = @(
        Get-ChildItem (Join-Path $PSScriptRoot "web\src") -Recurse -File -ErrorAction SilentlyContinue
        Get-Item (Join-Path $PSScriptRoot "web\index.html") -ErrorAction SilentlyContinue
        Get-Item (Join-Path $PSScriptRoot "web\tailwind.config.js") -ErrorAction SilentlyContinue
        Get-Item (Join-Path $PSScriptRoot "web\vite.config.ts") -ErrorAction SilentlyContinue
        Get-Item (Join-Path $PSScriptRoot "web\package.json") -ErrorAction SilentlyContinue
    )
    $webStale = $webSources | Where-Object { $_ -and $_.LastWriteTime -gt $distTime } | Select-Object -First 1
}
if ($webStale) {
    Write-Host "web\dist missing or stale - building the web UI first (pnpm)..."
    Push-Location (Join-Path $PSScriptRoot "web")
    try {
        pnpm install --frozen-lockfile
        pnpm build
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $distIndex)) {
        throw "web build produced no dist\index.html - the UI cannot be served"
    }
}

Write-Host "Building and starting loot-radar on port $Port ..."
$arguments = "/c gradlew.bat :engine:runApp --no-daemon --args=$Port"
$process = Start-Process -FilePath "cmd.exe" -ArgumentList $arguments `
    -WorkingDirectory $PSScriptRoot -PassThru -NoNewWindow

if ($TimeoutSec -gt 0) {
    if (-not $process.WaitForExit($TimeoutSec * 1000)) {
        Write-Host "Timeout reached ($TimeoutSec s), killing app."
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Get-Process -Name java -ErrorAction SilentlyContinue |
            Where-Object { $_.StartTime -gt (Get-Date).AddSeconds(-$TimeoutSec - 30) } |
            Stop-Process -Force -ErrorAction SilentlyContinue
    }
} else {
    $process.WaitForExit()
}
