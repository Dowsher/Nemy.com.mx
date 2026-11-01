# Visio · sesiones completas desde el teléfono

Teléfono **0.8.0-sessions**, reloj **0.6.0-capture**. Ecosistema Nemy. Entrega del 1 de noviembre de 2026.

## Grabar con «acción» y «corte»

1. Actualiza ambas APK sin desinstalar ni borrar datos. Conservan la identidad y firma del proyecto anterior. No se necesita conectar el ESP32 para capturar sesiones.
2. Una vez en el reloj, abre Visio, concede sensores/micrófono/dispositivos cercanos y pulsa **LISTO PARA EL TELÉFONO**. Android requiere activar este servicio desde su pantalla visible. Después el reloj solo captura, conserva originales y los envía; no hay que confirmar cada sesión allí. El botón Detener sigue disponible para emergencia. Tras un reinicio o cierre del servicio hay que reactivar este modo.
3. En el teléfono: **Capturar → Conectar reloj**. Elige un tipo o crea uno, descripción y duración máxima (30 s a 10 min). Puedes reservar la sesión para evaluación o marcarla como prueba técnica.
4. Pulsa **Crear nueva sesión de ejemplo**. Autoriza cámara y micrófono del teléfono. Aparece la cámara frontal y el indicador de escucha. Encuadra manos, objetos y actividad; todavía no se graban video ni sensores. Un borrador puede prepararse sin reloj, pero el inicio exige cámara y reloj listos.
5. Di **«acción»**, sola. También sirve el botón **ACCIÓN · iniciar**. El video arranca primero y luego se solicita audio/sensores al reloj; se conservan los tiempos reales de inicio. Espera la indicación en pantalla y revisa las lecturas. La sesión no se presenta como una captura completa de todas las señales si alguna falta.
6. Durante la captura puedes describir lo que haces; la opción **Transcribir también mis descripciones** guarda frases reconocidas localmente. El audio original se graba en el reloj, no en el video del teléfono.
7. Di **«corte»**, sola, o pulsa **CORTE · detener y guardar**. El video se finaliza y se envía la orden de detener audio y sensores. El límite elegido también termina la sesión. Salir de la pantalla o mandar la app al fondo cierra la captura; no se filma a escondidas.
8. En **Sesiones**, elige la actividad y grabación. Reproduce video/audio, desplázate en el tiempo, consulta señales y mediciones, revisa la descripción y acepta o excluye el ejemplo. Espera **copia verificada** para contar con los originales del reloj.
9. **Exportar solo esta sesión · ZIP** entrega ese ejemplo. **Exportar base de entrenamiento** incluye el conjunto. Los ZIP se guardan donde elijas en el selector de Android, incluido Descargas.

Los comandos se interpretan solo en resultados finales del reconocedor y como palabras completas: «hice un corte» o «voy a decir acción» son descripciones, no órdenes. La app no ejecuta comandos parciales. Una orden repetida no reinicia ni duplica archivos. El control por voz depende del servicio local de Android y de un modelo español instalado; tiene pausas entre frases y puede fallar con ruido. La pantalla muestra su estado y permite apagarlo, solicitar el idioma o usar los botones. La escucha se apaga al salir y está acotada a 15 minutos por activación. No usa un reconocedor remoto ni claves de API.

La misma pantalla de captura conserva los botones de marcas manuales y el interruptor **Demo sonido → LED**. Conecta la placa previamente en Ajustes. La demo solo se habilita con placa conectada y audio activo; se apaga al cerrar o perder enlace. Sigue siendo una regla de impulsos, no reconocimiento aprendido de un sonido específico.

## Qué queda en cada sesión

| Origen | Archivo o señal | Captura |
| --- | --- | --- |
| Cámara frontal del teléfono | `video.mp4` | CameraX SD (~480p), alrededor de 1 Mbit/s, sin pista de audio del teléfono. Si SD no existe, se usa una calidad compatible; dimensiones y duración reales quedan registradas. |
| Micrófono del reloj | `audio.wav` | PCM16 mono a 16 kHz, aproximadamente 1.92 MB/min, con fuente y calidad en `audio.json`. |
| Acelerómetro del reloj | `acelerometro_*.csv` | Tres ejes, m/s², periodo solicitado de 40 ms (25 Hz), precisión y tiempo de recepción. |
| Giroscopio del reloj | `giroscopio_*.csv` | Tres ejes, rad/s, periodo solicitado de 40 ms (25 Hz), precisión y tiempo de recepción. |
| Pulso del reloj | `heart_rate_*.csv` | BPM, IBI disponible, estados de calidad y marcas originales del SDK Samsung. |
| Temperatura del reloj | `temperatura_*.csv` | Piel y ambiente disponibles, estado de calidad, origen y recepción. No se sustituye una lectura ausente por cero. |
| Ambos dispositivos | Metadatos y anotaciones | Tipo, descripción, dictados, revisión humana, marcas, comandos, resúmenes/minuto, huecos, errores, contacto y batería. |

