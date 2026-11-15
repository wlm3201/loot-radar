# 最多 90 秒内轮询 Port..Port+PortSpan-1，直到 /api/versions 返回 200。
param([int]$Port = 8765, [int]$PortSpan = 10)
foreach ($i in 1..45) {
    Start-Sleep -Seconds 2
    foreach ($p in $Port..($Port + $PortSpan - 1)) {
        try {
            $v = (New-Object System.Net.WebClient).DownloadString("http://127.0.0.1:$p/api/versions")
            Write-Output ("API up on ${p}: " + $v.Substring(0, [Math]::Min(100, $v.Length)))
            exit 0
        } catch { }
    }
}
Write-Output "API did not come up in 90s (ports $Port..$($Port + $PortSpan - 1))"
exit 1
