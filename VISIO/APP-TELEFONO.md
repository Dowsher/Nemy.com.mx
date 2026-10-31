# Visio Mobile 0.4.1 — exploración de señales y registro compacto

Herramienta del ecosistema **Nemy**. App nativa para el Samsung SM-S938B (Android 16), con tema oscuro, verde lima y grises. Esta entrega usa Visio Mobile 0.4.1 y Visio Wear 0.3.0. Se conserva el identificador interno org.pipalab.wear para actualizar ambas instalaciones sin perder historial, permisos o claves del vínculo. El nombre visible es Visio.

## Uso diario

1. En el reloj, abrir Visio y tocar **Enlazar teléfono**. Después elegir el código anónimo, consentimiento e **INICIAR SESIÓN**.
2. En el teléfono, abrir Visio y tocar **Conectar**. Debe aparecer **Conectado · cifrado local** y, cuando recibe muestras nuevas, **SEÑAL EN VIVO**.
3. **En vivo**: pulso, IBI, temperatura y gráficas seleccionables de movimiento, giro, PPG verde/IR/rojo, uso y batería. Tocar una gráfica para inspeccionar un punto.
4. **Historial**: seleccionar una sesión y señal. **Ampliar** abre la gráfica en pantalla completa. Pellizcar o usar **+/−** para cambiar la escala temporal; arrastrar horizontalmente o usar las flechas para recorrer el tiempo. El gesto vertical permite seguir bajando por la pantalla. Los botones de rango eligen 10 s, 1 min, 5 min, 1 h o toda la sesión. **En directo / Volver al final** recupera el seguimiento del final. Los consumos confirmados aparecen como marcas rojas con una entrada que permite revisarlos; las pruebas y los falsos positivos no se muestran como consumos confirmados.
5. **Registrar consumo**: elegir pipa simulada, pipa, cigarro, vape u otro, momento y notas. El código guarda un evento independiente incluso sin sesión; sin señales asociadas no debe tratarse como ejemplo completo de entrenamiento.
6. **Eventos**: confirmar, descartar como falso positivo, cambiar etiqueta/notas o devolver a revisión. Se conserva la alerta original y cada decisión con hora y origen.
7. **Ajustes → Exportar historial y correcciones**: guardar un ZIP en la ubicación elegida. Para mantenerlo local, elegir el almacenamiento del teléfono.

**Promedios minuto a minuto** abre una lista desplegable con cada minuto y sensor: media, mínimo, máximo, desviación estándar, cantidad de valores válidos y cobertura temporal. Los IBI incluyen todos los intervalos válidos del lote y se asignan al minuto de su lote de origen. No se rellenan los minutos sin señal. Los históricos anteriores calculan este resumen localmente la primera vez que se abren, conservando todas sus filas.

Tocar un punto muestra su hora y campos. **Ver mediciones conservadas** abre cada fila original, con archivo e índice, en páginas de 40 registros. Un círculo hueco indica un promedio de minuto, no una lectura instantánea. **¿Qué mide y cómo ayuda?** explica cada sensor y su utilidad para Visio.

## Medir ahora · 30 segundos

Con una sesión activa y el reloj conectado, **En vivo → Medir ahora · 30 segundos** pide al reloj conservar una ventana de 30 segundos. La app muestra la solicitud, el conteo y el cierre confirmado. Al abrir la tarjeta se ven las medias de las señales recibidas y el acceso a sus lecturas individuales. También aparece en Historial. Esta medición no etiqueta un consumo ni genera una orden LED.

La captura usa los sensores que ya están activos en el reloj. Si no hay una sesión, el botón indica que debe iniciarse allí. Los sensores lentos, como temperatura, pueden no entregar datos dentro de esos 30 segundos. Una desconexión puede retrasar la confirmación o la copia; las señales conservadas se recuperan al sincronizar el archivo cerrado. No confundir «solicitando» con una captura confirmada.

## Modo compacto

El modo compacto se aplica a nuevas sesiones del reloj. Las grabaciones anteriores permanecen completas y no se borran ni reducen.

