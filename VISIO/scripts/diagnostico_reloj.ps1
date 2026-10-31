param([string]$Serial)
. "$PSScriptRoot\entorno.ps1"
$watch = Get-WatchSerial $Serial
foreach ($prop in @('ro.product.model','ro.build.version.release','ro.build.version.sdk','ro.build.display.id')) {
    Write-Host $prop
    Invoke-AdbChecked -s $watch shell getprop $prop
}
$packages = & $script:AdbPath -s $watch shell pm list packages
$healthPackages = @($packages | Where-Object { $_ -match 'health|sensor' })
$healthPackages
Write-Host 'Abre solo el paquete identificado como Health Sensor Service / Health Platform. No confundir con Samsung Health.'
Invoke-AdbChecked -s $watch shell dumpsys battery
