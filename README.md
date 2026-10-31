# Nemy

Herramientas digitales para comprender el consumo, explorar experiencias y preparar conversaciones de acompañamiento. [Visitar Nemy](https://nemy.com.mx/) · [Privacidad](https://nemy.com.mx/privacidad)

El proyecto parte de preguntas surgidas de una experiencia personal con el consumo y de conversaciones con personas y familias. Esa perspectiva orienta las herramientas; las observaciones individuales se distinguen de la evidencia y no sustituyen una evaluación profesional.

## Componentes

| Componente | Propósito | Estado |
| --- | --- | --- |
| [DTR-GA](DTR-GA/) | Explorar escenarios de apoyo bajo restricciones y supuestos. | Disponible; validación clínica independiente pendiente. |
| [TRAZA](TRAZA/) | Organizar respuestas y generar un informe para conversar. | Disponible; la síntesis con IA requiere consentimiento y configuración del proveedor. |
| [UMBRAL](UMBRAL/) | Recorrido educativo sobre consumo y contexto. | Disponible como experiencia informativa. |
| [CRIO](CRIO/) | Explorar fuentes y preguntas sobre exposición al frío. | Investigación en desarrollo; no es una intervención clínica. |

CAPTU es una línea en desarrollo y no se presenta como una herramienta de detección ya operativa.

## Código y ejecución

Las carpetas enlazadas permiten revisar archivos principales de cada componente. La estructura completa de ejecución y las pruebas están en [Nemy-fuente-completa.zip](Nemy-fuente-completa.zip). Las carpetas son selecciones para lectura; no son proyectos independientes instalables. Cada guía explica sus límites, dependencias y pruebas.

La aplicación utiliza Python, FastAPI y una interfaz web en HTML, CSS y JavaScript. Las instalaciones locales deben emplear datos de prueba; el repositorio no contiene expedientes reales, archivos de conversaciones privadas ni credenciales de producción.

## Evaluación responsable

Las pruebas automatizadas verifican comportamiento de software. La revisión de fuentes, el análisis de sesgos, la evaluación de seguridad y la validación clínica independiente son procesos adicionales. No se debe interpretar la salida de una herramienta como una prescripción o como garantía de resultados para una persona.

Para examinar un punto concreto, comienza por la guía de cada componente, las pruebas y el código relacionado. La retroalimentación fundamentada es bienvenida.
