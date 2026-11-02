# Visio 0.9 · detector de referencia y revisión humana

Teléfono 0.9.0-live (12), Wear 0.7.0-feedback (9). El teléfono ejecuta localmente el archivo proporcionado `visio_detector.py`, versión `visio-ref-1.1.0`. SHA-256 del original intacto: `7319dea21fb3e94c46f1f84d469c417b6c2bd298382d0f05c8ad2150e224d55d`. El micrófono, las señales y las grabaciones no se envían a un servidor. El intérprete Python y NumPy vienen dentro de la APK ARM64.

## Uso

1. Abre Visio en el reloj y activa **Listo para el teléfono**. Conecta el reloj desde el teléfono.
2. **En vivo → Iniciar sensores + audio** abre una sesión de observación de hasta diez minutos. No abre la cámara. **Detener y guardar** cierra sus archivos. Las observaciones quedan sin etiqueta, pendientes de revisión.
3. Para video frontal y dictado, usa **Capturar → Crear nueva sesión**. Se conservan los comandos completos «acción» y «corte», así como los botones de inicio y cierre.
4. En vivo muestra pulso, temperatura de piel, acelerómetro y giroscopio. El panel de audio dibuja la envolvente de amplitud de bloques PCM reales del reloj. Sin señal válida/reciente aparece gris; no se inventan muestras.
5. Cuando llegue una pregunta al reloj, toca la notificación y responde **Sí, confirmar / No, descartar / No estoy seguro**. También puedes calificar o corregir los intervalos en **Detecciones y etiquetas** del teléfono. Los confirmados se marcan en las gráficas; permanecen el zoom, desplazamiento, promedios por minuto y detalle individual.
6. **Exportar entrenamiento** incluye `detecciones.json`. La exportación de una sesión filtra sus detecciones y revisiones; no mezcla registros de otras sesiones.

## Qué calcula y qué no está validado

El archivo suministrado contiene filtros causales, estimación de gravedad, transformación a ejes anatómicos, segmentación de elevación/permanencia/retorno, características de los gestos y un acumulador de episodios. Se ejecutan sus clases originales. Aceleración en m/s², giro en rad/s y tiempos monotónicos del reloj en ns; se conserva el marco crudo Android.

El adaptador `visio_live.py` reproduce una sesión desde un origen fijo, como máximo cada cinco segundos, en un hilo separado. Espera los dos segundos de contexto posterior reales. Omite el cierre artificial del último segmento y el `flush` de episodios que el pipeline usa para archivos completos. Los huecos no se rellenan con ceros. Los identificadores de intervalos derivan de sesión, inicio monotónico y fragmento; los duplicados no generan nuevas etiquetas.

El clasificador disponible es **BootstrapScorer**, un ranking heurístico; `released=false`. No se entregó un modelo entrenado/calibrado y no se cambia esa condición. Su índice no es una probabilidad validada de consumo. Pulso y piel permanecen como contexto y material de entrenamiento; la corroboración fisiológica del archivo está apagada por defecto. Tampoco se simulan probabilidades de encendedor a partir del audio.

Las preguntas solicitadas por el usuario son una **política separada de muestreo para revisión**, registrada así en la evidencia: índice ≥ 0.5, segmento con ≥ 80% de calidad, primer fragmento de una permanencia, señal reciente y sesión activa. No equivalen a habilitar las alertas de un modelo publicado. Máximo cuatro preguntas en 24 horas y separación de 45 minutos, conservados en SQLite. Puedes apagar las preguntas en Ajustes. Un candidato pendiente o «no sé» nunca se convierte en negativo automático. No hay reentrenamiento ni difusión automáticos de estas etiquetas.

El análisis se limita a las sesiones acotadas. Al reiniciar el servicio comienza con la señal recibida desde ese momento; el historial grabado permanece intacto, pero no se inventa el prefijo ausente ni se emiten alertas por archivos antiguos. Los segmentos aún abiertos al final de una sesión pueden no producir una revisión madura. La calibración anatómica por las dos poses descritas en el Python sigue siendo una validación física pendiente.

## Colores

Son indicadores de atención de señales, **no umbrales clínicos de peligro**. En Ajustes se editan los niveles amarillo/rojo. Valores iniciales:

