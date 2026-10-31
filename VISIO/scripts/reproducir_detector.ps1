param([Parameter(Mandatory=$true)][string]$Json, [string]$Destination)
. "$PSScriptRoot\entorno.ps1"
if (-not $Destination) { $Destination = Join-Path $projectRoot ('datos\replay-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
& "$projectRoot\gradlew.bat" -p $projectRoot :core:bundleLibRuntimeToJarDebug --offline --quiet
if ($LASTEXITCODE -ne 0) { throw 'No se pudo compilar core.' }
python -I "$projectRoot\tools\eventos_json.py" $Json $Destination
if ($LASTEXITCODE -ne 0) { throw 'No se pudo convertir la exportación.' }
$stdlib = Get-ChildItem -LiteralPath "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.jetbrains.kotlin\kotlin-stdlib" -Recurse -Filter 'kotlin-stdlib-*.jar' |
    Where-Object { $_.Name -match '^kotlin-stdlib-[0-9.]+\.jar$' } | Sort-Object { [version]$_.Directory.Parent.Name } -Descending | Select-Object -First 1
if (-not $stdlib) { throw 'Falta kotlin-stdlib en la caché de Gradle; compila el proyecto una vez con red.' }
$classes = Join-Path $projectRoot 'core\build\intermediates\runtime_library_classes_jar\debug\bundleLibRuntimeToJarDebug\classes.jar'
# Mismo código Kotlin que usan el reloj y el teléfono; nada sale de esta computadora.
& "$env:JAVA_HOME\bin\java.exe" -cp "$classes;$($stdlib.FullName)" "$projectRoot\tools\ReplayDetector.java" $Destination
if ($LASTEXITCODE -ne 0) { throw 'La reproducción falló.' }
Write-Host "Resultados: $(Join-Path $Destination 'replay-v3.csv')"
