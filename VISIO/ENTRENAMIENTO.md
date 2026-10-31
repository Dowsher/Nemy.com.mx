# Visio · sesiones de actividades y audio

Versión de teléfono 0.7.0-training; reloj 0.5.0-training. Herramienta del ecosistema Nemy.

## Registrar una actividad

1. Abre Visio en el reloj y toca **Enlazar teléfono**. En el teléfono, **Capturar → Conectar reloj**.
2. Elige un tipo de actividad o crea uno. Describe postura, mano, objetos, distancia y ambiente. Elige de 30 segundos a 10 minutos y si quieres audio del reloj.
3. Pulsa **Preparar sesión en el reloj**. En el reloj revisa la preparación, concede los permisos solicitados, acepta esa captura y pulsa **INICIAR**. Una solicitud del teléfono prepara el registro: el micrófono se inicia desde la pantalla visible del reloj.
4. En el teléfono, **Dictar descripción** inicia reconocimiento de voz local mientras el reloj registra sensores y, si lo elegiste, sonido. Puedes detener el dictado sin detener la sesión. Deja Visio visible para seguir dictando. La pantalla muestra errores y disponibilidad real del modelo; no hay sustitución por un servicio remoto.
5. Usa las marcas **Encendedor**, **Gesto**, **Otro sonido** o **Sincronización** como anotaciones humanas. La recepción en el reloj incluye demora Bluetooth, registrada explícitamente.
6. Detén y guarda, o espera el límite. Mantén ambos dispositivos cerca hasta **Archivos recibidos y verificados**. En **Sesiones**, revisa la descripción, escucha el audio, consulta señales y decide si es un ejemplo válido o debe excluirse. La agrupación sigue el tipo revisado de actividad.

Las pruebas de instalación o demostración deben llevar **Solo prueba técnica**. No pueden aceptarse como ejemplos de actividad. Los históricos previos se conservan separados; sus antiguas marcas no se convierten automáticamente en etiquetas de la nueva base.

## Qué se captura

- **Movimiento:** acelerómetro a 25 Hz, tres ejes en m/s², tiempo monotónico de la muestra y recepción. Registra movimientos del brazo y cambios de orientación; no demuestra por sí solo una actividad.
- **Pulso e IBI:** lecturas disponibles del Samsung Health Sensor SDK y su calidad. IBI acompaña al pulso; no se registra PPG crudo. El contacto y el movimiento pueden invalidar lecturas.
- **Temperatura de piel y ambiente:** lecturas disponibles del SDK con su estado. La temperatura de piel no es temperatura corporal central. Ausencia o estado inválido no se sustituye por un valor inventado.
- **Audio opcional:** WAV PCM de 16 bits, mono, 16 kHz, aproximadamente 1.92 MB/min. Se solicita entrada sin procesamiento cuando el dispositivo lo soporta; en otro caso VOICE_RECOGNITION. `audio.json` declara la fuente, ruta, resultado, duración y calidad. No hay escucha permanente.
- **Metadatos auxiliares:** batería, contacto del reloj, huecos, estados, sincronización y resúmenes por minuto. Sirven para evaluar calidad; no son sensores adicionales para clasificar.

Se guardan todas las muestras recibidas durante sesiones deliberadas y acotadas, además de promedios. Fuera de una sesión no se inicia la adquisición de movimiento, salud o audio. El servicio de enlace puede continuar para copiar archivos.

## Cómo se relacionan los tiempos

Los sensores conservan su tiempo monotónico; `meta.json` y `reloj.csv` relacionan ese reloj con la hora civil. Samsung conserva también los tiempos de origen y declara la inferencia usada. El WAV conserva cada muestra de sonido. `audio_timeline.csv` guarda una referencia aproximada cada segundo: posición de frame, tiempo BOOTTIME del hardware, recepción y origen de la estimación. Si Android no entrega ancla de hardware, la fila se marca `reception_estimate`.

