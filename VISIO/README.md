# Visio · ecosistema Nemy

Teléfono **0.9.0-live** y reloj **0.7.0-feedback**: capturar actividades, observar señales reales y reunir etiquetas revisables para entrenar.

**En vivo → Iniciar sensores + audio** abre una observación acotada a diez minutos, sin video. **Capturar** conserva cámara frontal ~480p, voz «acción/corte», dictado y sesiones por actividad. Pulso, piel, acelerómetro, giroscopio y audio del reloj; las modalidades no usadas desaparecen del panel.

El teléfono ejecuta localmente el Python original `visio_detector.py`. El clasificador suministrado es heurístico y no validado; no se presenta su índice como certeza de consumo. El reloj puede solicitar una etiqueta y el teléfono permite corregirla. Las respuestas, intervalos, evidencia y versión se exportan en `detecciones.json`. Los colores son umbrales de atención configurables, no peligro clínico.

Guía actual: [DETECTOR-EN-VIVO.md](DETECTOR-EN-VIVO.md). Cámara y voz: [CAPTURA-SESIONES.md](CAPTURA-SESIONES.md). Aprendizaje previo: [ENTRENAMIENTO.md](ENTRENAMIENTO.md).

| Carpeta | Contenido |
|---|---|
| apps/ | APK instalables con actualización conservando datos |
| phone/ | App móvil, Python/NumPy, cámara, revisión y exportación |
| app/ | App Wear OS, audio/sensores, preguntas y envío |
| core/ | Protocolos, señales y detector acústico de demostración |
| esp32s3-led/ | Firmware LED existente |
| tools/ | Capturador Zoom y pruebas de referencia |
| verificacion/ | Resultados de software e instalación |

Compilar requiere JDK 21, SDK del proyecto, Python 3.11 y el Samsung Health Sensor SDK privado en app/libs. Sigue los comandos de la guía actual; el AAR no se redistribuye. La APK móvil es ARM64. Los datos, grabaciones, claves y modelos personales quedan fuera de Git. El único fixture de señales incluido es sintético y está en src/debug/python para pruebas.

Ambas apps están instaladas y se verificó el enlace. La prueba física de nuevas capturas, preguntas durante una actividad y chasquidos con LED queda al usuario. Consulta [ESTADO.md](ESTADO.md) y [verificación 0.9](verificacion/LIVE-0.9.json). Las guías anteriores documentan versiones históricas.

Repositorio: [Dowsher/Nemy.com.mx](https://github.com/Dowsher/Nemy.com.mx), carpeta VISIO/.

[Descargar código completo](Visio-fuente-completa.zip) · [APK instalables](apps/)
