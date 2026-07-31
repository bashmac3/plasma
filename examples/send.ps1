# Отправка примера payload в мост Plasma (Windows / PowerShell).
# Использование:
#   .\send.ps1 -Port 46946 -Payload .\01_hello.json
# Конфиг ищется автоматически: переменная PLASMA_CONFIG, затем PrismLauncher /
# Legacy Launcher (.minecraft) / MultiMC, затем рекурсивный поиск по %APPDATA%.
# Если нужен конкретный файл, укажите -Config <путь\plasma.properties>.

param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Payload,
    [string]$Config = $env:PLASMA_CONFIG
)

if (-not $Config) {
    $candidates = @(
        (Join-Path $env:APPDATA "PrismLauncher\instances\26.2\minecraft\config\plasma.properties"),
        (Join-Path $env:APPDATA ".minecraft\config\plasma.properties"),
        (Join-Path $env:APPDATA "MultiMC\instances\26.2\minecraft\config\plasma.properties")
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $Config = $c; break }
    }
}
if (-not $Config) {
    $found = Get-ChildItem $env:APPDATA -Recurse -Filter "plasma.properties" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "config" } | Select-Object -First 1
    if ($found) { $Config = $found.FullName }
}
if (-not $Config -or -not (Test-Path $Config)) {
    Write-Error "Конфиг не найден. Укажите -Config <путь\plasma.properties> или PLASMA_CONFIG."
    exit 1
}

$token = (Get-Content $Config | Where-Object { $_ -like "token=*" } | Select-Object -First 1).Substring(6).Trim()
$code = Get-Content $Payload -Raw

$py = "python3"
if (-not (Get-Command $py -ErrorAction SilentlyContinue)) { $py = "python" }

$script = Join-Path $PSScriptRoot "..\scripts\send_payload.py"
& $py $script "127.0.0.1" $Port $token $code