Los dictados se guardan por frases: inicio/fin en el teléfono, estimación del desfase Bluetooth y texto original. No es alineación palabra a palabra ni sincronización exacta entre dispositivos. Revisa el texto; el resumen editable tiene 1,200 caracteres y los fragmentos reconocidos completos se conservan aparte. Si hablas durante la captura del reloj, esa voz también puede aparecer en el WAV: etiqueta o excluye esos intervalos al estudiar sonidos de objetos.

## Demostración de chasquido → LED

Conecta la placa en **Ajustes → Buscar ESP32/Reconectar**, inicia una sesión con audio y activa **Demo sonora**. Tras un segundo de sonido ambiente, un impulso breve cerca del reloj puede solicitar un pulso del LED. La duración se elige entre 1, 3 y 5 segundos.

La regla usa energía y pico en ventanas de 10 ms, umbral sobre el fondo, duración breve y una pausa de dos segundos entre eventos. **No es un modelo entrenado de chasquidos o encendedores:** puede reaccionar a palmadas, golpes u otros impulsos. Los candidatos acústicos y órdenes/acuses del LED quedan auditados, sin convertirse en etiquetas confirmadas de actividad. La demo se desactiva al terminar o perder el enlace. La detección automática anterior de actividades está desactivada.

## Grabar referencias con la Zoom desde Windows

Abre `tools/ABRIR-CAPTURA.cmd` o la copia `Descargas/Visio-Captura-Zoom/ABRIR-CAPTURA.cmd`. Requiere Python con Tcl/Tk y FFmpeg. No usa servicios de red ni paquetes Python adicionales.

Selecciona **Microphone (H Series Stereo Track Usb Audio)**. Graba primero ambiente, después archivos independientes de encendedor, chasquidos, palmadas, llaves y voz. Deja 2–3 segundos entre repeticiones y varía distancias, orientación y ambientes. Cada botón **Grabar** muestra tres segundos de cuenta regresiva visual antes de abrir el micrófono. No genera pitidos que contaminen los ejemplos.

Los WAV de referencia son PCM16 mono 48 kHz. Cada carpeta contiene `audio.wav`, `session.json` y `capture.log`. El JSON conserva tipo, descripción, marcas manuales, relojes del proceso, duración real, saturación, SHA-256 y revisión. El comienzo del proceso incluye latencia del dispositivo: usa una referencia acústica compartida para alinear después la Zoom y el reloj. Los micrófonos tienen características diferentes; no se debe asumir que entrenar solo con la Zoom basta para reconocer el sonido desde la muñeca.

Revisa escuchando el archivo desde **Abrir archivos**, y luego acepta o excluye la última captura. El cierre de la ventana durante una grabación solicita primero guardar; una segunda pulsación cierra una vez finalizada. Las capturas permanecen locales en `Descargas/Visio-Captura-Zoom/capturas` y están excluidas de Git.

## Exportación y estructura para entrenar

**Exportar base de entrenamiento** produce un ZIP local:

- `dataset.json`: categorías, sesiones, especificación original, revisiones, dictados y solicitudes de marcas.
- `sesiones/<id>/`: archivos originales con hashes verificados, sensores CSV, marcadores, metadatos y audio opcional.
- `transferencia.json`: lista de archivos incluidos y estado de la copia. Si la copia sigue pendiente, espera antes de usar esa sesión.
- `actuaciones_led.csv`: solicitudes y resultados de la placa; un acuse no prueba que alguien vio la luz.

La base SQLite migra de esquema 4 a 5 mediante tablas nuevas; no borra muestras, eventos ni revisiones anteriores. Los originales se mantienen; corregir una descripción crea una entrada de revisión. La muerte del proceso del reloj recupera WAV de sesiones nuevas cuando es posible y marca la sesión **interrumpida**, nunca como finalizada limpiamente.

Para entrenar: aceptar solo sesiones revisadas, excluir `technical_test=1`, comprobar calidad/contacto y archivos completos, recortar usando audio y marcas revisadas, y recoger también muchos ejemplos negativos parecidos. Separar entrenamiento y validación por sesiones/días/personas; nunca repartir ventanas vecinas de una misma grabación entre ambos. Medir precisión, sensibilidad y falsas alarmas por hora en sesiones nuevas antes de activar una clasificación automática. Unas pocas grabaciones sirven para arrancar el conjunto, no para demostrar certeza.

