package org.pipalab.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.*
import java.io.File
import java.util.Locale

object Permissions {
    const val EXTRA = "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
    const val HR = "android.permission.health.READ_HEART_RATE"
    const val SKIN = "android.permission.health.READ_SKIN_TEMPERATURE"
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    fun foreground(): List<String> = buildList {
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (BuildConfig.SAMSUNG_ENABLED) {
            if (Build.VERSION.SDK_INT >= 36) { add(HR); add(SKIN); add(EXTRA) } else add(Manifest.permission.BODY_SENSORS)
        }
    }
    fun background(): String? = if (!BuildConfig.SAMSUNG_ENABLED) null else when {
        Build.VERSION.SDK_INT >= 36 -> BACKGROUND
        Build.VERSION.SDK_INT >= 33 -> Manifest.permission.BODY_SENSORS_BACKGROUND
        else -> null
    }
}

class MainActivity : ComponentActivity() {
    private var permissionEpoch by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionEpoch++ }
    override fun onResume() { super.onResume(); permissionEpoch++; TrainingInbox.load(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrainingInbox.load(this)
        setContent { MaterialTheme { App() } }
    }
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun link() {
        val required = listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE")
        if(required.any { !granted(it) }) permissions.launch(required.toTypedArray())
        else startForegroundService(Intent(this, LinkServerService::class.java))
    }
    @Composable private fun Big(label: String, enabled: Boolean = true, action: () -> Unit) {
        Button(onClick=action, enabled=enabled, modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) { Text(label, textAlign=TextAlign.Center) }
    }
    @Composable private fun App() {
        val live by Live.state.collectAsState()
        val pending by TrainingInbox.pending.collectAsState()
        val prefs = remember { getSharedPreferences("setup", MODE_PRIVATE) }
        var participant by rememberSaveable { mutableStateOf(prefs.getString("participant", "P001") ?: "P001") }
        var wrist by rememberSaveable { mutableStateOf(prefs.getString("wrist", "izq") ?: "izq") }
        var dominant by rememberSaveable { mutableStateOf(prefs.getString("dominant", "der") ?: "der") }
        var consent by rememberSaveable { mutableStateOf(false) }
        var audio by rememberSaveable { mutableStateOf(false) }
        var duration by rememberSaveable { mutableIntStateOf(60) }
        var type by rememberSaveable { mutableStateOf("rest") }
        var setup by rememberSaveable { mutableStateOf(false) }
        val actualAudio = pending?.audio ?: audio
        val missing = remember(permissionEpoch, actualAudio) {
            (Permissions.foreground() + if(actualAudio) listOf(Manifest.permission.RECORD_AUDIO) else emptyList()).filterNot(::granted)
        }
        val green = Color(0xFFB2FF69)
        val root = remember { getExternalFilesDir(null)?.let { File(it, "sesiones") } }
        Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(horizontal=28.dp, vertical=42.dp),
            horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Visio", color=green, fontSize=25.sp)
            Text("DATOS DE ENTRENAMIENTO", fontSize=10.sp, textAlign=TextAlign.Center)
            if(live.active) {
                Text(live.activity.ifBlank { "Registro anterior" }, fontSize=20.sp, textAlign=TextAlign.Center)
                Text("%02d:%02d".format(live.seconds / 60, live.seconds % 60), fontSize=30.sp)
                if(live.durationSeconds > 0) Text("Límite ${live.durationSeconds} s", fontSize=12.sp)
                Text(when(live.audio.state) {
                    "recording" -> if(live.audio.silenced) "MICRÓFONO SILENCIADO POR ANDROID" else "● MICRÓFONO GRABANDO"
                    "failed", "empty" -> "Audio no válido: " + live.audio.message
                    "closed" -> "Audio guardado"
                    else -> "Audio desactivado"
                }, color=if(live.audio.state == "recording" && !live.audio.silenced) green else Color(0xFFFFCE7A), fontSize=12.sp, textAlign=TextAlign.Center)
                Text("Movimiento %.1f Hz\nPulso %d · temperatura %d".format(live.hz["acelerometro"] ?: 0.0, live.counts["heart_rate"] ?: 0, live.counts["temperatura"] ?: 0), fontSize=12.sp, textAlign=TextAlign.Center)
                Big(if(live.stopping) "Guardando…" else "DETENER Y GUARDAR", !live.stopping) {
                    startService(Intent(this@MainActivity, RecorderService::class.java).setAction("STOP").putExtra("time", SystemClock.elapsedRealtimeNanos()))
                }
                Text("Marcas manuales", fontSize=13.sp)
                org.pipalab.core.ActivityTypes.markers.forEach { (id, label) -> Big("Marcar · $label", !live.stopping) {
                    startService(Intent(this@MainActivity, RecorderService::class.java).setAction("POINT").putExtra("label", id).putExtra("time", SystemClock.elapsedRealtimeNanos()))
                } }
                Text(live.message, fontSize=11.sp, textAlign=TextAlign.Center)
                Text("Batería ${live.battery}% · señales locales", fontSize=11.sp, textAlign=TextAlign.Center)
            } else {
                Text(live.message, fontSize=12.sp, textAlign=TextAlign.Center)
                Big("Enlazar teléfono", action=::link)
                if(pending != null) {
                    Text("PREPARADA EN EL TELÉFONO", color=green, fontSize=10.sp, textAlign=TextAlign.Center)
                    Text(pending!!.activity, fontSize=21.sp, textAlign=TextAlign.Center)
                    if(pending!!.description.isNotBlank()) Text(pending!!.description, fontSize=12.sp, textAlign=TextAlign.Center)
                    Text("${pending!!.durationSeconds} s · " + if(actualAudio) "Con audio" else "Sin audio", fontSize=13.sp)
                    Big("Cancelar preparación") { TrainingInbox.cancel(this@MainActivity, pending!!.id) }
                } else {
                    Text("Elige y describe la actividad en el teléfono, o usa una categoría aquí.", fontSize=12.sp, textAlign=TextAlign.Center)
                    Big(org.pipalab.core.ActivityTypes.defaults.getValue(type)) {
                        val types = org.pipalab.core.ActivityTypes.defaults.keys.toList()
                        type = types[(types.indexOf(type) + 1) % types.size]
                    }
                    Big("Duración: $duration s") { duration = when(duration) { 30 -> 60; 60 -> 120; 120 -> 300; else -> 30 } }
                    Big(if(audio) "☑ Grabar audio" else "☐ Grabar audio") { audio = !audio }
                }
                Text(if(actualAudio) "Se grabará el micrófono de este reloj durante la sesión. Detener cierra audio y sensores. Evita registrar conversaciones de otras personas."
                    else "Se guardarán movimiento, pulso y temperatura durante esta sesión.", fontSize=12.sp, textAlign=TextAlign.Center)
                Big(if(consent) "☑ Acepto esta captura" else "☐ Acepto esta captura") { consent = !consent }
                if(missing.isNotEmpty()) Big("Permitir sensores" + if(actualAudio) " y micrófono" else "") { permissions.launch(missing.toTypedArray()) }
                if(!BuildConfig.SAMSUNG_ENABLED) Text("Solo movimiento: sensores Samsung no incluidos", fontSize=12.sp, textAlign=TextAlign.Center)
                Big(if(actualAudio) "INICIAR CON AUDIO" else "INICIAR SESIÓN", consent && missing.isEmpty() && participant.matches(Regex("[A-Za-z0-9_-]{1,24}"))) {
                    val capture = pending ?: org.pipalab.core.ActivityCapture(java.util.UUID.randomUUID().toString(), type,
                        org.pipalab.core.ActivityTypes.defaults.getValue(type), "", audio, duration, System.currentTimeMillis())
                    prefs.edit().putString("participant", participant).putString("wrist", wrist).putString("dominant", dominant).apply()
                    startForegroundService(Intent(this@MainActivity, RecorderService::class.java).setAction("START")
                        .putExtra("participant", participant).putExtra("wrist", wrist).putExtra("dominant", dominant).putExtra("power", "auto")
                        .putExtra("consent", true).putExtra("capture", capture.json().toString()))
                }
                Big(if(setup) "Ocultar configuración" else "Participante y permisos") { setup = !setup }
                if(setup) {
                    Text("Código anónimo", fontSize=13.sp)
                    BasicTextField(participant, { participant = it.filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' }.take(24) }, singleLine=true,
                        textStyle=TextStyle(color=Color.White, fontSize=20.sp, textAlign=TextAlign.Center), cursorBrush=SolidColor(Color.White),
                        modifier=Modifier.fillMaxWidth().background(Color(0xFF242A2A), RoundedCornerShape(16.dp)).padding(14.dp))
                    Big("Muñeca: ${if(wrist == "izq") "izquierda" else "derecha"}") { wrist = if(wrist == "izq") "der" else "izq" }
                    Big("Dominante: ${if(dominant == "izq") "izquierda" else "derecha"}") { dominant = if(dominant == "izq") "der" else "izq" }
                    Permissions.background()?.let { permission -> if(!granted(permission)) Big("Salud en segundo plano") { permissions.launch(arrayOf(permission)) } }
                    Big("Ajustes de permisos") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                    Big("Apagar enlace") { stopService(Intent(this@MainActivity, LinkServerService::class.java)) }
                    Text("${root?.listFiles()?.count { it.isDirectory } ?: 0} sesiones locales. Se conservan hasta exportarlas.", fontSize=11.sp, textAlign=TextAlign.Center)
                }
            }
        }
    }
}
