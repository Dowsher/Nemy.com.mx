# DTR-GA

**Simulación exploratoria de estrategias adaptativas para adultos con consumo de metanfetamina.** [Abrir la herramienta](https://nemy.com.mx/dtr-ga) · [Fuente integrada](../Nemy-fuente-completa.zip)

DTR-GA combina restricciones clínicas explícitas, optimización multiobjetivo y simulación de trayectorias para comparar escenarios sujetos a presupuesto, tiempo y disponibilidad. El modelo genera alternativas y supuestos para una conversación informada; sus porcentajes no son probabilidades individuales validadas y no constituyen diagnóstico ni prescripción.

## Flujo de la integración web

1. Captura anónima de seguridad, consumo, recursos y preferencias; las respuestas opcionales desconocidas se identifican como tales.
2. Validación de tipos, rangos y consentimiento en el servidor. La ausencia de antecedentes impide explorar medicamentos y neuromodulación.
3. Un proceso aislado ejecuta el motor original; señales de urgencia o seguridad no aclarada detienen la generación de un régimen.
4. La interfaz muestra sugerencias y supuestos y permite descargar un PDF. La sesión temporal identifica al navegador; el resultado vence 30 minutos después de finalizar y puede borrarse antes.

## Componentes para revisión

| Archivo | Responsabilidad |
| --- | --- |
| `dtr_base.py` | Esquema, parámetros y estructura del perfil. |
| `dtr_clinico.py` | Reglas, alertas y restricciones. |
| `dtr_calibracion.py` | Calibración y caché. |
| `dtr_simulador.py` | Trayectorias y resultados simulados. |
| `dtr_optimizador.py` | Búsqueda multiobjetivo NSGA-II. |
| `dtr_analisis.py`, `dtr_reporte.py` | Análisis de sensibilidad, robustez y salida del motor. |
| `dtr_api.py`, `dtr_worker.py` | Validación de entrada, aislamiento, ciclo de vida y ejecución web. |
| `dtr_presentation.py`, `dtr_pdf.py` | Presentación orientativa y PDF. |
| `dtr.html`, `dtr.js`, `dtr.css` | Captura y resultados en el navegador. |

El CLI original incluye opciones de investigación que **no están expuestas en la integración web**, incluida la posibilidad de ignorar el bloqueo. El código web no acepta ese parámetro. Las reglas se pueden leer y verificar; no sustituyen una valoración profesional ni garantizan seguridad clínica.

## Pruebas del motor

Desde la raíz del ZIP:

```powershell
python dtr_ga/dtr_main.py --autoprueba
python tests/dtr_tests.py
```

Las pruebas cubren las reglas del motor, entrada inválida, aislamiento, bloqueo de urgencias, expiración, cancelación y PDF. Su aprobación comprueba comportamiento técnico, no eficacia clínica.

## Evidencia y límites

El modelo usa supuestos y referencias a estudios, pero ni la calibración ni el desempeño en una prueba de software validan sus predicciones. Las guías y resultados de poblaciones específicas no se deben extrapolar sin evaluación. Véase la [guía ASAM/AAAP](https://www.asam.org/quality-care/clinical-guidelines/stimulant-use-disorders) y [ADAPT-2](https://doi.org/10.1056/NEJMoa2020214).

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