- Los sensores siguen midiéndose para análisis y gráficas en vivo. El reloj mantiene hasta 210 segundos en RAM, con un límite de 30,000 filas; el teléfono mantiene hasta 5 minutos recientes en RAM, con límites por sensor. Estas memorias no son un archivo persistente.
- Se guardan resúmenes por minuto calculados con todas las lecturas válidas disponibles, además de estados de calidad, eventos y auditoría. El resumen se publica tras el cierre del minuto con 15 segundos de margen para lotes retrasados; un lote posterior puede actualizarlo.
- Se conserva señal detallada durante las mediciones manuales de 30 segundos y alrededor de patrones candidatos y eventos: 30 segundos de contexto antes y después de la ventana del evento, limitado a la señal disponible en memoria.
- Se conservan 10 segundos cada 5 minutos como controles **sin etiquetar**. No se asume que sean negativos verdaderos; deben revisarse antes del entrenamiento.
- La detección local del reloj puede conservar una ventana candidata aunque el teléfono no esté conectado. Esto no confirma un consumo; las alertas y su revisión siguen en el teléfono.
- Un evento antiguo registrado después de agotar el búfer no puede recuperar detalle que no se guardó. La ventana exportada informa desde cuándo había memoria disponible. Fuera de segmentos retenidos habrá promedios, sin inventar muestras.

El ahorro depende del número de eventos y mediciones. Esta política facilita construir un conjunto de entrenamiento con menos filas; su suficiencia y el desempeño del futuro modelo requieren evaluación con etiquetas reales.

Al terminar, **DETENER** en el reloj. El teléfono recupera los archivos completos y solo marca la sesión como verificada cuando todos coinciden por SHA-256. Es normal que la copia tarde más que la vista en vivo. Mantener ambos dispositivos próximos hasta terminar. Un corte de Bluetooth no detiene la captura del reloj.

Después de reiniciar un dispositivo o detener forzosamente la app, reabrir Visio y activar su enlace. No se afirma continuidad automática tras un reinicio.

**En vivo requiere una sesión activa en el reloj.** Mantenerlo puesto y cerca del teléfono. Las gráficas avanzan conforme llegan lotes reales; no todas las señales tienen la misma cadencia. El pulso y los IBI solo muestran valores con calidad válida. Al conectar después de iniciar la captura se recupera la última fila disponible de cada sensor; los archivos completos se recuperan al cerrar la sesión.

## Detección y correcciones

Las alertas automáticas se activan con **Ajustes → Alertas de movimiento**. El ajuste empieza desactivado para que el investigador pueda decidir cuándo inicia el piloto.

Son reglas experimentales locales: calibración de 5 s en reposo, elevación de la muñeca, pausa y retorno; dos gestos en 180 s y separación de 300 s entre alertas. Exigen señal de reloj puesto. No identifican la sustancia ni prueban que ocurrió un consumo. Comer, beber y otros movimientos pueden parecerse.

La barra **Normal / Cuidado / Potencial consumo** usa verde, amarillo y rojo. «Normal» significa que no se observa el patrón; «Cuidado», un gesto en curso o reciente; «Potencial consumo», una secuencia candidata reciente. Se queda gris cuando faltan señal fresca, contacto, calibración o la detección está desactivada. No es un indicador clínico ni confirma consumo.

La notificación ofrece **Revisar** y **Descartar**. Descartar conserva los datos y registra un falso positivo; no elimina evidencia. Confirmar necesita una etiqueta humana. Las correcciones se guardan para un entrenamiento posterior: esta versión no se reentrena sola ni tiene una tasa de acierto validada.

**Probar una notificación** genera un evento origin=test. Estas pruebas aparecen en el filtro **Pruebas**, no en los contadores de consumo, y deben excluirse del entrenamiento. Los registros automáticos preservan versión de reglas y características del movimiento. Los manuales conservan el reloj del teléfono y una estimación de desfase cuando había enlace; no es sincronización exacta.

## ESP32-S3 · LED

La app incluye una conexión BLE independiente en **Ajustes → ESP32-S3 · LED**:

- Buscar y seleccionar una placa con firmware **VisioLED**.
- Vincular mediante el diálogo de Android usando el código temporal del monitor serie.
- Elegir pulso de 1, 3, 5 o 10 segundos.
- Activar/desactivar la salida ante nuevas detecciones.
- **Probar LED**, habilitado cuando la placa está conectada.
- Ver confirmación de recepción, error de configuración o falta de respuesta.

