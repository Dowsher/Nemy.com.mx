param([switch]$MotionOnly, [switch]$Install, [string]$Serial)
. "$PSScriptRoot\entorno.ps1"
Push-Location $projectRoot
try {
    $argsList = @(':app:assembleDebug',':app:testDebugUnitTest',':app:lintDebug','--console=plain')
    if ($MotionOnly) { $argsList += '-PmotionOnly=true' }
    & .\gradlew.bat @argsList
    if ($LASTEXITCODE -ne 0) { throw 'La compilación o validación falló.' }
    if ($Install) {
        $watch = Get-WatchSerial $Serial
        Invoke-AdbChecked -s $watch install --no-streaming -r (Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk')
        Invoke-AdbChecked -s $watch shell am start -n org.pipalab.wear/.MainActivity
    }
} finally { Pop-Location }
