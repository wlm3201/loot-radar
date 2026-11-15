# 校验发行包：启动 .bat、轮询 /api/versions、检查首页与 JS 的 Content-Type。
$root = Split-Path $PSScriptRoot
$version = (Select-String -Path (Join-Path $root 'dump-mod\gradle.properties') -Pattern '^version=').Line.Split('=')[1].Trim()
$dist = Join-Path $root "dist\loot-radar-$version"
$proc = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c "Loot Radar.bat"' `
    -WorkingDirectory $dist -WindowStyle Hidden -PassThru
try {
    $ok = $false
    foreach ($i in 1..30) {
        Start-Sleep -Seconds 2
        try {
            $versions = Invoke-RestMethod 'http://127.0.0.1:8765/api/versions' -TimeoutSec 2
        } catch {
            continue
        }
        Write-Output ("API up: versions=" + ($versions.versions -join ','))
        $page = (New-Object System.Net.WebClient).DownloadString('http://127.0.0.1:8765/')
        if ($page -match 'Build the web UI') {
            throw 'root page serves the placeholder - web-root resolution failed'
        }
        if ($page -notmatch '<!doctype html>') {
            throw 'root page is not the web UI'
        }
        $asset = ([regex]::Match($page, 'src="/(assets/[^"]+\.js)"')).Groups[1].Value
        if (-not $asset) { throw 'index.html references no module script' }
        $req = [System.Net.WebRequest]::Create('http://127.0.0.1:8765/' + $asset)
        $resp = $req.GetResponse()
        $type = $resp.ContentType
        $resp.Close()
        if ($type -notmatch 'javascript') {
            throw "module script served as $type (static route fell back to index.html)"
        }
        Write-Output "root page serves the web UI ($asset -> $type)"
        $ok = $true
        break
    }
    if (-not $ok) { throw 'launcher did not bring the API up within 60s' }
} finally {
    Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'dev.lootradar.app.Main' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    if (-not $proc.HasExited) { $proc.Kill() }
}
Write-Output 'launcher smoke OK'
