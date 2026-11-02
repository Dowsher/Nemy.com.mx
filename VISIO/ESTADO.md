# Estado · 2 de noviembre de 2026

Teléfono 0.9.0-live y Wear 0.7.0-feedback compilados e instalados; hashes comprobados. 50 pruebas Android, 3 de referencia Python y 2 del capturador Zoom aprobadas. Lint sin errores. En el teléfono real coincidieron 3,221 valores numéricos con la referencia sobre un fixture sintético de diez minutos (43 gestos, ~0.67 s de cálculo).

Se conservaron 260 archivos externos del teléfono y 252 del reloj, además de sus archivos privados durante las actualizaciones. SQLite 6 añade tablas de detecciones/revisiones sin borrar el historial. Se comprobó interfaz y enlace cifrado. Respaldo privado conservado fuera del repositorio.

El detector conserva BootstrapScorer released=false. Las preguntas recogen etiquetas de candidatos, no demuestran precisión validada. Los colores no son límites clínicos. El audio impulsa una demo acústica; no reconoce exclusivamente chasquidos/encendedores. La calibración anatómica y la validación física con nuevas actividades/LED siguen pendientes del usuario. No se grabó una sesión nueva para estas comprobaciones.

[Guía de uso y límites](DETECTOR-EN-VIVO.md) · [Resultados](verificacion/LIVE-0.9.json).
