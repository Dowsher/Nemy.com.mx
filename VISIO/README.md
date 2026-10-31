# Visio · ecosistema Nemy

Apps Android/Wear OS para crear una base de actividades con señales reales, audio opcional, descripciones por dictado local y revisión humana. Teléfono **0.7.0-training**, reloj **0.5.0-training**.

**Aprender** ajusta modelos personales con movimiento, pulso y temperatura, compara versiones con sesiones reservadas y conserva la posibilidad de volver atrás. No aprende de sus propias predicciones. Las sugerencias requieren evidencia suficiente y se abstienen ante señales ambiguas. La demo acústica → LED responde a impulsos breves; todavía no es un reconocedor entrenado de encendedores o chasquidos.

## Empezar

Lee [ENTRENAMIENTO.md](ENTRENAMIENTO.md). Abre Visio en el reloj y enlaza el teléfono. En Capturar elige tipo, descripción, duración y audio; confirma el inicio en el reloj. Al terminar, revisa la sesión y acepta, corrige o excluye el ejemplo. Reserva algunas sesiones para evaluación antes de iniciarlas.

| Carpeta | Contenido |
| --- | --- |
| phone/ | App del teléfono, revisión y aprendizaje local |
| app/ | Captura del reloj, audio y enlace cifrado |
| core/ | Protocolos, señales, WAV y detector de impulsos de demostración |
| apps/ | APK instaladas, con hashes en ENTREGA.json |
| tools/ | Capturador Zoom para Windows y pruebas |
| esp32s3-led/ | Firmware ESP-IDF del LED |
| firmware/VisioLED-XIAO/ | Binarios XIAO ESP32-S3 |

El historial previo se conserva; las guías APP-TELEFONO.md y DETECTOR-Y-CAPTURA.md documentan versiones anteriores. La interfaz vigente es la de entrenamiento.

## Compilar

Android Studio/JDK 21, SDK Platform 37 y Build-Tools 36. En PowerShell:

```powershell
. .\scripts\entorno.ps1
.\gradlew.bat :core:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug :app:assembleDebug
```

El Samsung Health Sensor SDK no se redistribuye. Descárgalo desde el [portal oficial](https://developer.samsung.com/health/sensor/overview.html), acepta sus condiciones y coloca un único AAR en app/libs. Ver SDK-PROVENIENCIA.txt. `-PmotionOnly=true` permite compilar sin sensores Samsung.

Para la Zoom: ejecuta tools/ABRIR-CAPTURA.cmd con Python 3.11+ con Tcl/Tk y FFmpeg. Los WAV y etiquetas permanecen en Descargas/Visio-Captura-Zoom/capturas. El índice se regenera con tools/index_zoom_dataset.py. No se graba hasta pulsar Grabar.

## Entrega y límites

Esta carpeta contiene código, APK y firmware; excluye mediciones, audio personal, modelos privados, respaldos y claves. Los modelos se crean dentro del teléfono y solo salen mediante exportación local del usuario. No se asignó una licencia de código abierto al proyecto. [Estado verificable](ESTADO.md).

El proyecto se integra en [Dowsher/Nemy.com.mx](https://github.com/Dowsher/Nemy.com.mx) bajo VISIO/. La evaluación reservada no demuestra generalización a personas o ambientes nuevos. El reconocimiento aprendido de sonidos sigue pendiente; los WAV son material de entrenamiento. La cadena física nueva reloj/audio/dictado/LED requiere pruebas del usuario.

[Descargar código completo](Visio-fuente-completa.zip) · [APK instalables](apps/)
