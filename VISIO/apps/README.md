# Apps listas para instalar

- `Visio-Mobile-debug.apk`: teléfono Android, versión 0.5.0-pilot.
- `Visio-Wear-debug.apk`: reloj Wear OS, versión 0.4.0-pilot.

Son las APK de depuración entregadas. Elegir el dispositivo correcto; ambas conservan el identificador `org.pipalab.wear` para mantener el historial.

```powershell
adb devices -l
adb -s SERIAL_TELEFONO install -r .\apps\Visio-Mobile-debug.apk
adb -s IP:PUERTO_RELOJ install -r .\apps\Visio-Wear-debug.apk
```

No hace falta reinstalar en los dispositivos que ya tienen estas versiones. No desinstalar para actualizar: se perderían los datos privados de la app. Las claves privadas de firma no se incluyen; una compilación en otra PC tendrá otra firma de depuración y no podrá reemplazar la instalación anterior sin la clave original.

Para nuevos dispositivos, con ambos APK instalados y ADB autorizado:

```powershell
python .\scripts\preparar_vinculo.py --telefono SERIAL_TELEFONO --reloj IP:PUERTO_RELOJ
```

Después activar el enlace desde ambas apps. No renovar el vínculo existente por una actualización. Consultar `APP-TELEFONO.md` para permisos, captura e historial.
