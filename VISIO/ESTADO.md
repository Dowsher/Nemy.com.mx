# Estado de la entrega

Preparada el 31 de octubre de 2026 para el ecosistema Nemy.

- Visio Mobile 0.4.1-pilot y Visio Wear 0.3.0-pilot instalados; los APK incluidos coinciden con los entregados por SHA-256.
- VisioLED 0.1.0 instalado en ESP32-S3 con perfil XIAO, LED integrado GPIO21 activo en bajo. Vinculación, recepción de órdenes, encendido/apagado físico y reconexión probados.
- Compilación y Lint sin errores. Trece pruebas compartidas y dos del reloj aprobadas durante el desarrollo; persisten advertencias de Lint documentadas en el proyecto de trabajo.
- Captura compacta y medición manual de 30 segundos comprobadas; archivos cerrados transferidos por BLE y cotejados con SQLite. Se verificaron zoom, recorrido temporal, promedios y acceso a lecturas individuales.
- La detección sigue siendo experimental; no se validó precisión ni autonomía de 60 minutos/8 horas. El usuario continuará las pruebas.

Este paquete contiene software y documentación. Las mediciones, exportaciones personales, capturas de los dispositivos, registros de ejecución y respaldos de flash se conservan en el proyecto de trabajo original y no se incluyen aquí.
