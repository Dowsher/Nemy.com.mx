package org.pipalab.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import org.pipalab.core.TimeWindow
import java.util.UUID

internal val Ink=Color(0xFF080D0C)
internal val Panel=Color(0xFF111A17)
internal val Edge=Color(0xFF263A30)
internal val Lime=Color(0xFFB2FF69)
internal val Mint=Color(0xFF5FE4BA)
internal val Muted=Color(0xFF8FA198)
internal val Amber=Color(0xFFFFCE7A)
internal val Mono=FontFamily.Monospace
internal fun time(t:Long,pattern:String="HH:mm:ss")=if(t<=0) "—" else SimpleDateFormat(pattern,Locale("es","MX")).format(Date(t))
internal fun num(v:Double?,decimals:Int=0)=if(v==null || !v.isFinite()) "—" else String.format(Locale.US,"%."+decimals+"f",v)
internal fun title(kind:String)=mapOf("PIPA" to "Pipa","PIPA_SIM" to "Pipa simulada","CIGARRO" to "Cigarro","VAPE" to "Vape",
    "OTRO" to "Otro consumo","POR_CLASIFICAR" to "Movimiento por revisar").getOrDefault(kind,kind)
private fun stateLabel(s:String)=mapOf("confirmed" to "Confirmado","false_positive" to "Falso positivo","pending" to "Por revisar").getOrDefault(s,s)
internal data class Metric(val label:String,val stream:String,val keys:List<String>,val unit:String)
internal val metrics=listOf(
    Metric("Pulso","heart_rate",listOf("bpm"),"lpm"),Metric("Movimiento","acelerometro",listOf("x_m_s2","y_m_s2","z_m_s2"),"m/s²"),
    Metric("Giro","giroscopio",listOf("x_rad_s","y_rad_s","z_rad_s"),"rad/s"),Metric("PPG","ppg",listOf("green"),"verde · cuentas"),
    Metric("IBI","heart_rate",listOf("ibi_ms_json"),"ms"),Metric("Piel","temperatura",listOf("skin_c","ambient_c"),"°C"),
    Metric("EDA","eda",listOf("conductance_us"),"µS"),Metric("PPG IR","ppg",listOf("ir"),"infrarrojo · cuentas"),
    Metric("PPG rojo","ppg",listOf("red"),"rojo · cuentas"),Metric("Uso","offbody",listOf("worn"),"0 fuera / 1 puesto"),
    Metric("Batería","bateria",listOf("percent"),"%"))
internal fun value(s:Sample,key:String):Double? {
    if(s.source=="minute")return s.values[key]?.toDoubleOrNull()
    if(key in listOf("green","ir","red") && s.values[key+"_status"]!="0")return null
    if(key in listOf("skin_c","ambient_c","conductance_us") && s.values["status"]!="0")return null
    if(key=="bpm" && s.values["status"]?.toIntOrNull()!=1) return null
    if(key=="ibi_ms_json") return runCatching {
        val a=JSONArray(s.values[key]); val quality=JSONArray(s.values["ibi_status_json"])
        (a.length()-1 downTo 0).firstOrNull{it<quality.length() && quality.getInt(it)==0 && a.getDouble(it)>0}?.let{a.getDouble(it)}
    }.getOrNull()
    return s.values[key]?.toDoubleOrNull()
}
class MainActivity:ComponentActivity() {
    private lateinit var store:Store
    private lateinit var dictation:TrainingDictation
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var notice by mutableStateOf("")
    private var pendingSpeech=""
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if(linkPermissions().all(::granted)) startLink() else notice="Permite Dispositivos cercanos para enlazar."
    }
    private val microphone=registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if(ok && pendingSpeech.isNotBlank())dictation.start(pendingSpeech) else notice="Puedes escribir la descripción sin micrófono."
        pendingSpeech=""
    }
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null)scope.launch {
            notice="Preparando base de entrenamiento…"
            notice=withContext(Dispatchers.IO) {runCatching{contentResolver.openOutputStream(uri)!!.use{Exporter.writeTraining(store,it,LearningEngine.get(this@MainActivity).export())};"Base de entrenamiento exportada"}.getOrElse{"No se pudo exportar: "+it.message}}
        }
    }
    private val legacyExport=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null)scope.launch {notice=withContext(Dispatchers.IO){runCatching{contentResolver.openOutputStream(uri)!!.use{Exporter.write(store,it)};"Histórico exportado"}.getOrElse{"No se pudo exportar: "+it.message}}}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge();store=Store.get(this)
        getSharedPreferences("prefs",MODE_PRIVATE).edit().putBoolean("detection",false).putBoolean("esp_automatic",false).putBoolean("audio_demo",false).apply()
        getSystemService(NotificationManager::class.java).activeNotifications.filter{it.notification.channelId=="candidates"}.forEach{getSystemService(NotificationManager::class.java).cancel(it.id)}
        dictation=TrainingDictation(this,TrainingStore(store))
        LearningEngine.get(this).refresh()
        setContent {MaterialTheme(colorScheme=darkColorScheme(primary=Lime,onPrimary=Ink,primaryContainer=Edge,onPrimaryContainer=Lime,secondary=Mint,secondaryContainer=Color(0xFF243B2B),onSecondaryContainer=Lime,surfaceVariant=Panel,onSurfaceVariant=Muted,background=Ink,surface=Panel,onSurface=Color(0xFFE5EFE8),onBackground=Color(0xFFE5EFE8),outline=Edge)) {
            TrainingApp(store,dictation,notice,{notice=it},::connect,::espAction,
                {id->if(granted(Manifest.permission.RECORD_AUDIO))dictation.start(id) else {pendingSpeech=id;microphone.launch(Manifest.permission.RECORD_AUDIO)}},
                {export.launch("Visio-Entrenamiento-"+time(System.currentTimeMillis(),"yyyyMMdd-HHmm")+".zip")},
                {legacyExport.launch("Visio-Historico-"+time(System.currentTimeMillis(),"yyyyMMdd-HHmm")+".zip")})
        }}
        scope.launch {withContext(Dispatchers.IO){runCatching{store.importLocal()}}}
    }
    override fun onStop() {if(::dictation.isInitialized)dictation.stop();super.onStop()}
    override fun onDestroy() {if(::dictation.isInitialized)dictation.destroy();scope.cancel();super.onDestroy()}
    private fun granted(p:String)=checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    private fun linkPermissions()=listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)+if(android.os.Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    private fun startLink() {startForegroundService(Intent(this,LinkService::class.java))}
    private fun connect() {if(linkPermissions().all(::granted))startLink() else permissions.launch(linkPermissions().toTypedArray())}
    private fun espAction(action:String,address:String) {
        if(linkPermissions().all(::granted))startForegroundService(Intent(this,EspLedService::class.java).setAction(action).putExtra("address",address))
        else {permissions.launch(linkPermissions().toTypedArray());notice="Después del permiso, vuelve a buscar la placa."}
    }
}

@Composable internal fun Eyebrow(text:String) { Text(text,fontSize=10.sp,lineHeight=14.sp,fontFamily=Mono,color=Muted,letterSpacing=1.5.sp) }
@Composable internal fun SurfaceCard(onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).border(1.dp,Edge,RoundedCornerShape(22.dp))
        .then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
