param([string]$Serial, [string]$Destination)
. "$PSScriptRoot\entorno.ps1"
$watch = Get-WatchSerial $Serial
if (-not $Destination) { $Destination = Join-Path $projectRoot ('datos\' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$service = & $script:AdbPath -s $watch shell dumpsys activity services org.pipalab.wear
if (($service -join "`n") -match 'isForeground=true') { throw 'Detén la sesión y espera «Sesión guardada» antes de descargar una copia consistente.' }
$remoteRoot = '/sdcard/Android/data/org.pipalab.wear/files/sesiones'
Invoke-AdbChecked -s $watch shell ls $remoteRoot
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
Invoke-AdbChecked -s $watch pull $remoteRoot $Destination
$localRoot = Join-Path $Destination 'sesiones'
$files = @(Get-ChildItem -LiteralPath $localRoot -File -Recurse)
if ($files.Count -eq 0) { throw 'No se descargaron archivos.' }
$hashes = foreach ($file in $files) {
    $relative = $file.FullName.Substring($localRoot.Length + 1).Replace('\','/')
    if ($relative -notmatch '^[A-Za-z0-9_./-]+$') { throw "Ruta inesperada: $relative" }
    $remote = (& $script:AdbPath -s $watch shell sha256sum "$remoteRoot/$relative") -join ''
    if ($LASTEXITCODE -ne 0 -or $remote -notmatch '^([0-9a-fA-F]{64})\s') { throw "No se pudo verificar $relative en el reloj." }
    $remoteHash = $Matches[1].ToLowerInvariant()
    $localHash = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($remoteHash -ne $localHash) { throw "La copia difiere: $relative" }
    [pscustomobject]@{File=$relative;Bytes=$file.Length;SHA256=$localHash}
}
$hashes | Export-Csv -LiteralPath (Join-Path $Destination 'SHA256-verificado.csv') -NoTypeInformation -Encoding utf8
Write-Host "Descarga verificada: $($files.Count) archivos en $Destination"
