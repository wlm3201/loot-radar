# 安装前端依赖并构建 web/dist，打印最后几行输出。
$ErrorActionPreference = 'Continue'
Set-Location (Join-Path (Split-Path $PSScriptRoot) 'web')
pnpm install --no-frozen-lockfile 2>&1 | Select-Object -Last 3
pnpm run build 2>&1 | Select-Object -Last 5
Write-Output ("dist ok: " + (Test-Path 'dist\index.html'))