Durante una sesión se conserva la señal original recibida, no solo promedios. Fuera de sesiones no se adquieren estos sensores ni audio del reloj. El enlace Bluetooth y el modo listo pueden permanecer activos. El micrófono del teléfono solo se utiliza para comandos/dictado mientras la escucha está indicada.

Temperatura y pulso dependen del soporte y permisos del reloj, del contacto y del SDK. La revisión muestra muestras recibidas y con valor válido para las cuatro señales. **Archivo copiado correctamente** no significa **medición válida**. La temperatura de piel no equivale a temperatura corporal central. El micrófono también puede quedar silenciado por Android; ese estado se conserva.

## Sincronización y revisión

Cada captura tiene un UUID del teléfono. `training_sessions.watch_session` lo relaciona con la carpeta original del reloj. Antes del inicio y durante la sesión se intercambian sondas con cuatro tiempos monotónicos:

```
offset = ((watch_receive - phone_send) + (watch_send - phone_receive)) / 2
RTT = (phone_receive - phone_send) - (watch_send - watch_receive)
watch_elapsed = phone_elapsed + offset
```

Se conserva cada sonda y se prefiere la de menor RTT reciente. RTT/2 expresa incertidumbre de transporte bajo el supuesto de caminos aproximadamente simétricos. El origen del video se estima con callbacks de CameraX y duración registrada; la demora de ese callback **no está medida**. El audio conserva anclas de hardware BOOTTIME cuando están disponibles y declara el uso de estimaciones de recepción cuando no lo están. Por ello la reproducción conjunta es aproximada, no sincronización exacta por frame ni calibración científica. Conserva referencias visuales/acústicas para refinar la alineación al entrenar.

El reproductor recorre video y WAV usando esas referencias, y muestra lecturas cercanas al cursor. En Señales puedes ampliar, arrastrar, tocar mediciones y consultar promedios por minuto. El WAV también puede escucharse por separado. Los dictados siguen siendo anotaciones por frase revisables, no transcripción con tiempos por palabra.

## Desconexiones y cierres

- Si la cámara no inicia, no se envía el inicio al reloj. Si este no confirma en 15 segundos, se cierra el video conservando el motivo.
- Las solicitudes de inicio caducan y nunca se reenvían automáticamente tras reconectar. El reloj rechaza identificadores ya usados o cancelados.
- Una orden de corte queda pendiente de envío si se pierde Bluetooth; se reintenta al reconectar. El reloj mantiene su propio límite de duración. Sin enlace no se puede garantizar el corte remoto inmediato.
- El audio y los CSV permanecen en el reloj hasta su transferencia. El video permanece en el teléfono. La copia utiliza bloques reanudables y SHA-256. Un minuto de audio puede tardar más de un minuto en copiarse según Bluetooth; no se promete transferencia instantánea.
- Una interrupción del proceso conserva archivos y marca la sesión para revisión. No se afirma un cierre limpio cuando no hubo confirmación. Un MP4 truncado puede no ser reproducible; no se borra para disimular el fallo.

## ZIP y uso para entrenamiento

```
dataset.json                           tipos, sesiones, revisión, dictados
telefono/<training_id>/video.mp4        video original del teléfono
telefono/<training_id>/capture.json     estado, comandos, tiempos, sondas, SHA
sesiones/<watch_session>/meta.json      inventario y calidad del reloj
sesiones/<watch_session>/audio.wav      audio original
sesiones/<watch_session>/audio.json
sesiones/<watch_session>/audio_timeline.csv
sesiones/<watch_session>/*.csv          sensores, resúmenes, huecos y marcas
telefono-manifiesto.json                relación entre ids y hashes
transferencia.json                     archivos del reloj verificados/incompletos
```

El ZIP de una sesión filtra sesiones, revisiones, dictados y tipos; no añade datos de otras sesiones ni el historial global del LED. El ZIP de la base también puede incluir modelos locales y actuaciones del LED. Exportar mientras hay una captura activa se rechaza para evitar un archivo aparentemente completo con datos aún cambiando. Las capturas y modelos privados permanecen fuera de Git.

La pestaña Aprender y sus reglas de revisión/evaluación se conservan. Su modelo actual usa características de acelerómetro, pulso y temperatura; **todavía no aprende de video, giroscopio o audio**. Estos originales ya quedan disponibles para desarrollar y evaluar un modelo multimodal. Las predicciones no se convierten en etiquetas. Consulta [ENTRENAMIENTO.md](ENTRENAMIENTO.md) para criterios de calidad, separación por sesiones y versiones del modelo.

## Validación de esta entrega

Las apps se compilan y prueban en la computadora sin dispositivos conectados. La compilación, pruebas de software y hashes no demuestran el funcionamiento físico de cámara, micrófono del reloj, reconocimiento hablado o sincronización. La instalación y esa comprobación corresponden a la siguiente prueba del usuario. Consulta [ESTADO.md](ESTADO.md) y `ENTREGA.json` en la carpeta de distribución para los resultados de la compilación final.

Referencias oficiales: [CameraX y captura de video](https://developer.android.com/media/camera/camerax/video-capture), [SpeechRecognizer local](https://developer.android.com/reference/android/speech/SpeechRecognizer), [restricciones de servicios con micrófono](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
