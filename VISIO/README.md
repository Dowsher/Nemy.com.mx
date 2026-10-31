# Visio · sistema experimental de acompañamiento

Visio explora el uso del acelerómetro y giroscopio de un reloj para reconocer patrones de movimiento y activar señales de acompañamiento. El sistema integra una aplicación Wear OS, una aplicación Android y un LED mediante ESP32-S3 y Bluetooth. Es un prototipo en desarrollo: **un patrón de movimiento no demuestra consumo ni identifica una sustancia**, y la precisión, los falsos positivos y el efecto de la intervención aún requieren validación. No sustituye atención profesional ni debe usarse para vigilar a otra persona.

## Revisar el proyecto

[Descargar el código y documentación completos](Visio-fuente-completa.zip). El ZIP conserva la estructura original con `app/` (reloj), `phone/` (teléfono), `core/` (lógica compartida), `esp32s3-led/` (firmware), pruebas y scripts. Esta copia no incluye los APK de depuración, mediciones personales ni el SDK propietario de Samsung. Los binarios de firmware incluidos corresponden a la entrega de desarrollo; revisar el dispositivo antes de instalarlos.

Para una lectura rápida están disponibles aquí [las reglas de gesto](GestureDetector.kt), [el servicio de captura del reloj](RecorderService.kt), [el enlace con el LED](EspLedService.kt), [el protocolo de comunicación](LocalProtocol.kt), [el protocolo del LED](LedProtocol.kt) y [el firmware ESP32](main.c). Los nombres de estos archivos son copias para facilitar la revisión; el ZIP conserva sus rutas originales.

## Compilación y pruebas

Abrir el proyecto extraído en Android Studio con JDK 21, Android SDK Platform 37 y Build-Tools 36. El SDK de sensores Samsung no se redistribuye: se obtiene en el portal oficial de Samsung bajo sus condiciones. Sin él, el proyecto documenta una variante de movimiento con `-PmotionOnly=true`. Consultar el README dentro del ZIP y [SDK-PROVENIENCIA.txt](SDK-PROVENIENCIA.txt) para los pasos y condiciones.

Las pruebas Python del analizador se ejecutan con `python -m unittest discover -s tools -p "test_*.py"`. En la revisión de esta copia pasaron 8 pruebas; esto no valida la detección en personas. El [estado del piloto](ESTADO.md) describe las verificaciones previas y lo que falta probar. Consulta también la [guía del teléfono](APP-TELEFONO.md).

## Privacidad y estado

El proyecto está diseñado para procesar y guardar los datos localmente y comunicar los dispositivos mediante Bluetooth. Antes de usarlo se debe comprobar la configuración concreta, sus permisos y el consentimiento de cada persona. No hay licencia de código abierto declarada; consulta con el propietario antes de reutilizarlo. Revisión de origen: `946665690098e0c517b88f6a14f52fbd8d5692f0`.
