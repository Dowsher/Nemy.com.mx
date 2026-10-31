$ErrorActionPreference = 'Stop'
$recorderScript = Join-Path $PSScriptRoot 'zoom_capture.py'
$candidates = @(
    "$env:LOCALAPPDATA\Python\pythoncore-3.14-64\pythonw.exe",
    "$env:LOCALAPPDATA\Programs\Python\Python313\pythonw.exe",
    "$env:LOCALAPPDATA\Programs\Python\Python312\pythonw.exe"
)
$recorderPython = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $recorderPython) { throw 'Instala Python 3.11 o posterior con Tcl/Tk. Después ejecuta python zoom_capture.py.' }
Start-Process -FilePath $recorderPython -ArgumentList ('"' + $recorderScript + '"') -WorkingDirectory $PSScriptRoot
