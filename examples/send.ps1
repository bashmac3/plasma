# Send an example payload to the Plasma bridge (Windows / PowerShell).
# Usage:
#   .\send.ps1 -Port 46946 -Payload .\01_hello.json
# The token config is auto-discovered by scripts/send_payload.py:
# the PLASMA_TOKEN / PLASMA_CONFIG environment variables, then common
# launcher locations.

param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$Payload
)

$code = Get-Content $Payload -Raw

$py = "python3"
if (-not (Get-Command $py -ErrorAction SilentlyContinue)) { $py = "python" }

$script = Join-Path $PSScriptRoot "..\scripts\send_payload.py"
& $py $script "127.0.0.1" $Port "" $code
