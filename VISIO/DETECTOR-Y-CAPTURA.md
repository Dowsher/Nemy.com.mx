# Detector personal y captura esencial · Visio 0.5

Teléfono 0.5.0-pilot; reloj 0.4.0-pilot. Los identificadores de app se mantienen para conservar datos y claves. Los modelos personales y las señales permanecen en el teléfono; el repositorio no contiene datos de entrenamiento del usuario.

## Captura

- Acelerómetro solicitado a **25 Hz**, frente a 50 Hz de la versión anterior. Se utiliza el timestamp del sensor y batching de hasta 2 segundos, limitado por su FIFO. La frecuencia real se registra: Android puede entregar una distinta de la solicitada.
- Se solicitan únicamente `HEART_RATE_CONTINUOUS` y `SKIN_TEMPERATURE_CONTINUOUS` al SDK Samsung. PPG crudo, giroscopio y EDA no se activan.
- Pulso e IBI provienen del mismo tracker. Se conserva cada lectura recibida de pulso y temperatura, incluidos sus indicadores de calidad. Las lecturas inválidas no se presentan como valores válidos ni entrenan el perfil.
- Movimiento: promedios por minuto; señal detallada en mediciones de 30 segundos, contexto de eventos y controles de 10 segundos cada 5 minutos. El resto vive en un búfer de 210 segundos. Los controles siguen **sin etiquetar**, no se convierten en negativos.
- Contacto con la muñeca y batería son estados auxiliares necesarios para calidad y autonomía. No son nuevas señales de consumo. El reloj de sincronización se registra una vez por minuto en vez de cada 5 segundos.
- Se conservan los históricos de todos los sensores. La vista en vivo muestra las señales actuales; los sensores antiguos siguen disponibles en Historial.

La carga nominal de señales pasa de aproximadamente 126 filas/s (acelerómetro 50 + giro 50 + PPG 25 + pulso 1) a 26 filas/s (movimiento 25 + pulso 1), más la temperatura de baja cadencia. Es una reducción aproximada del 79 % en eventos de señal solicitados, **no una medición de ahorro de batería**. Las frecuencias reales, el almacenamiento y el consumo dependen de dispositivo, eventos y conexión.

## Detector `motion-personal-v2`

El detector combina dos vías. Siempre exige captura activa, contacto conocido y señal reciente:

1. **Secuencia geométrica:** filtra gravedad con una constante temporal, calibra una postura de reposo, busca elevación, pausa y retorno, y exige repeticiones con direcciones y amplitudes coherentes. Rechaza golpes fuertes, cambios lentos de postura, muestras no finitas y huecos superiores a 350 ms. Las constantes usan tiempo real, no un número fijo de muestras; funciona sin giroscopio. Un parecido mayor con ejemplos descartados exige una repetición adicional.
2. **Perfil personal:** compara descriptores de movimiento de ocho segundos con ventanas de eventos confirmados y falsos positivos. Usa orientación, aceleración dinámica, velocidad de cambio de orientación, excursión angular y proporción de pausa. Escala las distancias con dispersión robusta y mínimos por canal. Necesita al menos cuatro grupos confirmados y un descarte útil para activar esta vía. Requiere dos ventanas sin solapamiento con similitud alta y margen sobre los descartes; una ventana repetida no suma votos independientes.

Ambas vías comparten cinco minutos de separación entre alertas. Una reconexión o cambio de contacto borra evidencia parcial sin borrar esa separación. No se evalúan lotes viejos como si fueran movimiento actual. Las alertas nuevas conservan la versión, método, características y tamaño del perfil; activan la revisión humana y, si está conectado y habilitado, el LED. Entrenar, sincronizar o revisar eventos anteriores no dispara el LED.

La similitud es **una distancia transformada, no una probabilidad de consumo**. Las reglas y umbrales son de desarrollo. La frecuencia cardíaca y temperatura válidas se adjuntan como contexto; no se usan como prueba de consumo, no identifican sustancias y no crean una alerta por sí mismas.

## Aprendizaje local y etiquetas

La app reconstruye el perfil, como máximo una vez por minuto mientras el servicio está activo, cuando cambian etiquetas o datos retenidos. Emplea solo eventos revisados del mismo participante anónimo, muñeca y mano dominante. Excluye notificaciones de prueba, controles sin etiqueta, eventos sin sesión, registros manuales sin enlace y ventanas sin contacto conocido o con señal insuficiente. Cada evento aporta como máximo 16 descriptores; un evento largo no genera miles de ejemplos.

El perfil se guarda en `files/personal-detector-v2.json`, dentro del almacenamiento privado de Android. Si una corrección vuelve a «Por revisar» o cambia de clase, la reconstrucción elimina su clasificación anterior. No se incorporan medidas ni descriptores personales a la APK o al Git.

Los eventos sin sesión asociada no reciben señales de otra hora. Si no hay temperatura válida en las ventanas etiquetadas, no se ajusta un supuesto patrón térmico. Los recuentos y resultados particulares del conjunto de mediciones permanecen fuera de esta documentación pública.

## Comprobaciones y límites

Las pruebas unitarias cubren funcionamiento a 25/50 Hz, discontinuidades, valores inválidos, trayectorias opuestas, cambios de postura, separación entre alertas, descartes y evidencia temporal independiente. Compilación y Lint se ejecutan para ambos APK.

`tools/ReplayDetector.java` permite reproducir eventos privados utilizando el mismo extractor Kotlin de la app. La revisión retrospectiva separa los eventos al construir el perfil de comparación; no divide aleatoriamente filas vecinas. Una reproducción retrospectiva durante el desarrollo no constituye una tasa de acierto validada. Se requieren grupos independientes, cobertura suficiente y varios eventos negativos útiles para evaluar generalización y especificidad; las filas vecinas de una misma sesión no son observaciones independientes.

La mejora está implementada y utiliza las correcciones reales; su precisión futura debe evaluarse con más eventos independientes y actividades cotidianas etiquetadas. Las pruebas largas y de gestos físicos quedan a cargo del usuario, como solicitó.

## Referencias consultadas

- [Samsung: especificaciones de sensores](https://developer.samsung.com/health/sensor/guide/data-specifications.html): pulso con IBI y cadencias de trackers.
- [Android: SensorManager](https://developer.android.com/reference/android/hardware/SensorManager): periodo solicitado y latencia de entrega.
- [Android: batching](https://source.android.com/docs/core/interaction/sensors/batching): relación entre adquisición, FIFO y entregas al procesador.
