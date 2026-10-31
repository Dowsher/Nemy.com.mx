$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $bundledJava = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path -LiteralPath (Join-Path $bundledJava 'bin\java.exe')) { $env:JAVA_HOME = $bundledJava }
    else { throw 'Falta JDK 17+. Instala Android Studio desde https://developer.android.com/studio o configura JAVA_HOME.' }
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$script:AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $script:AdbPath)) { throw 'Falta platform-tools. Instálalo desde SDK Manager de Android Studio.' }
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
# Solo afecta esta consola; no modifica variables globales del usuario.
function Invoke-AdbChecked {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
    & $script:AdbPath @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB falló (código $LASTEXITCODE)." }
}
function Get-WatchSerial {
    param([string]$Serial)
    if ($Serial) {
        $status = & $script:AdbPath -s $Serial get-state
        if ($LASTEXITCODE -ne 0 -or $status.Trim() -ne 'device') { throw 'El dispositivo elegido no está conectado/autorizado.' }
        return $Serial
    }
    $devices = @(& $script:AdbPath devices | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw 'Conecta un solo reloj o indica -Serial para evitar actuar sobre otro dispositivo.' }
    return $devices[0]
}
