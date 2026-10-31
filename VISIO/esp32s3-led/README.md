# VisioLED — instalado y probado desde el teléfono

Instalado el 31 de octubre de 2026 en la placa Seeed Studio indicada por el usuario. El cambio al cable/puerto del teléfono recuperó COM13. Esptool identificó ESP32-S3 QFN56 revisión 0.2, PSRAM de 8 MB y flash de 8 MB, sin Secure Boot ni cifrado de flash activos. El perfil usado corresponde a XIAO ESP32-S3 / Sense; la variante comercial exacta no se identificó por una lectura de hardware.

Antes de escribir se respaldaron los 8,388,608 bytes y se comparó el digest contra la memoria de la placa: coincidencia confirmada. Respaldo local privado: `../../pruebas-esp32/backup/flash-original-20261031-8MB.bin`, SHA-256 `6c9b603fa5ef6509ad420903c0b220cd192243689d1dc42e3afa5675d0c47e06`. No se incluye en el ZIP de fuentes. La escritura de bootloader, tabla y aplicación pasó la verificación de hashes, sin borrar toda la flash.

El registro serie confirma arranque de `visio_led` 0.1.0, NVS inicializada, GPIO21 configurado y comienzo de publicidad BLE. Android descubrió VisioLED-CC16 (90:70:69:11:CC:16) y se vinculó con el código temporal del monitor, excluido de las evidencias. Una orden de prueba de 5 segundos recibió confirmación en 71 ms; el usuario confirmó visualmente que el LED encendió y se apagó. Después de reiniciar la placa, el teléfono recuperó el enlace automáticamente sin otro código y una segunda prueba de 1 segundo recibió confirmación en 74 ms.

Ambas órdenes quedaron persistidas en `led_commands` con `source=test` y sin asociarlas a un consumo. Los cuatro eventos y sus ocho registros de auditoría conservaron sus hashes; SQLite pasó la comprobación de integridad. Se dejó duración de 5 segundos y salida ante nuevas detecciones habilitada. No se ha probado todavía la cadena completa mediante un gesto real del reloj con esta placa. Evidencias: `../../pruebas-esp32/phone-led-verified.json`, capturas, `runtime.log` e `instalacion.log`.

Proyecto ESP-IDF 5.5.2, NimBLE, sin Wi-Fi. Permite LED convencional o RGB WS2812. De forma predeterminada GPIO=-1: responde que falta configurar el LED y no activa una salida.

Compilación genérica previa comprobada para ESP32-S3: imagen visio_led.bin de 0x803d0 bytes. El perfil XIAO instalado usa GPIO21 activo en bajo y consola USB: imagen de 0x7df90 bytes, comprobación de tamaño de partición aprobada. La vinculación BLE con Android, la reconexión tras reiniciar la placa y el encendido visible se probaron en la unidad del usuario.

Antes de programar, identificar modelo, revisión y tipo de LED. Las revisiones de ESP32-S3-DevKitC-1 pueden usar GPIO48 o GPIO38; no asumir un pin por el nombre genérico ESP32-S3. [Documentación oficial de placas](https://docs.espressif.com/projects/esp-dev-kits/en/latest/esp32s3/esp32-s3-devkitc-1/user_guide.html).

## Preparación posterior

### Seeed Studio XIAO ESP32-S3

Perfil `sdkconfig.xiao.defaults` preparado para el LED integrado: GPIO21, LED convencional activo en bajo, consola USB Serial/JTAG y flash de 8 MB. Consultar la [documentación oficial de Seeed Studio](https://wiki.seeedstudio.com/xiao_esp32s3_getting_started/). El perfil corresponde a XIAO ESP32-S3 / Sense; antes de instalar, comprobar el modelo y el tamaño real de flash. La variante Plus tiene otra capacidad de flash. Este perfil no constituye una prueba física.

La tabla `partitions-xiao.csv` conserva el rango NVS observado en el respaldo de esta placa: offset 0x9000, tamaño 0x5000. Los metadatos OTA antiguos en 0xe000 quedan fuera de las nuevas particiones y no se sobrescriben. La aplicación nueva inicia desde una partición factory en 0x10000; el firmware no monta ni formatea el sistema de archivos anterior. Verificar el mapa antes de reutilizar el perfil en otra placa con datos existentes.

Los botones R y B corresponden a Reset y Boot. Para entrar al cargador ROM, mantener B, pulsar y soltar R, y soltar B; alternativamente, mantener B mientras se conecta el USB y soltarlo después. Si Windows no enumera un puerto y muestra un error de descriptor, probar primero un cable y puerto de datos conocidos. El firmware mantiene la salida inactiva al habilitar el GPIO, para evitar un destello de arranque en LEDs activos en bajo.

Compilar el perfil en un directorio separado, usando un archivo `SDKCONFIG` nuevo para no heredar la configuración genérica:

~~~powershell
idf.py -B C:/ruta/corta/visio-xiao-build -D SDKCONFIG=C:/ruta/corta/visio-xiao-sdkconfig -D SDKCONFIG_DEFAULTS=sdkconfig.xiao.defaults build
~~~

### Otras placas

Con ESP-IDF activado:

~~~powershell
idf.py set-target esp32s3
idf.py menuconfig
# Visio LED: escoger GPIO, tipo y polaridad.
idf.py build
# Revisar primero el puerto y el firmware existente en la placa.
idf.py -p COM_DE_LA_PLACA flash monitor
~~~

Flashear reemplaza el firmware de la placa; revisar/respaldar el existente antes de hacerlo. Este proyecto no borra NVS automáticamente en caso de error.

En el teléfono: Ajustes → ESP32-S3 · LED → Buscar placa → VisioLED-XXXX. Android pedirá el código temporal mostrado por el monitor serie. El código cambia para cada proceso de vinculación y no está embebido en la app. La conexión exige cifrado y autenticación, con LE Secure Connections y persistencia de claves en NVS.

El firmware apaga la salida al desconectar y al vencer el temporizador. Conserva los últimos 16 identificadores de orden en RAM para no repetirlos durante ese arranque. La app no guarda órdenes para enviarlas después de reconectar.

## Protocolo v1

Servicio: a7d84e20-2d6a-4a31-995f-4a9e778cdc11
RX: a7d84e21-2d6a-4a31-995f-4a9e778cdc11 (write con respuesta, cifrado/autenticado)
TX: a7d84e22-2d6a-4a31-995f-4a9e778cdc11 (notify/read autenticado)

Orden de 19 bytes: 0x01, UUID de 16 bytes en orden big-endian, duración uint16 big-endian (100–10000 ms).
Confirmación de 18 bytes: 0x02, mismo UUID, estado:
0 salida aplicada; 1 duplicada (no repetir); 2 LED no configurado; 3 error de salida.

Cabe en el MTU mínimo. No contiene frecuencia cardiaca, señales, nombres, notas ni tipos de consumo. La app espera confirmación hasta 7 s y registra el resultado. Una confirmación del firmware no comprueba ópticamente el LED.

Referencias consultadas: ejemplos oficiales bleprph y blink incluidos en C:/Espressif/frameworks/esp-idf-v5.5.2/examples/.
