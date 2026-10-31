# Visio · ecosistema Nemy

Aplicaciones Android y Wear OS para registrar señales, explorar el historial, etiquetar consumos y corregir falsos positivos. Incluye firmware ESP32-S3 para activar un LED ante nuevas detecciones experimentales. Datos locales y enlaces Bluetooth.

## Contenido

| Carpeta | Contenido |
| --- | --- |
| `phone/` | Código de Visio Mobile 0.5.0-pilot |
| `app/` | Código de Visio Wear 0.4.0-pilot |
| `core/` | Protocolos, señales, ventanas de registro y reglas compartidas |
| `esp32s3-led/` | Código de VisioLED 0.1.0, ESP-IDF 5.5.2 |
| `apps/` | APK del teléfono y reloj, iguales a las entregadas |
| `firmware/VisioLED-XIAO/` | Binarios de la placa XIAO ESP32-S3, flash de 8 MB |
| `scripts/` y `tools/` | Compilación, conexión, descarga y análisis |

Las gráficas permiten zoom, recorrido temporal, selección de puntos y marcas de consumos confirmados. El historial incluye promedios por minuto, lecturas retenidas y mediciones manuales de 30 segundos. El modo compacto conserva resúmenes y segmentos relevantes. La interfaz usa tema oscuro, verde y gris.

Guía: [APP-TELEFONO.md](APP-TELEFONO.md). Estado: [ESTADO.md](ESTADO.md). Instalación: [apps/README.md](apps/README.md). Firmware: [esp32s3-led/README.md](esp32s3-led/README.md).

## Compilar Android

Usar Android Studio, su JDK 21, Android SDK Platform 37, Build-Tools 36 y Platform-Tools. El wrapper fija Gradle y las dependencias del proyecto. `scripts/entorno.ps1` configura JDK/SDK solo para la consola actual.

```powershell
. .\scripts\entorno.ps1
.\gradlew.bat :phone:assembleDebug :app:assembleDebug
```

APK generadas: `phone/build/outputs/apk/debug/phone-debug.apk` y `app/build/outputs/apk/debug/app-debug.apk`.

El Samsung Health Sensor SDK 1.4.1 **no se redistribuye en este repositorio ni en el ZIP de fuentes**. Al clonar el repositorio en otra computadora, descargarlo desde el [portal oficial de Samsung](https://developer.samsung.com/health/sensor/overview.html), aceptar sus términos y colocar un único `.aar` en `app/libs/`. Consultar `SDK-PROVENIENCIA.txt`. Sin ese SDK se puede compilar la variante de movimiento con `-PmotionOnly=true`, que no incluye los sensores de salud Samsung.

Para los sensores Samsung, el reloj necesita Health Sensor Service y su modo de desarrollador habilitado. Esta entrega es un piloto; distribuir con ese SDK requiere atender sus condiciones. No se incluye código del ejemplo propietario de Samsung.

## Repositorio y descarga

Visio forma parte de [Dowsher/Nemy.com.mx](https://github.com/Dowsher/Nemy.com.mx), dentro de `VISIO/`. Para trabajar desde una copia nueva:

```powershell
git clone https://github.com/Dowsher/Nemy.com.mx.git
cd Nemy.com.mx/VISIO
```

El código completo se puede revisar directamente en estas carpetas o descargar como [ZIP de fuentes](Visio-fuente-completa.zip). Las [APK del teléfono y reloj](apps/) y el [firmware de XIAO ESP32-S3](firmware/VisioLED-XIAO/) están disponibles por separado; el ZIP de fuentes no duplica las APK. Incluye las pruebas, scripts y documentación necesarios para reconstruir el proyecto, con el SDK Samsung como dependencia externa.

`ENTREGA.json` identifica las revisiones de origen y versiones de la entrega. `SHA256SUMS.txt` permite comprobar los archivos publicados; el ZIP lleva su propio manifiesto de integridad. No se incluyen mediciones, etiquetas personales, perfiles de entrenamiento, respaldos de dispositivos, credenciales ni claves privadas. `.gitignore` también excluye el AAR propietario y los archivos locales de desarrollo. No se asignó una licencia de código abierto al proyecto.

Las copias de lectura rápida en la raíz (`GestureDetector.kt`, `RecorderService.kt`, `EspLedService.kt`, `LocalProtocol.kt`, `LedProtocol.kt` y `main.c`) se mantienen sincronizadas con sus rutas de compilación en `core/`, `app/`, `phone/` y `esp32s3-led/` para conservar los enlaces existentes.

## Límites del piloto

Las alertas combinan reglas experimentales con un perfil personal aprendido localmente de eventos revisados; no son un diagnóstico ni tienen precisión validada. No prueban que hubo consumo. Las correcciones se conservan para entrenamiento posterior. El pulso/IBI requieren contacto y calidad válida; EDA no estuvo disponible en el reloj utilizado. La prueba completa de gesto real → alerta → LED y las pruebas largas quedan a cargo del usuario.

## Detector personal y captura esencial

La versión 0.5 conserva pulso, temperatura y movimiento de 25 Hz. El detector usa ventanas personales revisadas y gestos coherentes, sin giroscopio. El perfil se reconstruye en el almacenamiento privado del teléfono. Ver [DETECTOR-Y-CAPTURA.md](DETECTOR-Y-CAPTURA.md) para funcionamiento, comprobaciones y límites. El JSON personal de consumos entregado por separado no pertenece a este repositorio.