## Aprendizaje automático local

La pestaña **Aprender** se actualiza al sincronizar archivos completos o guardar una revisión. Solo usa sesiones nuevas, finalizadas, verificadas, aceptadas y sin marca de prueba técnica. Exige contacto del reloj confirmado, al menos 200 muestras de movimiento durante 10 segundos, frecuencia suficiente y ausencia de huecos superiores a un segundo. El historial antiguo no se convierte en entrenamiento automáticamente.

Antes de iniciar algunas sesiones, activa **Reservar para evaluar**. Esa decisión se conserva en `original_spec.evaluation_only` del teléfono. Esas sesiones también requieren revisión humana, pero sus valores no ajustan el modelo. No se reparten ventanas vecinas de una misma sesión entre aprender y evaluar. Son ejemplos de validación para selección de versiones, no una prueba final intocable de precisión: sigue siendo necesario probar otros días y contextos.

El modelo v1 compara prototipos por actividad. Extrae ocho características de movimiento (variación, intensidad, cambios y orientación), media/variación del pulso y media de temperatura válida. Las señales de salud tienen menor peso y su ausencia permanece explícita; no se rellena con valores ficticios. La escala y radio de rechazo se calculan solo sobre entrenamiento. Distancia excesiva, señal incompleta o actividades demasiado parecidas producen **no sé**. La semejanza no se presenta como probabilidad.

Con dos clases y dos sesiones válidas de cada una se prepara una primera versión. Para habilitar sugerencias, se exigen al menos cinco sesiones de entrenamiento y dos reservadas por clase, acierto equilibrado de validación de 70% o más, respuesta en al menos 60% de las sesiones y sensibilidad de al menos 50% en cada clase. Son criterios iniciales del prototipo, no una garantía de certeza ni umbrales clínicos. Los resultados cuentan las abstenciones como fallos al calcular sensibilidad. Las matrices de confusión y características se conservan en la exportación.

Cada cambio de ejemplos o etiquetas genera una versión identificada por hash. Una candidata se compara con la activa usando el mismo conjunto de validación actual; se adopta automáticamente solo si supera los mínimos y no baja el acierto equilibrado. Puedes desactivar la adopción automática o volver a una versión previa. Al excluir o corregir un ejemplo, se comprueba la validez de las versiones anteriores para no restaurar etiquetas retiradas. Los porcentajes históricos pueden usar conjuntos distintos y no prueban por sí solos una mejora general.

Durante una sesión, el modelo habilitado compara los últimos 30 segundos recibidos y muestra una sugerencia experimental. No confirma actividades, no genera nuevas etiquetas y no activa el LED. Para corregirlo, revisa la sesión y cambia su tipo por el correcto; el siguiente ajuste usa esa corrección humana. Los modelos se guardan en almacenamiento privado y se incluyen como `aprendizaje.json` al exportar la base.

Esta primera iteración aprende actividades con **movimiento, pulso y temperatura**. El reconocimiento aprendido de encendedores/chasquidos por audio queda separado: los WAV de Zoom y reloj forman el material para ese desarrollo. La demo de impulsos → LED sigue usando su regla acústica declarada.

El criterio de separar experimentos completos sigue la documentación de [validación con grupos](https://scikit-learn.org/stable/modules/cross_validation.html#cross-validation-iterators-for-grouped-data). No se utiliza scikit-learn dentro de la APK: el cálculo es Kotlin local, sin servidor ni claves de API.

## Referencias de implementación

[Servicios con micrófono en Android](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone), [AudioRecord y marcas de tiempo](https://developer.android.com/reference/android/media/AudioRecord), [reconocimiento local de voz](https://developer.android.com/reference/android/speech/SpeechRecognizer#createOnDeviceSpeechRecognizer(android.content.Context)).
