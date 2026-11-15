# API 冒烟：拉目录、带 structures 白名单发起搜索、取消、关闭后端。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$env:LOOTRADAR_NO_BROWSER = '1'
$p = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList '/c gradlew.bat :engine:runApp --offline --no-daemon > build\smoke-app.log 2>&1' `
    -WorkingDirectory $root -WindowStyle Hidden -PassThru
try {
    $port = $null
    foreach ($line in (Get-Content 'build\smoke-app.log' -Encoding UTF8 -ErrorAction SilentlyContinue)) {
        if ($line -match '127\.0\.0\.1:(\d+)') { $port = $Matches[1] }
    }
    foreach ($i in 1..60) {
        Start-Sleep -Seconds 2
        foreach ($line in (Get-Content 'build\smoke-app.log' -Encoding UTF8 -ErrorAction SilentlyContinue)) {
            if ($line -match '127\.0\.0\.1:(\d+)') { $port = $Matches[1] }
        }
        if ($port) {
            try {
                $null = Invoke-RestMethod "http://127.0.0.1:$port/api/versions" -TimeoutSec 2
                break
            } catch { }
        }
    }
    if (-not $port) { throw 'server did not come up' }
    Write-Output "app on port $port"
    $catalog = Invoke-RestMethod "http://127.0.0.1:$port/api/catalog?version=26.2"
    $names = $catalog.structures.PSObject.Properties.Name
    Write-Output ("catalog.structures: {0} ids, fortress=" + ($catalog.structures.'minecraft:fortress') + ", monster_room=" + ($catalog.structures.'minecraft:monster_room')) -f $names.Count
    $body = @{
        seed = '0'; version = '26.2'; centerX = 0; centerZ = 0; radius = 512
        minCountPerItem = 1; lootingLevel = 0; startOffset = 1
        skipDungeon = $false
        itemIds = @('minecraft:rib_armor_trim_smithing_template', 'minecraft:diamond_horse_armor')
        typeBudgetSeconds = 30
        structures = @('minecraft:fortress', 'minecraft:monster_room')
    } | ConvertTo-Json
    $started = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/api/search" -ContentType 'application/json' -Body $body
    Write-Output ("search accepted id=" + $started.searchId)
    Start-Sleep -Seconds 4
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/api/search/$($started.searchId)/cancel" | Out-Null
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$port/api/shutdown" -TimeoutSec 3 | Out-Null
    Write-Output 'smoke OK'
} finally {
    Start-Sleep -Seconds 2
    if (-not $p.HasExited) { $p.Kill() }
}
