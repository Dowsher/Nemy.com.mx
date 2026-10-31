# UMBRAL

**Una experiencia interactiva para explorar el consumo desde distintas preguntas.** [Abrir UMBRAL](https://nemy.com.mx/adicciones) · [Fuente integrada](../Nemy-fuente-completa.zip)

UMBRAL ofrece un recorrido editorial con capítulos, tarjetas y preguntas para observar relaciones posibles entre contexto, malestar y consumo. Distingue observaciones, hipótesis y evidencia; una explicación general no establece la causa de la experiencia de una persona.

## Archivos

- `umbral.html`: estructura, navegación y textos de la experiencia.
- `umbral.css`: diseño, adaptación móvil y movimiento reducido.
- `umbral.js`: navegación, interacción y estado local de la pestaña.

Las elecciones del recorrido permanecen en el almacenamiento de sesión del navegador según los avisos de la herramienta. La cuenta de comunidad es independiente. UMBRAL no diagnostica, no prescribe ni sustituye atención clínica. Las fotografías editoriales identificadas en el sitio son ilustraciones generadas con IA; el pizarrón de origen se identifica por separado.

## Ejecutar y probar

Las carpetas de GitHub reúnen los archivos principales para lectura. El proyecto integrado, sus rutas y dependencias están en `Nemy-fuente-completa.zip` en la raíz del repositorio. Para ejecutar usa el ZIP completo: estas carpetas individuales son una selección de archivos y no reproducen por sí solas la estructura del servidor.

Con Python 3.11 o posterior, crea un entorno virtual e instala las dependencias. Ejemplo para PowerShell:

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
$env:TRAZA_DATA_DIR = "$PWD\datos-locales"
$env:TRAZA_PUBLIC_ORIGIN = 'http://127.0.0.1:3230'
$env:TRAZA_SECURE_COOKIE = '0'
python -m uvicorn server:app --host 127.0.0.1 --port 3230 --no-access-log
```

Los datos de prueba locales y las claves deben quedar fuera de cualquier repositorio público. No uses información real para una instalación de revisión. Las cookies sin `Secure` son sólo para desarrollo local HTTP.

```powershell
python -m unittest discover -s tests -p '*tests.py'
```
