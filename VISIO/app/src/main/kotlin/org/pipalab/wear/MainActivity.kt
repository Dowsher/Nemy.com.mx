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
import androidx.compose.ui.graphics.graphicsLayer
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
    override fun onResume() { super.onResume(); permissionEpoch++; TrainingInbox.load(this); DetectionReview.load(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TrainingInbox.load(this); DetectionReview.load(this)
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
        val question by DetectionReview.pending.collectAsState()
        val ready by RemoteReady.state.collectAsState()
        val prefs=remember {getSharedPreferences("setup",MODE_PRIVATE)}
        var participant by rememberSaveable {mutableStateOf(prefs.getString("participant","P001") ?: "P001")}
        var setup by rememberSaveable {mutableStateOf(false)}
        var screenTurns by rememberSaveable {mutableIntStateOf(prefs.getInt("display_quarter_turns",display?.rotation ?: 0))}
        val required=Permissions.foreground()+listOf(Manifest.permission.RECORD_AUDIO,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE)
        val missing=remember(permissionEpoch){required.filterNot(::granted)}
        val green=Color(0xFFB2FF69)
        Column(Modifier.fillMaxSize().graphicsLayer { rotationZ=screenTurns*90f }.background(Color.Black).verticalScroll(rememberScrollState()).padding(horizontal=26.dp,vertical=40.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Visio",color=green,fontSize=28.sp)
            Text("CAPTURADOR / NEMY",fontSize=11.sp)
            question?.let {
                Text("¿Fue un consumo?",color=green,fontSize=21.sp,textAlign=TextAlign.Center)
                Text("Pregunta de entrenamiento. Patrón experimental, no confirmación automática.",fontSize=12.sp,textAlign=TextAlign.Center)
                Big("SÍ, CONFIRMAR") {DetectionReview.answer(this@MainActivity,"confirmed")}
                Big("NO, DESCARTAR") {DetectionReview.answer(this@MainActivity,"false_positive")}
                Big("NO ESTOY SEGURO") {DetectionReview.answer(this@MainActivity,"unsure")}
            }
            Text(if(live.active) "● GRABANDO" else if(ready) "LISTO PARA EL TELÉFONO" else "CAPTURA APAGADA",color=green,fontSize=16.sp,textAlign=TextAlign.Center)
            if(live.active) {
                Text(live.activity,fontSize=18.sp,textAlign=TextAlign.Center)
                Text("%02d:%02d".format(live.seconds/60,live.seconds%60),fontSize=28.sp)
                Text("Audio: ${live.audio.state}\nPulso: ${live.counts["heart_rate"] ?: 0} · Piel: ${live.counts["temperatura"] ?: 0}\nAcel.: ${live.counts["acelerometro"] ?: 0} · Giro: ${live.counts["giroscopio"] ?: 0}",fontSize=12.sp,textAlign=TextAlign.Center)
                if(live.audio.silenced)Text("MICRÓFONO SILENCIADO",color=Color(0xFFFFCE7A),fontSize=12.sp)
                Big(if(live.stopping)"Guardando…" else "DETENER",!live.stopping) {startService(Intent(this@MainActivity,RecorderService::class.java).setAction("STOP"))}
            } else {
                Text(if(ready)"Crea, inicia y revisa las sesiones desde el teléfono. Ahora no se graban audio ni sensores."
                    else "Activa el capturador una vez. El teléfono podrá iniciar audio, pulso, temperatura y movimiento durante cada sesión.",fontSize=12.sp,textAlign=TextAlign.Center)
                if(missing.isNotEmpty()) Big("Permitir captura") {permissions.launch(missing.toTypedArray())}
                Big(if(ready)"APAGAR CAPTURADOR" else "LISTO PARA EL TELÉFONO",ready || missing.isEmpty()) {
                    if(ready)startService(Intent(this@MainActivity,RecorderService::class.java).setAction("DISARM"))
                    else {
                        prefs.edit().putString("participant",participant.ifBlank {"P001"}).apply()
                        startForegroundService(Intent(this@MainActivity,RecorderService::class.java).setAction("ARM"))
                        link()
                    }
                }
                Big("Reactivar enlace",missing.isEmpty(),::link)
            }
            Text(live.message,fontSize=11.sp,textAlign=TextAlign.Center)
            Text("Audio y CSV se conservan aquí hasta copiarse al teléfono. Si Android reinicia la app, vuelve a activar el capturador.",fontSize=11.sp,textAlign=TextAlign.Center)
            Big(if(setup)"Cerrar ajustes" else "Ajustes") {setup=!setup}
            if(setup) {
                Big("Girar pantalla 90°") {
                    screenTurns=(screenTurns+1)%4;prefs.edit().putInt("display_quarter_turns",screenTurns).apply()
                }
                Big("Orientación del reloj") {startActivity(Intent(Settings.ACTION_SETTINGS))}
                Text("General → Orientación: muñeca izquierda y botones hacia la mano. Los ejes crudos se conservan.",fontSize=11.sp,textAlign=TextAlign.Center)
                Text("Código anónimo",fontSize=12.sp)
                BasicTextField(participant,{participant=it.filter {c->c.isLetterOrDigit() && c.code<128 || c=='_' || c=='-'}.take(24)},singleLine=true,
                    textStyle=TextStyle(color=Color.White,fontSize=18.sp),modifier=Modifier.fillMaxWidth().padding(12.dp))
                Big("Guardar código") {prefs.edit().putString("participant",participant.ifBlank {"P001"}).apply()}
                Permissions.background()?.let {permission->if(!granted(permission))Big("Salud en segundo plano") {permissions.launch(arrayOf(permission))}}
                Big("Permisos del sistema") {startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}
            }
        }
    }
}
