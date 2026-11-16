# 一键开发态：后端走 API（不自动开浏览器）+ web/ 的 vite dev（5173），前端改动即时热更新。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$version = (Select-String -Path (Join-Path $root 'dump-mod\gradle.properties') -Pattern '^version=').Line.Split('=')[1].Trim()
$dist = Join-Path $root "dist\loot-radar-$version"
$build = Join-Path $root 'build'
New-Item -ItemType Directory -Force -Path $build | Out-Null

# 清掉上次残留（按 pid 文件；兼容旧版无 pid 的进程）。
& (Join-Path $PSScriptRoot 'stop-dev.ps1') | Out-Null

# 后端不自动开浏览器，否则会抢先打开 8765 的静态页面而不是 Vite 页面。
$env:LOOTRADAR_NO_BROWSER = '1'
try {
    $api = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c "Loot Radar.bat"' `
        -WorkingDirectory $dist -WindowStyle Hidden -PassThru
} finally {
    Remove-Item Env:LOOTRADAR_NO_BROWSER -ErrorAction SilentlyContinue
}
$vite = Start-Process -FilePath 'cmd.exe' -ArgumentList '/c pnpm dev > ..\build\vite-dev.log 2>&1' `
    -WorkingDirectory (Join-Path $root 'web') -WindowStyle Hidden -PassThru

$api.Id | Set-Content (Join-Path $build 'dev-api.pid')
$vite.Id | Set-Content (Join-Path $build 'dev-vite.pid')
Write-Output 'api:  http://127.0.0.1:8765  (no browser auto-open)'
Write-Output 'web:  http://127.0.0.1:5173  (Vite HMR)'
Write-Output ('pids: api={0} vite={1}' -f $api.Id, $vite.Id)
Write-Output 'stop: .\tools\stop-dev.ps1'
