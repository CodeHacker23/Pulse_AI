# Запуск sidecar на Windows (без activate)
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Py = Join-Path $Root ".venv\Scripts\python.exe"

if (-not (Test-Path $Py)) {
    Write-Host "Сначала: python -m venv .venv  и  pip install -r requirements.txt"
    exit 1
}

Set-Location $Root

# Сессии .session из продавца — import не нужен; иначе auth key:
$needImport = -not (Test-Path "sessions\outreach-1.session") -or -not (Test-Path "sessions\observer-1.session")
if ($needImport) {
    & $Py scripts/import_auth_key.py
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

& $Py main.py
