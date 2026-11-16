# 停止 start-dev.ps1 起的后端与 Vite（按 pid 文件杀整棵进程树，兼容旧版无 pid 的残留）。
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$build = Join-Path $root 'build'
$stopped = $false

foreach ($name in @('dev-api.pid', 'dev-vite.pid')) {
    $file = Join-Path $build $name
    if (-not (Test-Path $file)) { continue }
    $first = Get-Content $file -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($first -match '^\d+$') {
        $target = [int]$first
        & taskkill.exe /PID $target /T /F 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Output ("stopped {0} (pid {1})" -f $name, $target)
            $stopped = $true
        }
    }
    Remove-Item $file -Force -ErrorAction SilentlyContinue
}

# 旧版脚本没记 pid：按命令行特征兜底，避免只能开任务管理器。
$legacy = Get-CimInstance Win32_Process | Where-Object {
    $_.CommandLine -match 'dev\.lootradar\.app\.Main' -or
    $_.CommandLine -match 'pnpm dev > \.\.\\build\\vite-dev\.log'
}
foreach ($process in $legacy) {
    & taskkill.exe /PID $process.ProcessId /T /F 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Output ("stopped legacy pid=" + $process.ProcessId)
        $stopped = $true
    }
}

if (-not $stopped) { Write-Output 'no dev process to stop' }
