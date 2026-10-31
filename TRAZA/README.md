# TRAZA

**Un recorrido voluntario para organizar experiencias, respuestas y preguntas para una conversación de salud.** [Abrir TRAZA](https://nemy.com.mx/traza) · [Fuente integrada](../Nemy-fuente-completa.zip)

TRAZA reúne cuestionarios y rutas de distinta profundidad. Permite omitir preguntas, revisar respuestas y obtener un informe descargable. Una síntesis generada mediante un proveedor de IA requiere consentimiento específico y configuración del servicio; no debe tratarse como diagnóstico ni como validación automática de una hipótesis.

## Cómo está organizado

| Archivo | Responsabilidad |
| --- | --- |
| `journey_bank.json`, `question_support.json` | Rutas, preguntas y textos de apoyo. |
| `journey.py` | Validación del recorrido, cálculos, preparación del informe y endpoints. |
| `exploration.py` | Integración del proveedor de IA y límites compartidos. |
| `activity.py` | Registro de actividad con consentimiento y controles de acceso. |
| `journey_pdf.py` | Composición del PDF de un informe validado. |
| `journey.js`, `experience.js`, `welcome.html` | Interfaz y navegación. |

El recorrido y sus avisos describen el tratamiento de datos, la retención y el envío a un proveedor externo cuando la persona autoriza la síntesis. La funcionalidad que depende de ese proveedor no debe considerarse operativa en un entorno local sin configuración. El PDF no requiere donación.

## Pruebas enfocadas

Desde la raíz del ZIP:

```powershell
python tests/journey_tests.py
python tests/activity_tests.py
```

La batería automatizada verifica contratos y rutas técnicas. Los resultados y cuestionarios requieren una interpretación profesional; una puntuación, una síntesis o una prueba de software no validan por sí mismas una conclusión clínica.

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
