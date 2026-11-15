# 一键开发态：启动发行包里的后端 + web/ 的 vite dev（5173），端口代理见 vite.config.ts。
$root = Split-Path -Parent $PSScriptRoot
$version = (Select-String -Path (Join-Path $root 'dump-mod\gradle.properties') -Pattern '^version=').Line.Split('=')[1].Trim()
$dist = Join-Path $root "dist\loot-radar-$version"
New-Item -ItemType Directory -Force -Path (Join-Path $root 'build') | Out-Null
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c "Loot Radar.bat"' `
    -WorkingDirectory $dist -WindowStyle Hidden
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c pnpm dev > ..\build\vite-dev.log 2>&1' `
    -WorkingDirectory (Join-Path $root 'web') -WindowStyle Hidden
Write-Output 'api + vite dev starting (5173)'