Se dispara al crear una alerta automática; confirmar, descartar o consultar un evento antiguo no repite el pulso. El registro manual no dispara el LED en esta versión. Solo se transmite un identificador aleatorio de orden y su duración, sin muestras, tipo de consumo ni datos personales.

Si falta conexión, se conserva la alerta y se registra que no se envió la orden. No se ejecutan pulsos antiguos al reconectar. La confirmación indica que el firmware aplicó la salida; no es una medición óptica de que un LED físico encendió.

El firmware del perfil XIAO utiliza GPIO21 activo en bajo. La vinculación, el pulso físico y la reconexión fueron comprobados. Código en `esp32s3-led/`; binarios en `firmware/VisioLED-XIAO/`. La cadena completa desde un gesto real sigue pendiente.

## Almacenamiento y exportación

- Reloj: archivos CSV/meta en Android/data/org.pipalab.wear/files/sesiones/.
- Teléfono: SQLite privado con claves únicas sesión/archivo/fila; copias originales en Android/data/org.pipalab.wear/files/archives/.
- Las gráficas reducen puntos para visualización. Las filas marcadas para conservar se escriben en SQLite y CSV; la señal efímera de modo compacto se mantiene en RAM. Los resúmenes no reemplazan filas históricas anteriores.
- La copia de sesiones cerradas es reanudable y verificada. Durante una sesión puede haber tramos aún no recuperados en el teléfono.
- Exportación: sesiones/ (vista CSV normalizada), originales/ (copias exactas disponibles), promedios_minuto.csv, ventanas_entrenamiento.csv, feedback.csv (auditoría de etiquetas), actuaciones_led.csv (órdenes y resultados), README.txt. Los promedios antiguos se generan al abrir su desplegable; los nuevos llegan desde el reloj.
- Mientras se captura o se sincroniza, la exportación puede estar incompleta. Para un corte completo, detener la sesión y esperar la verificación.
- Backups automáticos y permiso de Internet desactivados. Los archivos locales no tienen cifrado adicional de la app; dependen del almacenamiento y bloqueo de Android. El enlace reloj–teléfono sí usa autenticación y AES-GCM, con clave envuelta por Android Keystore.

El ZIP contiene señales personales: elegir su destino deliberadamente. La app no lo envía automáticamente.

## Compilar e instalar por dispositivo

Desde esta carpeta, con JDK y SDK configurados:

~~~powershell
.\gradlew.bat :phone:assembleDebug :phone:lintDebug :core:testDebugUnitTest
adb -s SERIAL_TELEFONO install --no-streaming -r phone\build\outputs\apk\debug\phone-debug.apk

.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
adb -s IP:PUERTO_RELOJ install --no-streaming -r app\build\outputs\apk\debug\app-debug.apk
~~~

No usar installDebug sin elegir módulo/dispositivo: hay teléfono y reloj con el mismo identificador. La biblioteca Samsung AAR es necesaria para compilar el proyecto completo y permanece excluida del ZIP de fuentes por su licencia.

El vínculo de estos dos dispositivos ya está provisionado. Para otros dispositivos, scripts/preparar_vinculo.py configura una clave aleatoria de 256 bits por ADB, sin escribirla en la PC. No renovar una clave solo por actualizar APK; Android conserva la clave existente si se mantiene la instalación.

## Decisión de conectividad

La opción habitual de Wear OS es [Data Layer](https://developer.android.com/training/wearables/data/overview), que puede recurrir a transporte por servidores de Google. Para conservar el requisito original de datos estrictamente locales, esta implementación usa [BLE de Android](https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview) con seguridad de aplicación en el vínculo del reloj. Es una elección deliberada para este piloto, no una recomendación general de sustituir Data Layer.

Los enlaces usan el servicio de primer plano [connectedDevice](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device). El ESP32 usa seguridad de vinculación BLE y características autenticadas; su integración se basa en los ejemplos oficiales [NimBLE de Espressif](https://github.com/espressif/esp-idf/tree/v5.5.2/examples/bluetooth/nimble/bleprph).

Gestos basados en las APIs oficiales de [gestos multitáctiles de Compose](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch). La interpretación de canales y calidad sigue las [especificaciones de Samsung Health Sensor SDK](https://developer.samsung.com/health/sensor/guide/data-specifications.html).
