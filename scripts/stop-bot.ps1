# Только останавливает бота на порту 8081.
# Использование: .\scripts\stop-bot.ps1

$Port = 8081
$connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $connections) {
    Write-Host "Nothing listening on port $Port."
    exit 0
}

$pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique
foreach ($pid in $pids) {
    Write-Host "Stopping PID $pid..."
    Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
}
Write-Host "Done."
