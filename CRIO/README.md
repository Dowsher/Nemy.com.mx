# CRIO

**Una página de investigación en desarrollo sobre exposición al frío y fisiología.** [Abrir CRIO](https://nemy.com.mx/crio) · [Fuente integrada](../Nemy-fuente-completa.zip)

CRIO reúne antecedentes públicos de Wim Hof, preguntas sobre respuesta al frío y fuentes para entender qué se ha estudiado. Un diagrama conceptual presenta respiración, circulación y percepción sin fingir datos de una persona. La experiencia no implementa un dispositivo ni ofrece un protocolo de inmersión.

## Evidencia y alcance

El [ensayo de Kox y colaboradores (2014)](https://pubmed.ncbi.nlm.nih.gov/24799686/) estudió 24 voluntarios sanos y una combinación de respiración, meditación y frío; no aisló el efecto del frío ni validó CRIO para tratar adicciones. Los antecedentes y marcas de Wim Hof se enlazan a [Guinness World Records](https://www.guinnessworldrecords.com/records/hall-of-fame/Wim-Hof-the-iceman). El sitio distingue observación personal de evidencia clínica y expone riesgos de la exposición al frío.

## Archivos

- `crio.html`: contenido, fuentes y estructura.
- `crio.css`: tema visual adaptable, diagramas y movimiento reducido.
- `crio-hielo.webp`, `crio-hielo-mobile.webp`: ilustraciones editoriales generadas con IA, no fotografías documentales de una persona o lugar.

Desde la raíz del ZIP, la comprobación de rutas y enlaces es:

```powershell
python tests/crio_tests.py
```

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
