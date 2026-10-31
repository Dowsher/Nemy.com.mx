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
    override fun onResume() { super.onResume(); permissionEpoch++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun command(action: String, event: String? = null) {
        startService(Intent(this, RecorderService::class.java).setAction(action).putExtra("event", event).putExtra("time", SystemClock.elapsedRealtimeNanos()))
    }
    @Composable private fun Big(label: String, enabled: Boolean = true, action: () -> Unit) {
        Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label, textAlign = TextAlign.Center) }
    }
    @Composable private fun App() {
        val live by Live.state.collectAsState()
        val prefs = remember { getSharedPreferences("setup", MODE_PRIVATE) }
        var participant by rememberSaveable { mutableStateOf(prefs.getString("participant", "P001") ?: "P001") }
        var wrist by rememberSaveable { mutableStateOf(prefs.getString("wrist", "izq") ?: "izq") }
        var dominant by rememberSaveable { mutableStateOf(prefs.getString("dominant", "der") ?: "der") }
        var power by rememberSaveable { mutableStateOf("auto") }
        var consent by rememberSaveable { mutableStateOf(false) }
        var view by rememberSaveable { mutableStateOf("home") }
        var selected by remember { mutableStateOf<File?>(null) }
        var deleteMessage by remember { mutableStateOf("") }
        var deleting by remember { mutableStateOf(false) }
        var listingEpoch by remember { mutableIntStateOf(0) }
        val missing = remember(permissionEpoch) { Permissions.foreground().filterNot(::granted) }
        val background = Permissions.background()
        val backgroundGranted = remember(permissionEpoch) { background == null || granted(background) }
        val root = remember { getExternalFilesDir(null)?.let { File(it, "sesiones") } }
        val files = remember(listingEpoch, live.active, view) { root?.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList() }
        Column(Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Visio", color = Color(0xFF99E8CD), fontSize = 23.sp)
            Text("Ecosistema Nemy · local", fontSize = 12.sp, textAlign = TextAlign.Center)
            Big("Enlazar teléfono") {
                val required = listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE")
                if (required.any { !granted(it) }) permissions.launch(required.toTypedArray())
                else startForegroundService(Intent(this@MainActivity, LinkServerService::class.java))
            }
            Big("Apagar enlace") { stopService(Intent(this@MainActivity, LinkServerService::class.java)) }
            if (view == "sessions" && !live.active) {
                Text("Sesiones: ${files.size}")
                if (selected != null) {
                    Text("Borrar definitivamente\n${selected!!.name}", fontSize = 12.sp, textAlign = TextAlign.Center)
                    Text("Comprueba antes tu copia descargada.", fontSize = 12.sp, textAlign = TextAlign.Center)
                    Big("Confirmar borrado", !deleting) {
                        val target = selected!!; deleting = true
                        Thread {
                            val ok = runCatching {
                                check(!Live.state.value.active)
                                check(target.canonicalFile.parentFile == root!!.canonicalFile)
                                target.deleteRecursively()
                            }.getOrDefault(false)
                            runOnUiThread { deleteMessage = if (ok) "Sesión borrada" else "No se pudo borrar"; selected = null; deleting = false; listingEpoch++ }
                        }.start()
                    }
                    Big("Cancelar", !deleting) { selected = null }
                } else {
                    files.forEach { file -> Big(file.name, !deleting) { selected = file } }
                    Text(deleteMessage, fontSize = 12.sp)
                    Big("Volver") { view = "home" }
                }
            } else if (live.active) {
                val h = live.seconds / 3600; val m = live.seconds / 60 % 60; val s = live.seconds % 60
                Text("%02d:%02d:%02d".format(h, m, s), fontSize = 26.sp)
                Text("Batería ${live.battery}%", fontSize = 15.sp)
                Text("Movimiento ${String.format(Locale.US, "%.1f", live.hz["acelerometro"] ?: 0.0)} /s", textAlign = TextAlign.Center)
                Text("Hz medidos con timestamps: media de sesión.", fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(live.health, fontSize = 12.sp, textAlign = TextAlign.Center)
                Text("Pulso: ${live.counts["heart_rate"] ?: 0} muestras\nTemperatura: ${live.counts["temperatura"] ?: 0}", fontSize = 12.sp, textAlign = TextAlign.Center)
                Big(if (live.stopping) "Guardando…" else "DETENER", !live.stopping) { command("STOP") }
                Text(live.message, fontSize = 12.sp)
                Big("SYNC · 3 aplausos", !live.stopping) { command("SYNC") }
                Text("Toca SYNC y da 3 aplausos fuertes. Sin grabación de audio.", fontSize = 11.sp, textAlign = TextAlign.Center)
                Text(if (live.event == null) "Sin evento activo" else "Activo: ${live.event}", fontSize = 14.sp, textAlign = TextAlign.Center)
                EVENT_NAMES.forEach { label -> Big(if (live.event == label) "FIN · $label" else "INICIO · $label", !live.stopping) { command("MARK", label) } }
            } else {
                Text(live.message, fontSize = 12.sp, textAlign = TextAlign.Center)
                if (!BuildConfig.SAMSUNG_ENABLED) Text("COMPILACIÓN SOLO IMU\nSin pulso, IBI o temperatura", color = Color(0xFFFFCD85), fontSize = 13.sp, textAlign = TextAlign.Center)
                Text("Código anónimo", fontSize = 14.sp)
                BasicTextField(participant, { participant = it.filter { c -> c.isLetterOrDigit() && c.code < 128 || c == '-' || c == '_' }.take(24) },
                    singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(Color.White), modifier = Modifier.fillMaxWidth().background(Color(0xFF242A2A), RoundedCornerShape(16.dp)).padding(14.dp))
                Big("Muñeca: ${if (wrist == "izq") "izquierda" else "derecha"}") { wrist = if (wrist == "izq") "der" else "izq" }
                Big("Dominante: ${if (dominant == "izq") "izquierda" else "derecha"}") { dominant = if (dominant == "izq") "der" else "izq" }
                Big("Energía: ${mapOf("auto" to "automática", "partial" to "bloqueo parcial", "none" to "sin bloqueo")[power]}") {
                    power = when (power) { "auto" -> "partial"; "partial" -> "none"; else -> "auto" }
                }
                if (power == "none") Text("Prueba de batería: puede perder muestras al apagar pantalla.", fontSize = 12.sp, color = Color(0xFFFFCD85), textAlign = TextAlign.Center)
                Text("Consiento este registro local para investigación con gestos simulados. No escribir nombre ni datos identificativos.", fontSize = 12.sp, textAlign = TextAlign.Center)
                Big(if (consent) "☑ Consentimiento" else "☐ Consentimiento") { consent = !consent }
                Text("Permisos: movimiento, notificación y sensores de salud. No se solicita Internet, ubicación ni micrófono.", fontSize = 12.sp, textAlign = TextAlign.Center)
                if (missing.isNotEmpty()) {
                    Big("Conceder permisos") { permissions.launch(missing.toTypedArray()) }
                    val names = mapOf(Manifest.permission.ACTIVITY_RECOGNITION to "Actividad física", Manifest.permission.POST_NOTIFICATIONS to "Notificaciones",
                        Permissions.HR to "Frecuencia cardíaca", Permissions.SKIN to "Temperatura cutánea", Permissions.EXTRA to "Sensores adicionales de Samsung",
                        Manifest.permission.BODY_SENSORS to "Sensores corporales")
                    Text("Pendientes:\n" + missing.joinToString("\n") { names[it] ?: "Sensores de salud" }, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
                if (missing.isEmpty() && !backgroundGranted && background != null) {
                    Text("Acceso de salud en segundo plano: se pide por separado. La sesión se inicia desde esta pantalla con servicio visible.", fontSize = 12.sp, textAlign = TextAlign.Center)
                    Big("Salud en segundo plano") { permissions.launch(arrayOf(background)) }
                }
                Big("Abrir ajustes de permisos") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                Big("INICIAR SESIÓN", consent && participant.matches(Regex("[A-Za-z0-9_-]{1,24}")) && missing.isEmpty()) {
                    prefs.edit().putString("participant", participant).putString("wrist", wrist).putString("dominant", dominant).apply()
                    startForegroundService(Intent(this@MainActivity, RecorderService::class.java).setAction("START")
                        .putExtra("participant", participant).putExtra("wrist", wrist).putExtra("dominant", dominant).putExtra("power", power).putExtra("consent", consent))
                }
                Big("Ver / borrar sesiones") { view = "sessions"; listingEpoch++ }
            }
        }
    }
}
