package org.pipalab.wear

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

class RecorderService : Service(), SensorEventListener2, HealthSink {
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var manager: SensorManager
    private var writer: SessionWriter? = null
    private var health: HealthSource? = null
    private var wake: PowerManager.WakeLock? = null
    private val activeSensors = mutableListOf<Sensor>()
    private val flushPending = mutableSetOf<Int>()
    private var healthFlushed = false
    private var stopping = false
    private var cutoff = Long.MAX_VALUE
    private var reason = "user"
    private var event: String? = null
    private var eventId = ""
    private var battery = -1
    private var healthStatus = "Sin comprobar"
    private var lastBattery = 0L
    private var lastCheckpoint = 0L
    private val main = Handler(Looper.getMainLooper())
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val t = SystemClock.elapsedRealtimeNanos()
            handler.post { safe { if (t <= cutoff) writer?.write("pantalla", t, listOf(if (intent.action == Intent.ACTION_SCREEN_ON) 1 else 0)) } }
        }
    }
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(SENSOR_SERVICE) as SensorManager
        worker = HandlerThread("VisioCapture", android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE).also { it.start() }
        handler = Handler(worker.looper)
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED) else registerReceiver(screenReceiver, filter)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(NotificationChannel("recording", "Registro de investigación", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                if (Live.state.value.active) return START_NOT_STICKY
                val cfg = SessionConfig(intent.getStringExtra("participant") ?: "", intent.getStringExtra("wrist") ?: "izq",
                    intent.getStringExtra("dominant") ?: "der", intent.getStringExtra("power") ?: "auto", intent.getBooleanExtra("consent", false))
                if (!cfg.consent || !cfg.participant.matches(Regex("[A-Za-z0-9_-]{1,24}"))) { stopSelf(); return START_NOT_STICKY }
                try {
                    if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
                    else startForeground(1, notification())
                    Live.state.value = RecorderStatus(active = true, message = "Preparando sesión…")
                    handler.post { safe { begin(cfg) } }
                } catch (e: Exception) { Live.state.value = RecorderStatus(message = "No se pudo iniciar: ${e.message}"); stopSelf() }
            }
            "STOP" -> handler.post { safe { requestStop("user", intent.getLongExtra("time", SystemClock.elapsedRealtimeNanos())) } }
            "RETAIN" -> handler.post {
                val id=intent.getStringExtra("id").orEmpty()
                runCatching {
                    val out=writer ?: error("Sin sesión activa");check(!stopping)
                    val now=System.currentTimeMillis();val manual=intent.getBooleanExtra("manual",false)
                    val from=if(manual)now else intent.getLongExtra("from",now)-30000
                    val to=if(manual)now+30000 else intent.getLongExtra("to",now)+30000
                    require(from<=now+5000 && to<=now+60000 && to>=from && to-from<=600000)
                    val w=out.retain(id,if(manual)"medicion_manual" else intent.getStringExtra("kind").orEmpty().take(50),from,to)
                    PhoneBridge.notices.offer(JSONObject().put("type","retain_ack").put("id",id).put("ok",true).put("session",out.dir.name)
                        .put("start",w.start).put("end",w.end).put("available_from",w.availableFrom).toString())
                }.onFailure{PhoneBridge.notices.offer(JSONObject().put("type","retain_ack").put("id",id).put("ok",false).put("message",it.message).toString())}
            }
            "MARK" -> {
                val name = intent.getStringExtra("event") ?: ""
                val t = intent.getLongExtra("time", SystemClock.elapsedRealtimeNanos())
                handler.post { safe { if (!stopping && writer != null && name in EVENT_NAMES) mark(name, t) } }
            }
            "SYNC" -> {
                val t = intent.getLongExtra("time", SystemClock.elapsedRealtimeNanos())
                handler.post { safe { if (!stopping) writer?.write("marcadores", t, listOf("SYNC", "POINT", UUID.randomUUID().toString(), "Dar 3 aplausos; alinear picos IMU offline")) } }
            }
        }
        return START_NOT_STICKY // No inventar continuidad después de muerte del proceso/reinicio.
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "recording").setSmallIcon(R.drawable.ic_record).setContentTitle("Visio • grabando datos locales")
            .setContentText("Toca para ver la sesión").setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build()).build()
    }
    private fun begin(cfg: SessionConfig) {
        val out = SessionWriter(this, cfg); writer = out
        val inventory = JSONArray()
        var allWake = true
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)) {
            val sensor = manager.getDefaultSensor(type, true) ?: manager.getDefaultSensor(type, false)
            if (sensor == null) { inventory.put(JSONObject().put("type", type).put("available", false)); continue }
            val imu = type != Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT
            if (imu && !sensor.isWakeUpSensor) allWake = false
            // La latencia no supera la mitad del FIFO reservado/máximo estimado. Sin FIFO, no batching.
            val fifo = if (sensor.fifoReservedEventCount > 0) sensor.fifoReservedEventCount else sensor.fifoMaxEventCount
            val latency = if (imu && fifo > 0) minOf(2_000_000, fifo * 40_000 / 2) else 0
            val registered = manager.registerListener(this, sensor, if (imu) 40_000 else SensorManager.SENSOR_DELAY_NORMAL, latency, handler)
            inventory.put(JSONObject().put("type", type).put("available", true).put("registered", registered)
                .put("name", sensor.name).put("vendor", sensor.vendor).put("version", sensor.version)
                .put("wake_up", sensor.isWakeUpSensor).put("fifo_max", sensor.fifoMaxEventCount)
                .put("fifo_reserved", sensor.fifoReservedEventCount).put("min_delay_us", sensor.minDelay)
                .put("power_ma_reported", sensor.power).put("max_report_latency_us", latency))
            if (registered) activeSensors.add(sensor) else out.error("sensor_$type", "registerListener returned false")
        }
        out.put("android_sensors", inventory)
        val needsWake = cfg.power == "partial" || (cfg.power == "auto" && !allWake)
        if (needsWake) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Visio:Capture").apply {
                setReferenceCounted(false); acquire(10 * 60 * 1000L)
            }
        }
        out.put("partial_wake_lock", needsWake)
        out.write("pantalla", out.startNs, listOf(if ((getSystemService(POWER_SERVICE) as PowerManager).isInteractive) 1 else 0))
        out.write("marcadores", out.startNs, listOf("SESSION", "START", out.dir.name, ""))
        // -1 explícito hasta recibir el primer evento; nunca asumir que el reloj está puesto.
        out.write("offbody", out.startNs, listOf(-1, -1, out.startNs, null))
        health = HealthFactory.create(this, handler, this).also { it.start() }
        sampleBattery()
        handler.post(tick)
    }
    override fun onSensorChanged(e: SensorEvent) = safe {
        val out = writer ?: return@safe
        val t = e.timestamp
        val received = SystemClock.elapsedRealtimeNanos()
        // Un sensor on-change entrega su estado al registrarse. En este reloj conserva
        // el timestamp del cambio anterior a la sesión. Aplicarlo desde la recepción
        // y conservar ese origen evita descartar el estado o inventar tiempo conocido.
        if (e.sensor.type == Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT) {
            val effective = if (t < out.startNs) received else t
            if (effective <= cutoff) out.write("offbody", effective, listOf(e.values[0].toInt(), e.accuracy, received, t))
            return@safe
        }
        if (t < out.startNs || t > cutoff) return@safe
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> out.write("acelerometro", t, listOf(e.values[0], e.values[1], e.values[2], e.accuracy, received), period = 40_000_000)
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* Cada muestra incluye accuracy. */ }
    override fun onFlushCompleted(sensor: Sensor) { flushPending.remove(sensor.type); maybeFinish() }
    private fun mark(name: String, t: Long) {
        val out = writer ?: return
        val old = event
        if (old != null) out.write("marcadores", t, listOf(old, "END", eventId, if (name != old) "change" else ""))
        event = if (old == name) null else name
        if (event != null) { eventId = UUID.randomUUID().toString(); out.write("marcadores", t, listOf(name, "START", eventId, "")) }
        Live.state.value = Live.state.value.copy(event = event)
    }
    private fun sampleBattery() {
        val out = writer ?: return
        val t = SystemClock.elapsedRealtimeNanos()
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = b?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        battery = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charge = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).takeIf { it != Int.MIN_VALUE }
        out.write("bateria", t, listOf(battery, if ((b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0) 1 else 0,
            (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0, b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0), charge))
        lastBattery = t
    }
    private val tick = object : Runnable {
        override fun run() = safe {
            val out = writer ?: return@safe
            if (stopping) return@safe
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastBattery >= 300_000_000_000L) { sampleBattery(); wake?.acquire(10 * 60 * 1000L) }
            if (now - lastCheckpoint >= 5_000_000_000L) {
                val actualWall = System.currentTimeMillis()
                if ((now / 5_000_000_000L) % 12 == 0L) out.write("reloj", now, listOf(actualWall, actualWall - now / 1_000_000))
                out.checkpoint(now - lastCheckpoint >= 60_000_000_000L || (now / 5_000_000_000L) % 12 == 0L)
                lastCheckpoint = now
                if (out.dir.usableSpace < 20L * 1024 * 1024) { requestStop("low_storage", now); return@safe }
            }
            val counts = out.stats.mapValues { it.value.count }
            val hz = out.stats.mapValues { it.value.hz() }
            val stale = listOf("acelerometro").any { now - (out.stats[it]?.last ?: out.startNs) > 6_000_000_000L }
            Live.state.value = RecorderStatus(true, false, (now - out.startNs) / 1_000_000_000, hz, counts, battery, event,
                if (stale) "AVISO: IMU sin datos recientes" else "Grabando", out.dir.name, healthStatus)
            handler.postDelayed(this, 1000)
        }
    }
    private fun requestStop(why: String, t: Long) {
        val out = writer ?: run { stopSelf(); return }
        if (stopping) return
        // El botón se recibe en main y se procesa en el hilo de captura. No cerrar
        // retroactivamente antes de muestras ya escritas mientras se entregaba STOP.
        stopping = true; cutoff = maxOf(t, SystemClock.elapsedRealtimeNanos(), out.startNs); reason = why
        out.put("requested_stop_elapsed_ns", t)
        Live.state.value = Live.state.value.copy(stopping = true, message = "Vaciando buffers…")
        event?.let { out.write("marcadores", cutoff, listOf(it, "END", eventId, "session_stop")) }; event = null
        out.write("marcadores", cutoff, listOf("SESSION", "END", out.dir.name, why))
        // Mantener registros durante flush; ignorar muestras posteriores al límite de sesión.
        flushPending.addAll(activeSensors.map { it.type })
        if (activeSensors.isEmpty()) flushPending.clear()
        else if (!manager.flush(this)) { out.error("sensor_flush", "flush returned false"); /* conservar pendientes */ }
        health?.flush { healthFlushed = true; maybeFinish() } ?: run { healthFlushed = true }
        handler.postDelayed({ if (writer != null && stopping) safe { finish(false) } }, 15_000)
        maybeFinish()
    }
    private fun maybeFinish() { if (stopping && healthFlushed && flushPending.isEmpty()) safe { finish(true) } }
    private fun finish(flushed: Boolean) {
        val out = writer ?: return
        manager.unregisterListener(this); activeSensors.clear(); health?.stop(); health = null
        if (!flushed) out.error("flush", "Timeout de 15 s; revisar cola final")
        sampleBattery()
        out.finish(cutoff, reason, flushed)
        writer = null; wake?.let { if (it.isHeld) it.release() }; wake = null
        handler.removeCallbacksAndMessages(null)
        Live.state.value = Live.state.value.copy(active = false, stopping = false, event = null, message = if (flushed) "Sesión guardada" else "Guardada con aviso de flush")
        main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun row(stream: String, sourceMs: Long, receivedNs: Long, receivedWall: Long, batch: Long, index: Int, size: Int, values: List<Any?>) = safe {
        val out = writer ?: return@safe
        // La referencia solo garantiza milisegundos: retener origen y declarar cualquier inferencia.
        val epochLike = abs(receivedWall - sourceMs) <= 86_400_000L
        val elapsedLike = sourceMs >= 0 && sourceMs <= receivedNs / 1_000_000 + 1000 && receivedNs / 1_000_000 - sourceMs <= 86_400_000L
        val basis = when { epochLike -> "epoch_ms_inferred"; elapsedLike -> "elapsed_ms_inferred"; else -> "receipt_only_unknown" }
        val t = when { epochLike -> receivedNs + (sourceMs - receivedWall) * 1_000_000; elapsedLike -> sourceMs * 1_000_000; else -> receivedNs }
        if (t < out.startNs || t > cutoff) return@safe
        val wall = if (epochLike) sourceMs else out.wall(t)
        out.write(stream, t, listOf(sourceMs, receivedNs, receivedWall, basis, batch, index, size) + values, wall)
    }
    override fun info(key: String, value: Any) = safe { writer?.put(key, value) }
    override fun status(text: String) { healthStatus = text; Live.state.value = Live.state.value.copy(health = text) }
    override fun error(source: String, message: String) = safe { writer?.error(source, message) }
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (e: Exception) {
            manager.unregisterListener(this); runCatching { health?.stop() }
            runCatching { writer?.finish(SystemClock.elapsedRealtimeNanos(), "error:${e.javaClass.simpleName}", false) }; writer = null
            wake?.let { if (it.isHeld) it.release() }; wake = null
            Live.state.value = Live.state.value.copy(active = false, stopping = false, message = "Registro interrumpido: ${e.message}")
            main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
    }
    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        handler.post { safe {
            manager.unregisterListener(this); health?.stop()
            writer?.finish(SystemClock.elapsedRealtimeNanos(), "service_destroyed", false); writer = null
            wake?.let { if (it.isHeld) it.release() }; wake = null
            Live.state.value = Live.state.value.copy(active = false, stopping = false)
            worker.quitSafely()
        } }
        super.onDestroy()
    }
}
