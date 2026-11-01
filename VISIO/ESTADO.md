# Entrega Visio 0.8 · 1 de noviembre de 2026

Teléfono 0.8.0-sessions y reloj 0.6.0-capture compilados. 47 pruebas Android y 2 de Zoom aprobadas. Lint: cero errores, con advertencias e incompatibilidad de comprobaciones Lifecycle de la dependencia. Los resultados están en [verificacion/SESIONES-0.8.json](verificacion/SESIONES-0.8.json).

Control por acción/corte, cámara frontal, cuatro señales del reloj, WAV, revisión, reproducción aproximada, ZIP individual y protección frente a órdenes tardías/repetidas implementados. Historial y claves preservados por actualizaciones aditivas; no se cambia SQLite 5.

**No instalado ni probado físicamente en esta entrega.** No había dispositivos conectados. Pendiente del usuario: permisos y modo listo del reloj, inicio/cierre por voz, cámara frontal real, calidad de pulso/temperatura/IMU/audio y sincronización. No se afirma precisión de detección ni alineación exacta por frame.

El modelo local existente todavía usa acelerómetro, pulso y temperatura. El audio, giroscopio y video quedan como datos para entrenamiento multimodal posterior. La placa y el firmware LED no se modificaron.
