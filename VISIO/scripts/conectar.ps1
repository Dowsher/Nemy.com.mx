param([string]$PairEndpoint, [string]$ConnectEndpoint)
. "$PSScriptRoot\entorno.ps1"
if ($PairEndpoint) {
    if ($PairEndpoint -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d{2,5}$') { throw 'IP:puerto de vinculación inválido.' }
    # ADB pide el código de forma interactiva: no se guarda en el script ni argumentos del proceso.
    Invoke-AdbChecked pair $PairEndpoint
}
if (-not $ConnectEndpoint) {
    Invoke-AdbChecked mdns services
    $ConnectEndpoint = Read-Host 'IP:puerto de DEPURACIÓN (pantalla anterior, distinto a vinculación)'
}
if ($ConnectEndpoint -notmatch '^\d{1,3}(\.\d{1,3}){3}:\d{2,5}$') { throw 'IP:puerto de conexión inválido.' }
Invoke-AdbChecked connect $ConnectEndpoint
Invoke-AdbChecked -s $ConnectEndpoint get-state
Invoke-AdbChecked devices -l
