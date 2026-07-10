# Останавливает процесс на порту 8081 и запускает бота заново.
# Использование: .\scripts\restart-bot.ps1

$ErrorActionPreference = "Stop"
$Port = 8081
$Root = Split-Path $PSScriptRoot -Parent

Write-Host "Checking port $Port..."

$connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($connections) {
    $pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($pid in $pids) {
        $proc = Get-Process -Id $pid -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "Stopping PID $pid ($($proc.ProcessName))..."
            Stop-Process -Id $pid -Force
        }
    }
    Start-Sleep -Seconds 2
} else {
    Write-Host "Port $Port is free."
}

Set-Location $Root
Write-Host "Starting bot (profile: local)..."
& .\gradlew.bat bootRun --args="--spring.profiles.active=local"