| Señal | Magnitud visual | Amarillo | Rojo |
|---|---|---:|---:|
| Pulso | Cambio absoluto respecto a primera lectura válida de la vista/sesión | 15 lpm | 30 lpm |
| Piel | Cambio absoluto respecto a primera lectura válida | 0.5 °C | 1 °C |
| Acelerómetro | Valor absoluto de magnitud menos gravedad | 0.8 m/s² | 2 m/s² |
| Giroscopio | Magnitud de velocidad angular | 0.6 rad/s | 1.2 rad/s |
| Audio | Pico digital en dBFS | −18 | −6 |

La referencia de pulso/piel se puede reiniciar. No es una línea base fisiológica entrenada. Los colores no alteran el algoritmo. La barra Normal/Cuidado/Potencial consumo representa el acumulador heurístico (3 y 6), no un diagnóstico; un dato ausente o un resultado de baja calidad queda sin estado destacado.

## Respuestas, exportación y conservación

SQLite pasa de versión 5 a 6 con dos tablas añadidas; no elimina tablas ni registros. `detector_events` conserva evidencia original, versión/hash, configuración, calidad, intervalos monotónicos y de pared, características y sesión asociada. `detector_reviews` conserva cada decisión, fuente, hora de recepción, respuesta original e indicador de aplicación. Una respuesta retrasada del reloj no sobrescribe una corrección ya hecha en el teléfono.

El reloj guarda su respuesta en una bandeja persistente y la reenvía hasta recibir acuse. Los UUID evitan duplicar la auditoría. Una pregunta ignorada no cuenta como «no». Las etiquetas se refieren al intervalo indicado, no a toda la grabación. Los eventos y revisiones están en `detecciones.json`, junto a los originales y manifiestos de exportación existentes. Separar sesiones/personas al evaluar; no repartir ventanas vecinas entre entrenamiento y prueba.

## Chasquidos y ESP32

Conecta la placa desde Ajustes y habilita **Chasquidos → LED** en En vivo, Ajustes o la captura con cámara. La preferencia se conserva; el texto indica si falta conexión o micrófono. Solo se actúa sobre impulsos recientes de la sesión activa, con la placa lista. No se reproducen pulsos al reconectar.

La regla acústica v2 usa ambiente adaptativo durante el primer segundo, ataque rápido, envolvente breve de 10–100 ms y separación de dos segundos. Es una demo de transitorios compatible con chasquidos; también puede reaccionar a palmadas, golpes o encendedores. No se afirma reconocimiento exclusivo. Apagar bloquea órdenes nuevas y vuelve a comprobarse antes de la escritura BLE. Un pulso ya recibido por el firmware finaliza con su temporizador (1/3/5 s según ajustes); este firmware no tiene comando de apagado inmediato.

## Orientación

Montaje indicado: izquierda, botones hacia la mano; `sx=+1, sy=+1`. La rotación de la pantalla es independiente del sensor crudo. En el dispositivo se encontró una rotación a 90°. El firmware ignora la orientación solicitada por las apps: Visio compensa visualmente la rotación del sistema y ofrece **Ajustes → Girar pantalla 90°**, conservado entre aperturas. El gesto táctil y la interfaz giran juntos; los datos del sensor no. La comprobación del sistema Samsung confirmó muñeca izquierda y botones a la derecha. En otros relojes usa **Ajustes del sistema → General → Orientación**. No rotar artificialmente los datos CSV para arreglar la interfaz.

## Compilar y verificar

JDK 21, SDK Android del proyecto, Samsung AAR privado en `app/libs`, Python 3.11 para construir Chaquopy 17.2.0. NumPy Android 1.26.2 está fijado. Ejemplo PowerShell:

```powershell
. .\scripts\entorno.ps1
$env:VISIO_BUILD_PYTHON = 'C:\Espressif\tools\idf-python\3.11.2\python.exe'
.\gradlew.bat :core:testDebugUnitTest :phone:testDebugUnitTest :app:testDebugUnitTest :phone:assembleDebug :app:assembleDebug :phone:lintDebug :app:lintDebug
python tools/test_reference_live.py
```

Las pruebas Python de escritorio requieren NumPy y SciPy; SciPy se utiliza únicamente para el simulador de pruebas y no se incluye en la app. La prueba de paridad `visio_probe` y su fixture están solo en `src/debug/python`; son datos sintéticos explícitos, no ejemplos de entrenamiento. El debug probe no abre sensores, cámara ni micrófono ni escribe en la base de actividades. Los resultados de software y de instalación se reportan por separado en `verificacion/LIVE-0.9.json`.

Referencia técnica de empaquetado: [Chaquopy](https://chaquo.com/chaquopy/doc/current/android.html). Orientación: [Samsung](https://www.samsung.com/us/support/answer/ANS10003656/).
