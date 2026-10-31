# VisioLED 0.1.0

Firmware ESP-IDF 5.5.2 para ESP32-S3. BLE autenticado mediante NimBLE, sin Wi-Fi. Compatible con LED convencional o WS2812; el perfil XIAO usa GPIO21 activo en bajo y flash de 8 MB. Los binarios están en `../firmware/VisioLED-XIAO/`.

## Preparación

### Seeed Studio XIAO ESP32-S3

Perfil `sdkconfig.xiao.defaults` preparado para el LED integrado: GPIO21, LED convencional activo en bajo, consola USB Serial/JTAG y flash de 8 MB. Consultar la [documentación oficial de Seeed Studio](https://wiki.seeedstudio.com/xiao_esp32s3_getting_started/). El perfil corresponde a XIAO ESP32-S3 / Sense; antes de instalar, comprobar el modelo y el tamaño real de flash. La variante Plus tiene otra capacidad de flash. El LED integrado se comprobó en la placa utilizada durante el desarrollo; confirmar la compatibilidad de otra variante antes de programarla.

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
