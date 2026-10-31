# Detector personal y captura esencial · Visio 0.6

Teléfono 0.6.0-pilot; reloj 0.5.0-pilot (código fuente; las APK de `apps/` siguen siendo 0.5.0/0.4.0). Los identificadores de app se mantienen para conservar datos y claves. Los modelos personales y las señales permanecen en el teléfono; el repositorio no contiene datos de entrenamiento del usuario.

## Captura

- Acelerómetro solicitado a **25 Hz**, frente a 50 Hz de la versión anterior. Se utiliza el timestamp del sensor y batching de hasta 2 segundos, limitado por su FIFO. La frecuencia real se registra: Android puede entregar una distinta de la solicitada.
- Se solicitan únicamente `HEART_RATE_CONTINUOUS` y `SKIN_TEMPERATURE_CONTINUOUS` al SDK Samsung. PPG crudo, giroscopio y EDA no se activan.
- Pulso e IBI provienen del mismo tracker. Se conserva cada lectura recibida de pulso y temperatura, incluidos sus indicadores de calidad. Las lecturas inválidas no se presentan como valores válidos ni entrenan el perfil.
- Movimiento: promedios por minuto; señal detallada en mediciones de 30 segundos, contexto de eventos y controles de 10 segundos cada 5 minutos. El resto vive en un búfer de 210 segundos. Los controles siguen **sin etiquetar**, no se convierten en negativos.
- Contacto con la muñeca y batería son estados auxiliares necesarios para calidad y autonomía. No son nuevas señales de consumo. El reloj de sincronización se registra una vez por minuto en vez de cada 5 segundos.
- Se conservan los históricos de todos los sensores. La vista en vivo muestra las señales actuales; los sensores antiguos siguen disponibles en Historial.

La carga nominal de señales pasa de aproximadamente 126 filas/s (acelerómetro 50 + giro 50 + PPG 25 + pulso 1) a 26 filas/s (movimiento 25 + pulso 1), más la temperatura de baja cadencia. Es una reducción aproximada del 79 % en eventos de señal solicitados, **no una medición de ahorro de batería**. Las frecuencias reales, el almacenamiento y el consumo dependen de dispositivo, eventos y conexión.

## Detector `motion-episodes-v3`

Sustituye a `motion-personal-v2` (teléfono 0.6.0-pilot, reloj 0.5.0-pilot). Solo usa el acelerómetro, funciona igual a 25 y 50 Hz y siempre exige captura activa, contacto conocido y señal reciente.

**Episodios de mano levantada.** La gravedad se filtra con τ = 0,3 s. De ella salen la elevación del antebrazo (eje X del reloj hacia la mano) y el giro de la muñeca. Un episodio empieza cuando el antebrazo pasa de 15° y termina cuando baja 20° respecto a donde se sostuvo o gira a una postura claramente distinta (más de 40°) durante más de 1,5 s. Solo cuentan como «sostenidas» las muestras quietas: velocidad angular suavizada menor de 30 °/s y aceleración dinámica menor de 1,5 m/s². Huecos de más de 350 ms, valores no finitos o magnitudes imposibles borran el episodio en curso.

**Regla general «mano a la boca».** Alerta si el episodio cumple todo esto:

- elevación media de 30° o más;
- al menos 4 s sostenidos;
- subida de al menos 20° desde la postura mínima de los 10 s previos;
- duración total de 60 s o menos;
- movimiento fino (mediana de bloques de 1 s) de 1,5 m/s² o menos.

Así se excluyen sorbos breves, llamadas o barbilla apoyada por más de un minuto, inclinaciones bajas, sacudidas y cambios de postura sin retorno. La alerta llega al bajar la mano, unos 1,5 s después de terminar el sostén. La etapa 1 («posible») se muestra mientras la mano sigue arriba.

**Perfil personal por episodios.** Compara el episodio con los ejemplos revisados mediante siete rasgos:

- dirección media de la gravedad;
- logaritmo del tiempo sostenido;
- movimiento fino;
- subida;
- dispersión del giro.

Las escalas son robustas y tienen mínimos por rasgo. Tiene dos usos:

1. **Veto:** un episodio que cumple la regla no alerta si se parece más a un descarte que a cualquier confirmado (similitud ≥ 0,6 y margen de 0,1).
2. **Extensión:** un episodio deliberado que no cumple la regla puede alertar si se parece a ejemplos de al menos dos eventos confirmados distintos y supera a los descartes por 0,15. Se considera deliberado si tiene ≥ 15°, ≥ 3 s sostenido y ≥ 10° de subida. Requiere al menos tres grupos confirmados y un descarte. Las posturas de reposo nunca califican.

