# Visio · ecosistema Nemy

Apps Android/Wear OS para registrar ejemplos de actividades, revisarlos y exportarlos como datos de entrenamiento. Teléfono **0.8.0-sessions**, reloj **0.6.0-capture**.

**Crear nueva sesión → acción → corte → revisar.** El teléfono controla video frontal ~480p, audio del reloj, pulso, temperatura, giroscopio y acelerómetro. Incluye dictado local, gráficos explorables, reproducción aproximada conjunta, calidad de señales y ZIP por sesión. El reloj solo captura, conserva originales y los envía.

## Empezar

Instala las APK de apps/ sin desinstalar ni borrar datos. En el reloj concede permisos y activa **Listo para el teléfono** una vez. En el teléfono conecta, elige actividad/duración y crea la sesión. Di **acción** para empezar y **corte** para guardar, o usa los botones. El control de voz requiere un modelo español local disponible. Guía completa: [CAPTURA-SESIONES.md](CAPTURA-SESIONES.md).

| Carpeta | Contenido |
| --- | --- |
| phone/ | Control de sesiones, cámara, voz, revisión y aprendizaje local |
| app/ | Captura del reloj, audio y enlace Bluetooth cifrado |
| core/ | Protocolos, reloj, comandos, señales y reglas compartidas |
| apps/ | APK listas para instalar; hashes en ENTREGA.json |
| tools/ | Capturador Zoom para Windows y pruebas |
| esp32s3-led/ | Firmware ESP-IDF del LED |
| firmware/VisioLED-XIAO/ | Binarios XIAO ESP32-S3 existentes |

## Aprendizaje y privacidad

Aprender conserva los modelos personales por versiones, revisión humana, evaluación reservada y posibilidad de volver atrás. Su modelo actual usa acelerómetro, pulso y temperatura: todavía no aprende con video, giroscopio o audio. La demo de impulsos → LED no es un clasificador entrenado de encendedores/chasquidos. Ver [ENTRENAMIENTO.md](ENTRENAMIENTO.md).

El historial se conserva. Los archivos personales, grabaciones, bases, modelos y claves se excluyen de Git. Los ZIP de sesiones se exportan localmente desde el teléfono. El SDK Samsung AAR no se redistribuye como fuente. Las guías APP-TELEFONO.md y DETECTOR-Y-CAPTURA.md describen versiones anteriores.

## Compilar

Android Studio/JDK 21, SDK Platform 37, Build-Tools 36. En PowerShell:

```powershell
. .\scripts\entorno.ps1
.\gradlew.bat :core:testDebugUnitTest :phone:testDebugUnitTest :app:testDebugUnitTest :phone:assembleDebug :app:assembleDebug
```

Descarga el SDK desde el [portal oficial Samsung](https://developer.samsung.com/health/sensor/overview.html), acepta sus condiciones y coloca un único AAR en app/libs. `-PmotionOnly=true` compila una variante sin sensores Samsung. La compilación descarga dependencias; las APK no tienen permiso de Internet.

Para referencias Zoom, tools/ABRIR-CAPTURA.cmd requiere Python con Tcl/Tk y FFmpeg. No empieza a grabar hasta pulsar Grabar. Sus archivos personales permanecen fuera del repositorio.

## Entrega

Compiladas y probadas en PC. **Esta versión no está instalada ni probada físicamente:** no había dispositivos conectados. [Estado y límites](ESTADO.md), [resultados de software](verificacion/SESIONES-0.8.json). La sincronización entre cámaras, audio y sensores es aproximada, con sus referencias originales exportadas.

Repositorio: [Dowsher/Nemy.com.mx](https://github.com/Dowsher/Nemy.com.mx), carpeta VISIO/. No se asignó una licencia de código abierto al proyecto.

[Descargar código completo](Visio-fuente-completa.zip) · [APK instalables](apps/)
