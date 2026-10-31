# VisioLED · perfil XIAO ESP32-S3

Firmware 0.1.0 compilado con ESP-IDF 5.5.2, flash de 8 MB y LED GPIO21 activo en bajo. Incluye aplicación, bootloader y tabla de particiones. No incluye memoria NVS, vínculos Bluetooth ni respaldo de una placa.

Desde esta carpeta, con esptool 4.x instalado y la placa correcta en modo Boot:

```powershell
python -m esptool --chip esp32s3 --port COM_DE_LA_PLACA write_flash '@flash_args'
```

Este comando reemplaza el firmware. Revisar modelo, puerto y particiones antes de usarlo en otra placa. No ejecutar `erase_flash` para una actualización normal. La tabla reserva NVS en 0x9000 con tamaño 0x5000.

El código y los pasos de compilación están en `../../esp32s3-led/`. Para vincular desde Visio, abrir Ajustes → ESP32-S3 · LED → Buscar placa y usar el código temporal del monitor serie. No se incluyen códigos fijos ni claves de vinculación.