Entre alertas hay al menos 120 s. En la sesión revisada las marcas de consumo estaban separadas por 3 min o más, así que cada uno puede avisar. Una reconexión o pérdida de contacto borra el episodio en curso, pero no esa separación. Las alertas guardan método, rasgos con nombre (`elevation_deg`, `rise_deg`, `stable_seconds`…), similitudes y grupos de apoyo. La similitud es **una distancia transformada, no una probabilidad de consumo**. La frecuencia cardíaca y la temperatura se adjuntan como contexto; no prueban nada ni crean alertas.

## Aprendizaje local y etiquetas

El perfil se reconstruye como máximo una vez por minuto, con eventos revisados del mismo participante, muñeca y mano dominante. Las exclusiones son las mismas que antes: pruebas, controles sin etiqueta, eventos sin sesión, marcas sin enlace y ventanas sin contacto.

Una revisión etiqueta una ventana, no el instante exacto del acto. Por eso los ejemplos se eligen así (`MotionEpisodes.exemplars`):

- **Alerta automática:** aporta su propio episodio.
- **Marca confirmada:** aporta su episodio más parecido a «mano a la boca».
- **Descarte:** aporta todos sus episodios deliberados como negativos.
- **Ventanas solapadas:** un mismo episodio físico cuenta para un solo evento.

Las marcas manuales de consumo conservan **120 s antes** y 30 s después de la marca; antes eran 30 s. El búfer del reloj guarda 210 s, y el acto suele quedar antes de la marca.

El perfil se guarda en `files/personal-detector-v3.json`, en el almacenamiento privado de Android. No se incorporan medidas ni descriptores personales a la APK o al Git.

## Comprobaciones y límites

Las pruebas unitarias cubren los siguientes casos:

- funcionamiento a 25/50 Hz y aviso en vivo;
- separación entre alertas, también tras reconectar;
- sorbo breve, sostén de más de un minuto, inclinación baja y cambio de postura sin retorno;
- huecos, valores no finitos y sacudidas;
- igualdad entre extracción en vivo y en diferido;
- veto por descarte;
- extensión personal con grupos suficientes y su ausencia con grupos insuficientes o en reposo;
- selección de ejemplos por evento.

Compilación y Lint se ejecutan para ambos APK.

`scripts/reproducir_detector.ps1 -Json <exportación>` convierte una exportación JSON (`tools/eventos_json.py`) y reproduce cada evento con el mismo código Kotlin (`tools/ReplayDetector.java`). Cada evento se evalúa con un perfil construido sin él. El resultado queda en `datos/`, fuera de Git.

Resultados con la exportación del 31-oct-2026 (7 eventos con señal, 25 y 50 Hz):

| Detector | Consumos confirmados (6) | Simulación | Ventana descartada |
| --- | --- | --- | --- |
| v3, regla general | 1 alerta, 3,5 s antes de la marca | 1 alerta | ninguna |
| v2, reproducción anterior | candidatos en 5 | ninguno | ninguno |

En v3, los otros cinco consumos no muestran la mano del reloj elevada sobre 25° dentro de las ventanas exportadas. Puede que el acto haya quedado fuera de los ±30 s o que lo hiciera la otra mano. Los candidatos de v2 salían de su vía de ventanas de 8 s, incluso con el brazo en reposo. Su similitud con la ventana descartada era 0,95–0,96, casi igual que entre positivos, porque todos los ejemplos son de la misma sesión. Ninguna cifra es una tasa de acierto validada: las ventanas son cortas, de una sola sesión y con un solo negativo, y no permiten estimar falsas alarmas por día.

Para validar hacen falta tres cosas:

1. Sesiones de días normales.
2. Marcas hechas al empezar el acto.
3. Revisión de cada alerta, incluidas las de actividades parecidas: beber, comer, llamar o mirar el reloj.

## Referencias consultadas

- [Samsung: especificaciones de sensores](https://developer.samsung.com/health/sensor/guide/data-specifications.html): pulso con IBI y cadencias de trackers.
- [Android: SensorManager](https://developer.android.com/reference/android/hardware/SensorManager): periodo solicitado y latencia de entrega.
- [Android: batching](https://source.android.com/docs/core/interaction/sensors/batching): relación entre adquisición, FIFO y entregas al procesador.
