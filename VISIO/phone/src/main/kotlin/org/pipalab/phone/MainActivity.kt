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
private data class ScreenData(val sessions:List<SessionInfo> = emptyList(),val events:List<EventInfo> = emptyList(),
    val points:List<Sample> = emptyList(),val latest:Map<String,Sample?> = emptyMap(),val counts:Map<String,Long> = emptyMap(),val now:Long=System.currentTimeMillis(),val window:TimeWindow=TimeWindow(0,1),val first:Long=0,val last:Long=1,val measurements:List<MeasurementInfo> = emptyList())

class MainActivity:ComponentActivity() {
    private lateinit var store:Store
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var notice by mutableStateOf("")
    private var eventIntent by mutableStateOf("")
    private var afterPermission:(()->Unit)?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if(linkPermissions().all(::granted)) { val next=afterPermission;afterPermission=null;if(next!=null)next() else startLink() } else notice="Permite Dispositivos cercanos para enlazar tus dispositivos." }
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri!=null) scope.launch {
            notice="Preparando exportación…"
            notice=withContext(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)!!.use{Exporter.write(store,it)}; "Exportación guardada" }.getOrElse{"No se pudo exportar: "+it.message} }
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state); enableEdgeToEdge(); store=Store.get(this); eventIntent=intent.getStringExtra("event") ?: ""
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Lime,onPrimary=Ink,secondary=Mint,background=Ink,surface=Panel,
            onSurface=Color(0xFFE5EFE8),onBackground=Color(0xFFE5EFE8),outline=Edge)) { App() } }
        scope.launch { withContext(Dispatchers.IO) { runCatching { store.importLocal() }.onFailure { notice="Importación pendiente: "+it.message } } }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); eventIntent=intent.getStringExtra("event") ?: "" }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun granted(p:String)=checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    private fun linkPermissions()=listOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)+
        if(android.os.Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
    private fun startLink() { startForegroundService(Intent(this,LinkService::class.java)) }
    private fun connect() { if(linkPermissions().all(::granted)) startLink() else permissions.launch(linkPermissions().toTypedArray()) }
    private fun espAction(action:String,address:String="") {
        val start={startForegroundService(Intent(this,EspLedService::class.java).setAction(action).putExtra("address",address));Unit}
        if(linkPermissions().all(::granted)) start() else { afterPermission=start;permissions.launch(linkPermissions().toTypedArray()) }
    }

    @Composable private fun App() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var selected by rememberSaveable { mutableStateOf("") }
        var metricIndex by rememberSaveable { mutableIntStateOf(0) }
        var range by rememberSaveable { mutableLongStateOf(120000) }
        var position by rememberSaveable { mutableFloatStateOf(1f) }
        var viewport by remember { mutableStateOf<TimeWindow?>(null) }
        var point by remember { mutableStateOf<Sample?>(null) }
        var about by remember { mutableStateOf<Metric?>(null) }
        var rawQuery by remember { mutableStateOf<RawQuery?>(null) }
        var measureDetail by remember { mutableStateOf<MeasurementInfo?>(null) }
        var data by remember { mutableStateOf(ScreenData()) }
        var register by remember { mutableStateOf(false) }
        var review by remember { mutableStateOf<EventInfo?>(null) }
        var revision by remember { mutableIntStateOf(0) }
        var eventFilter by remember { mutableStateOf("Todos") }
        val prefs=remember { getSharedPreferences("prefs",MODE_PRIVATE) }
        var detection by remember { mutableStateOf(prefs.getBoolean("detection",false)) }
        var ledAutomatic by remember { mutableStateOf(prefs.getBoolean("esp_automatic",true)) }
        var ledDuration by remember { mutableIntStateOf(prefs.getInt("esp_duration",5000)) }
        val personal by PersonalLive.state.collectAsState()
        val esp by EspLive.state.collectAsState()
        val link by PhoneLive.state.collectAsState()
        val metric=metrics[metricIndex]
        val current=data.sessions.find{it.id==(if(tab==0) link.session else selected)}
        val activeId=if(tab==0) link.session else selected
        val fresh=link.connected && link.recording && System.currentTimeMillis()-link.lastData<10000
        LaunchedEffect(tab) { if(tab==0 && metrics[metricIndex].stream !in setOf("heart_rate","temperatura","acelerometro"))metricIndex=0 }
        LaunchedEffect(eventIntent) { if(eventIntent.isNotBlank()) { tab=2; review=withContext(Dispatchers.IO){store.events().find{it.id==eventIntent}}; eventIntent="" } }
        LaunchedEffect(tab,selected,metricIndex,range,viewport,revision,link.session) {
            while(isActive) {
                val result=withContext(Dispatchers.IO) {
                    val sessions=store.sessions(); val id=if(tab==0) PhoneLive.state.value.session else selected
                    val s=sessions.find{it.id==id}; val now=System.currentTimeMillis()
                    val end=if((s?.end ?: 0)>0)s!!.end else if(tab==0)now-PhoneLive.state.value.clockDelta else store.latestLive(id,"acelerometro")?.wall ?: now
                    val first=s?.start ?: end-120000
                    val win=(viewport ?: TimeWindow(if(range==0L)first else maxOf(first,end-range),end)).constrain(first,end)
                    val latest=listOf("heart_rate","temperatura","acelerometro","ppg","bateria","eda","offbody").associateWith{store.latestLive(id,it)}
                    val counts=linkedMapOf<String,Long>()
                    store.readableDatabase.rawQuery("SELECT stream,n FROM stream_counts WHERE session=?",arrayOf(id)).use{c->while(c.moveToNext())counts[c.getString(0)]=c.getLong(1)}
                    ScreenData(sessions,store.events(),if(id.isBlank())emptyList() else store.plot(id,metric.stream,win.from,win.to),latest,counts,now,win,first,end,store.measurements())
                }
                data=result
                delay(if(tab==0) 1200 else 3000)
            }
        }
        val scroll=rememberLazyListStateCompat()
        LaunchedEffect(tab,selected) { viewport=null;scroll.scrollToItem(0) }
        @Composable fun Explorer() {
            val chartWindow=viewport ?: data.window
            ChartExplorer(metric,metricIndex,{metricIndex=it},data.points.filter{it.wall in chartWindow.from..chartWindow.to},data.events.filter{it.session==activeId || it.session.isBlank()},chartWindow,data.first,data.last,
                tab==0 && viewport==null,{viewport=it},{zoom,pan,anchor->viewport=(viewport ?: data.window).transform(zoom,pan,anchor,data.first,data.last)},
                {viewport=null;range=120000},{point=it},{review=it},{about=metric})
        }
        Scaffold(containerColor=Ink,floatingActionButton={
            if(tab==0) ExtendedFloatingActionButton(onClick={register=true},containerColor=Lime,contentColor=Ink) { Text("+ Registrar consumo",fontWeight=FontWeight.SemiBold) }
        },bottomBar={
            Column(Modifier.background(Ink).navigationBarsPadding()) {
                HorizontalDivider(color=Edge)
                NavigationBar(containerColor=Ink,tonalElevation=0.dp,windowInsets=WindowInsets(0,0,0,0),modifier=Modifier.height(76.dp)) {
                    listOf("En vivo","Historial","Eventos","Ajustes").forEachIndexed{i,label->
                        NavigationBarItem(selected=tab==i,onClick={tab=i},icon={
                            Box {
                                NavIcon(i,if(tab==i)Lime else Muted)
                                if(i==2 && data.events.any{it.state=="pending" && it.origin!="test"}) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Lime,CircleShape))
                            }
                        },label={Text(label,fontSize=11.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Lime,selectedTextColor=Lime,indicatorColor=Color(0xFF1C2B20),unselectedTextColor=Muted))
                    }
                }
            }
        }) { insets->
            Box(Modifier.fillMaxSize().padding(insets)) {
                Canvas(Modifier.fillMaxSize()) { val step=48.dp.toPx(); var x=0f; while(x<size.width){drawLine(Edge.copy(alpha=.18f),Offset(x,0f),Offset(x,size.height));x+=step} }
                LazyColumn(state=scroll,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item { Brand(tab) }
                    if(notice.isNotBlank()) item { Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF233426)).clickable{notice=""}.padding(12.dp)) { Text(notice,color=Lime,fontSize=12.sp) } }
                    when(tab) {
                        0 -> {
                            item {
                                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                                    Box(Modifier.size(7.dp).background(if(fresh)Lime else Muted,CircleShape))
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(if(fresh) "SEÑAL EN VIVO" else if(link.connected) "RELOJ CONECTADO" else "SIN ENLACE",color=if(fresh)Lime else Muted,fontSize=10.sp,letterSpacing=1.8.sp,fontFamily=Mono)
                                        Text(link.text,fontSize=11.sp,color=Muted)
                                    }
                                    if(!link.connected) OutlinedButton(onClick=::connect,contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp)) { Text("Conectar",fontSize=12.sp) }
                                    else if(link.battery>=0) Text(link.battery.toString()+"% RELOJ",fontFamily=Mono,fontSize=10.sp,color=Muted)
                                }
                            }
                            item { StateMeter(link,detection) }
                            item { PulseHero(data.latest["heart_rate"],fresh,link.recording,data.now,link.clockDelta) }
                            item {
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    SmallMetric("PIEL",num(data.latest["temperatura"]?.let{value(it,"skin_c")},1),"°C",if(data.latest["temperatura"]==null)"Sin lectura" else "Última "+time(data.latest["temperatura"]!!.wall,"HH:mm"),Modifier.weight(1f))
                                    val ibi=data.latest["heart_rate"]?.let{value(it,"ibi_ms_json")}
                                    SmallMetric("INTERVALO",num(ibi),"ms",if(ibi==null)"Sin lectura IBI" else "Último intervalo",Modifier.weight(1f))
                                }
                            }
                            item {
                                val pending=data.measurements.firstOrNull{it.status=="requested" || (it.status=="open" && it.end>data.now-link.clockDelta)}
                                Button(onClick={val id=UUID.randomUUID().toString();startService(Intent(this@MainActivity,LinkService::class.java).setAction("MEASURE").putExtra("id",id));revision++},
                                    enabled=link.connected && link.recording && link.protocol>=2 && pending==null,modifier=Modifier.fillMaxWidth()) {Text("Medir ahora · 30 segundos")}
                                if(!link.recording)Text("Inicia una sesión en el reloj para medir ahora.",fontSize=11.sp,color=Muted)
                                else if(link.protocol<2)Text("Actualiza la app del reloj para capturar 30 segundos.",fontSize=11.sp,color=Amber)
                            }
                            data.measurements.firstOrNull()?.let{m->item{MeasurementCard(m,data.now-link.clockDelta){measureDetail=m}}}
                            item { Explorer() }
                            if(activeId.isNotBlank())item { MinuteBrowser(store,activeId){rawQuery=it} }
                            item {
                                if(!link.recording) Info("Empieza una sesión en el reloj","Abre Visio, activa el enlace y toca INICIAR SESIÓN. Aquí aparecerán las señales recibidas.")
                                else Info(if(fresh)"Registro en curso" else "Esperando muestras",if(fresh)(if(link.compact)"Modo compacto: resúmenes por minuto y segmentos. " else "Registro completo anterior. ")+link.sync else "El reloj conserva el registro local si el enlace se interrumpe.")
                            }
                            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Micro("DETECCIÓN",if(detection) if(link.calibrated)"Experimental activa" else "Esperando calibración" else "Desactivada")
                                Micro("ALMACENAMIENTO","Solo en tus dispositivos")
                            } }
                        }
                        1 -> {
                            item { Heading("Tu archivo de señales","Resúmenes, segmentos y mediciones conservadas.") }
                            item { SurfaceCard {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                    Column { Eyebrow("BÓVEDA LOCAL"); Text(data.sessions.size.toString().padStart(2,'0'),fontSize=38.sp,fontFamily=Mono,color=Lime); Text("sesiones guardadas",fontSize=12.sp,color=Muted) }
                                    Column(horizontalAlignment=Alignment.End) { Eyebrow("MUESTRAS"); Text(String.format(Locale.US,"%,d",data.sessions.sumOf{it.count}),fontSize=24.sp,fontFamily=Mono); Text("registros conservados",fontSize=11.sp,color=Muted) }
                                }
                            } }
                            if(selected.isNotBlank() && current!=null) {
                                item { Row(verticalAlignment=Alignment.CenterVertically) { TextButton(onClick={selected=""}){Text("← Todas")}; Text(time(current.start,"dd MMM · HH:mm"),fontSize=15.sp,fontWeight=FontWeight.Bold) } }
                                item { Explorer() }
                                item { MinuteBrowser(store,activeId){rawQuery=it} }
                                item {
                                    SurfaceCard {
                                        TextButton(onClick={rawQuery=RawQuery(activeId,metric.stream,data.window.from,data.window.to)}) { Text("Inspeccionar datos y sensores →",fontSize=12.sp) }
                                    }
                                }
                                item { Info(if(current.complete)"Copia completa verificada" else "Copia en progreso","Inicio "+time(current.start,"dd/MM/yyyy HH:mm:ss")+" · "+(if(current.end>0)"Fin "+time(current.end) else "Sin cierre recibido")+"\n"+current.id) }
                            }
                            if(data.measurements.isNotEmpty())item { Heading("Mediciones manuales","Capturas de 30 segundos, sin etiquetarlas como consumos.") }
                            items(data.measurements,key={"measurement_"+it.id}){m->MeasurementCard(m,data.now-link.clockDelta){measureDetail=m}}
                            items(data.sessions,key={it.id}){s->
                                SurfaceCard(onClick={selected=s.id;position=1f;range=0;viewport=null}) {
                                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF233426)),contentAlignment=Alignment.Center){NavIcon(1,Lime)}
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(time(s.start,"dd MMM yyyy · HH:mm"),fontSize=15.sp,fontWeight=FontWeight.SemiBold)
                                            Text(String.format(Locale.US,"%,d",s.count)+" muestras · "+if(s.end>0)num((s.end-s.start)/60000.0,1)+" min" else "en curso",fontSize=12.sp,color=Muted)
                                        }
                                        Text(if(s.complete)"✓" else "↻",color=if(s.complete)Lime else Amber,fontSize=20.sp)
                                    }
                                }
                            }
                            if(data.sessions.isEmpty()) item { Info("Tu historial empieza aquí","Conecta el reloj para recibir tus sesiones y explorar sus señales.") }
                        }
                        2 -> {
                            item { Heading("Eventos & aprendizaje","Tus correcciones dejan una huella útil.") }
                            item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Counter(data.events.count{it.state=="confirmed" && it.origin!="test"},"Confirmados",Lime,Modifier.weight(1f))
                                Counter(data.events.count{it.state=="pending" && it.origin!="test"},"Por revisar",Amber,Modifier.weight(1f))
                                Counter(data.events.count{it.state=="false_positive" && it.origin!="test"},"Descartados",Muted,Modifier.weight(1f))
                            } }
                            item { Button(onClick={register=true},modifier=Modifier.fillMaxWidth().height(52.dp)){Text("+ Registrar consumo")} }
                            item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("Todos","Por revisar","Confirmados","Descartados","Pruebas").forEach{v->FilterChip(selected=eventFilter==v,onClick={eventFilter=v},label={Text(v,fontSize=11.sp)})}
                            } }
                            val shown=data.events.filter{if(eventFilter=="Pruebas")it.origin=="test" else it.origin!="test" && (eventFilter=="Todos" || it.state==mapOf("Por revisar" to "pending","Confirmados" to "confirmed","Descartados" to "false_positive")[eventFilter])}
                            items(shown,key={it.id}){e->
                                SurfaceCard(onClick={review=e}) {
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                        Text(if(e.origin=="test")"PRUEBA · NO ENTRENAR" else if(e.origin=="manual")"REGISTRO MANUAL" else "ALERTA EXPERIMENTAL",fontSize=9.sp,fontFamily=Mono,color=Muted,letterSpacing=1.sp)
                                        Text(time(e.time,"dd MMM · HH:mm"),fontSize=10.sp,color=Muted)
                                    }
                                    Text(title(e.kind),fontSize=20.sp,fontWeight=FontWeight.Medium)
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                        Text(stateLabel(e.state),color=when(e.state){"confirmed"->Lime;"pending"->Amber;else->Muted},fontSize=12.sp)
                                        Text("Revisar →",color=Lime,fontSize=12.sp)
                                    }
                                    if(e.note.isNotBlank()) Text(e.note,color=Muted,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            if(shown.isEmpty()) item { Info("Todo al día","Los registros manuales y las alertas del detector aparecerán aquí para confirmar o corregir.") }
                            item { Info("La corrección se conserva","Descartar marca un falso positivo y mantiene la alerta original, sus características y el vínculo a la señal. Puedes cambiar de opinión después.") }
                        }
                        else -> {
                            item { Heading("Tu laboratorio","Control del enlace, los datos y las alertas.") }
                            item { SurfaceCard {
                                Eyebrow("CONECTIVIDAD PRIVADA")
                                Text("Reloj ↔ Teléfono",fontSize=22.sp,fontWeight=FontWeight.Medium)
                                Text(link.text,color=Muted,fontSize=13.sp)
                                Text("Bluetooth directo con cifrado. Sin Internet, cuentas ni subida a la nube.",fontSize=13.sp,color=Muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick=::connect){Text("Conectar")}
                                    OutlinedButton(onClick={stopService(Intent(this@MainActivity,LinkService::class.java))}){Text("Desconectar")}
                                }
                                if(link.sync.isNotBlank()) Text(link.sync,fontSize=11.sp,color=Lime)
                            } }
                            item { SurfaceCard {
                                Eyebrow("SALIDA FÍSICA / BLUETOOTH")
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                    Text("ESP32-S3 · LED",fontSize=22.sp,fontWeight=FontWeight.Medium)
                                    Box(Modifier.size(10.dp).background(if(esp.ready)Lime else Muted,CircleShape))
                                }
                                Text(esp.text,color=if(esp.ready)Lime else Muted,fontSize=13.sp)
                                Text("Cada alerta nueva puede encender el LED. La placa recibe solo la orden y su duración; las señales se quedan aquí.",fontSize=13.sp,color=Muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Button(onClick={espAction("SCAN")}){Text("Buscar placa",fontSize=12.sp)}
                                    OutlinedButton(onClick={stopService(Intent(this@MainActivity,EspLedService::class.java))}){Text("Desconectar",fontSize=12.sp)}
                                }
                                if(prefs.getString("esp_address","").orEmpty().isNotBlank()) TextButton(onClick={espAction("CONNECT",prefs.getString("esp_address","").orEmpty())}){Text("Reconectar placa guardada")}
                                esp.devices.forEach{d->OutlinedButton(onClick={espAction("CONNECT",d.address)},modifier=Modifier.fillMaxWidth()){Text(d.name+" · "+d.address.takeLast(5)+" · "+d.rssi+" dBm",fontSize=11.sp)}}
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text("Activar en nuevas detecciones",modifier=Modifier.weight(1f),fontSize=13.sp)
                                    Switch(checked=ledAutomatic,onCheckedChange={ledAutomatic=it;prefs.edit().putBoolean("esp_automatic",it).apply()})
                                }
                                Text("Tiempo encendido",fontSize=12.sp,color=Muted)
                                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) { listOf(1000,3000,5000,10000).forEach{v->FilterChip(selected=ledDuration==v,onClick={ledDuration=v;prefs.edit().putInt("esp_duration",v).apply()},label={Text((v/1000).toString()+" s",fontSize=11.sp)})} }
                                OutlinedButton(enabled=esp.ready,onClick={scope.launch{withContext(Dispatchers.IO){EspTrigger.pulse(this@MainActivity,"","test")}}},modifier=Modifier.fillMaxWidth()){Text("Probar LED")}
                                if(esp.last.isNotBlank()) Text(esp.last,fontSize=12.sp,color=Mint)
                                Text("Necesita firmware VisioLED y vinculación de Android. Si pierde conexión, la alerta se conserva y el pulso no se envía después. Revisar un evento antiguo no vuelve a encenderlo.",fontSize=11.sp,color=Muted)
                            } }
                            item { SurfaceCard {
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)){Eyebrow("DETECTOR EXPERIMENTAL"); Text("Alertas de movimiento",fontSize=19.sp)}
                                    Switch(checked=detection,onCheckedChange={detection=it;prefs.edit().putBoolean("detection",it).apply()})
                                }
                                Text("Busca gestos repetidos de elevar, sostener y bajar la muñeca. No identifica sustancias ni confirma un consumo.",color=Muted,fontSize=13.sp)
                                Text("Al activarlo, mantén el brazo quieto en una postura natural durante 5 segundos. Revisa las alertas para etiquetar aciertos y errores.",color=Muted,fontSize=13.sp)
                                Text("Perfil local: ${personal.positives} confirmados y ${personal.negatives} descartes con señal útil. ${personal.excluded} registros sin señal compatible. Calibración provisional; precisión aún no validada.",color=Amber,fontSize=12.sp)
                                OutlinedButton(onClick={
                                    if(android.os.Build.VERSION.SDK_INT>=33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                    else scope.launch {
                                        val id=withContext(Dispatchers.IO){val now=System.currentTimeMillis();store.event(now,now,link.session,"POR_CLASIFICAR","test","Prueba de revisión; excluir del entrenamiento")}
                                        Notices.candidate(this@MainActivity,id,true);notice="Aviso de prueba enviado. Puedes descartarlo desde la notificación.";revision++
                                    }
                                }){Text("Probar una notificación",fontSize=12.sp)}
                            } }
                            item { SurfaceCard {
                                Eyebrow("PORTABILIDAD")
                                Text("Tus datos, contigo",fontSize=22.sp)
                                Text("Exporta señales, metadatos y todas las decisiones de revisión en un ZIP. Tú eliges dónde guardarlo.",fontSize=13.sp,color=Muted)
                                Button(onClick={export.launch("Visio-"+time(System.currentTimeMillis(),"yyyyMMdd-HHmm")+".zip")},modifier=Modifier.fillMaxWidth()){Text("Exportar historial y correcciones")}
                            } }
                            item { Info("Registro compacto","Captura esencial: movimiento a 25 Hz, pulso y temperatura. Pulso y temperatura se conservan cuando llegan; del movimiento se guardan promedios por minuto y segmentos de eventos, mediciones y controles. Los históricos se mantienen.") }
                            item { Info("Transparencia de sensores","PPG, giroscopio y EDA no se capturan en la nueva versión del reloj. Contacto y batería se conservan como estados de calidad. Los IBI proceden del mismo flujo de pulso; la temperatura puede llegar por lotes.") }
                            item { Text("VISIO / NEMY 0.5\nHerramienta del ecosistema Nemy · piloto local",fontFamily=Mono,color=Muted,fontSize=10.sp,lineHeight=18.sp,modifier=Modifier.fillMaxWidth()) }
                        }
                    }
                    item { Spacer(Modifier.height(if(tab==0)76.dp else 12.dp)) }
                }
            }
        }
        if(register || review!=null) {
            val e=review
            EventDialog(e,data.sessions,link.session,onDismiss={register=false;review=null},onSave={kind,note,decision,whenMs,session->
                scope.launch {
                    val savedId=withContext(Dispatchers.IO) {
                        if(e==null) store.event(whenMs,whenMs,session,kind,"manual",note,JSONObject()
                            .put("timestamp_source","phone_wall").put("watch_wall_estimate_ms",whenMs-link.clockDelta)
                            .put("clock_delta_estimate_ms",link.clockDelta).put("link_connected",link.connected)
                            .put("alignment_note","one-way BLE estimate; includes transfer delay; not exact synchronization").toString())
                        else {store.feedback(e.id,decision,kind,note,"app");e.id}
                    }
                    if(link.connected && link.recording && link.protocol>=2 && decision=="confirmed" && e?.origin!="test" && session==link.session) {
                        val from=if(e==null)whenMs-link.clockDelta else eventWall(e)
                        startService(Intent(this@MainActivity,LinkService::class.java).setAction("RETAIN").putExtra("id",savedId).putExtra("kind","consumo_confirmado")
                            .putExtra("from",from).putExtra("to",if(e==null)from else from+(e.end-e.time).coerceAtLeast(0)))
                    }
                    if(e!=null) getSystemService(NotificationManager::class.java).cancel(e.id.hashCode())
                    register=false;review=null;revision++;notice=if(decision=="false_positive")"Falso positivo registrado; la señal se conserva." else "Registro guardado en tu historial."
                }
            },onSignal={id->register=false;review=null;selected=id;tab=1;range=0})
        }
        rawQuery?.let{q->RawMeasurementsDialog(store,q){rawQuery=null}}
        about?.let{m->AlertDialog(onDismissRequest={about=null},title={Text(m.label+" · "+m.unit)},text={Text(sensorDescription(m),fontSize=14.sp,lineHeight=21.sp)},confirmButton={TextButton(onClick={about=null}){Text("Entendido")}})}
        point?.let{sample->AlertDialog(onDismissRequest={point=null},title={Text(if(sample.source=="minute")"Promedio de minuto" else "Medición individual")},text={
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(time(sample.wall,"dd MMM HH:mm:ss.SSS"),fontSize=13.sp)
                Text(when(sample.source){"minute"->"Resumen; no es una muestra instantánea.";"live"->"Señal en memoria. El modo compacto solo conserva el detalle en segmentos seleccionados.";else->"Medición conservada en el historial."},fontSize=11.sp,color=Muted)
                Text(sample.values.entries.joinToString("\n"){it.key+": "+it.value},fontFamily=Mono,fontSize=11.sp,lineHeight=16.sp)
                TextButton(onClick={point=null;rawQuery=RawQuery(activeId,metric.stream,sample.wall/60000*60000,sample.wall/60000*60000+59999)}){Text("Abrir todas las mediciones de este minuto")}
            }
        },confirmButton={TextButton(onClick={point=null}){Text("Cerrar")}})}
        measureDetail?.let{m->MeasurementDetails(store,data.measurements.find{it.id==m.id} ?: m,{measureDetail=null}){q->measureDetail=null;rawQuery=q}}

    }
}
@Composable private fun rememberLazyListStateCompat()=androidx.compose.foundation.lazy.rememberLazyListState()
@Composable private fun Brand(tab:Int) {
    Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).border(1.dp,Lime,RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){Text("V",color=Lime,fontSize=23.sp,fontWeight=FontWeight.Black)}
        Spacer(Modifier.width(10.dp))
        Text("Visio",fontSize=24.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.sp)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment=Alignment.End) {
            Text("NEMY",fontFamily=Mono,fontSize=11.sp,color=Lime,letterSpacing=2.sp,lineHeight=15.sp)
            Text("ECOSISTEMA",fontFamily=Mono,fontSize=8.sp,color=Muted,letterSpacing=1.sp,lineHeight=12.sp)
        }
    }
}
@Composable private fun Heading(title:String,subtitle:String) { Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontSize=29.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.7).sp);Text(subtitle,fontSize=13.sp,color=Muted)} }
@Composable internal fun Eyebrow(text:String) { Text(text,fontSize=10.sp,lineHeight=14.sp,fontFamily=Mono,color=Muted,letterSpacing=1.5.sp) }
@Composable internal fun SurfaceCard(onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).border(1.dp,Edge,RoundedCornerShape(22.dp))
        .then(if(onClick!=null)Modifier.clickable(onClick=onClick) else Modifier).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
}
@Composable private fun PulseHero(sample:Sample?,fresh:Boolean,recording:Boolean,now:Long,delta:Long) {
    val valid=sample?.values?.get("status")?.toIntOrNull()==1
    val bpm=sample?.values?.get("bpm")?.toDoubleOrNull()?.takeIf{it>0 && valid}
    val current=fresh && sample!=null && now-delta-sample.wall<15000
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF213525),Color(0xFF111D16)))).border(1.dp,Color(0xFF3D583A),RoundedCornerShape(26.dp))) {
        Canvas(Modifier.matchParentSize()) {
            val y=size.height*.68f
            val path=Path().apply{moveTo(size.width*.58f,y);lineTo(size.width*.66f,y);lineTo(size.width*.70f,y-20);lineTo(size.width*.75f,y+30);lineTo(size.width*.82f,y-70);lineTo(size.width*.88f,y);lineTo(size.width,y)}
            drawPath(path,Lime.copy(alpha=.10f),style=Stroke(2.dp.toPx()))
        }
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Eyebrow("FRECUENCIA CARDÍACA");Text(if(current)"● REC" else "○ EN ESPERA",fontFamily=Mono,fontSize=9.sp,color=if(current)Lime else Muted) }
            Row(verticalAlignment=Alignment.Bottom) {
                Text(num(bpm),fontFamily=Mono,fontSize=72.sp,fontWeight=FontWeight.Light,color=Lime,letterSpacing=(-4).sp)
                Text("lpm",fontSize=16.sp,color=Muted,modifier=Modifier.padding(start=12.dp,bottom=16.dp))
            }
            Text(when {
                current && bpm!=null -> "Recibiendo del reloj"
                recording && bpm==null -> "Esperando pulso válido · mantén el reloj puesto"
                sample!=null -> "Última lectura · "+time(sample.wall)
                else -> "Inicia el registro para ver tu pulso"
            },fontSize=12.sp,color=Muted)
        }
    }
}
@Composable private fun SmallMetric(label:String,value:String,unit:String,note:String,modifier:Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(Panel).border(1.dp,Edge,RoundedCornerShape(20.dp)).padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Eyebrow(label); Row(verticalAlignment=Alignment.Bottom){Text(value,fontSize=27.sp,fontFamily=Mono);Text(" "+unit,fontSize=12.sp,color=Muted,modifier=Modifier.padding(bottom=5.dp))}
        Text(note,fontSize=10.sp,color=Muted)
    }
}
@Composable private fun Micro(label:String,value:String) { Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Text(label,fontFamily=Mono,fontSize=8.sp,color=Muted,letterSpacing=.8.sp);Text(value,fontSize=10.sp,color=Mint)} }
@Composable private fun Info(title:String,body:String) { Column(Modifier.fillMaxWidth().padding(3.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(title,fontSize=13.sp,fontWeight=FontWeight.Medium,color=Color(0xFFD0DED4));Text(body,fontSize=12.sp,color=Muted,lineHeight=18.sp)} }
@Composable private fun Counter(n:Int,label:String,color:Color,modifier:Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(n.toString().padStart(2,'0'),fontSize=25.sp,fontFamily=Mono,color=color);Text(label,fontSize=10.sp,color=Muted)}
}
@Composable private fun SignalCard(metric:Metric,index:Int,select:(Int)->Unit,points:List<Sample>,caption:String) {
    SurfaceCard {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Señal",fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text(metric.unit,fontSize=11.sp,fontFamily=Mono,color=Mint)}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            metrics.forEachIndexed{i,m->FilterChip(selected=index==i,onClick={select(i)},label={Text(m.label,fontSize=11.sp)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF2B4327),selectedLabelColor=Lime))}
        }
        SignalPlot(points,metric)
        Text(caption,fontSize=9.sp,fontFamily=Mono,color=Muted,letterSpacing=.7.sp)
    }
}
@Composable private fun SignalPlot(points:List<Sample>,metric:Metric) {
    val colors=listOf(Lime,Mint,Color(0xFF98B9FF))
    var cursor by remember(metric,points.size) { mutableStateOf<Int?>(null) }
    val series=remember(points,metric){metric.keys.map{k->points.map{value(it,k)}}}
    val all=series.flatten().filterNotNull().filter{it.isFinite()}
    val low=all.minOrNull() ?: 0.0; val high=all.maxOrNull() ?: 1.0
    val pad=max((high-low)*.15,if(metric.stream=="heart_rate")2.0 else .1)
    val minY=low-pad; val maxY=high+pad
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(num(high,1),fontSize=9.sp,fontFamily=Mono,color=Muted);Text(if(cursor!=null && points.isNotEmpty())time(points[cursor!!.coerceIn(points.indices)].wall) else "Toca para inspeccionar",fontSize=9.sp,color=Muted)}
        Box(Modifier.fillMaxWidth().height(155.dp)) {
            Canvas(Modifier.fillMaxSize().pointerInput(points,metric){detectTapGestures{pos->if(points.isNotEmpty())cursor=((pos.x/size.width)*(points.size-1)).toInt().coerceIn(points.indices)}}) {
                for(i in 0..4) {val y=size.height*i/4;drawLine(Edge,Offset(0f,y),Offset(size.width,y),1f)}
                for(i in 0..6) {val x=size.width*i/6;drawLine(Edge.copy(alpha=.5f),Offset(x,0f),Offset(x,size.height),1f)}
                if(points.isNotEmpty()) {
                    val start=points.first().wall; val duration=(points.last().wall-start).coerceAtLeast(1)
                    fun x(i:Int)=(points[i].wall-start).toFloat()/duration*size.width
                    fun y(v:Double)=size.height-((v-minY)/(maxY-minY)*size.height).toFloat()
                    series.forEachIndexed{channel,values->
                        val path=Path(); var pen=false
                        values.forEachIndexed{i,v->
                            if(v==null || !v.isFinite()) pen=false else {
                                val gap=i>0 && points[i].wall-points[i-1].wall>if(metric.stream=="temperatura")90000 else 5000
                                if(!pen || gap)path.moveTo(x(i),y(v)) else path.lineTo(x(i),y(v))
                                pen=true
                                if(values.size<15) drawCircle(colors[channel%3],3.dp.toPx(),Offset(x(i),y(v)))
                            }
                        }
                        drawPath(path,colors[channel%3],style=Stroke(1.6.dp.toPx(),cap=StrokeCap.Round))
                    }
                    cursor?.coerceIn(points.indices)?.let{i->drawLine(Color.White.copy(alpha=.45f),Offset(x(i),0f),Offset(x(i),size.height),1f)}
                }
            }
            if(all.isEmpty()) Text("SIN MUESTRAS EN ESTA VENTANA",fontSize=10.sp,fontFamily=Mono,color=Muted,modifier=Modifier.align(Alignment.Center))
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(points.firstOrNull()?.let{time(it.wall)} ?: "—",fontSize=9.sp,fontFamily=Mono,color=Muted)
            Text(points.lastOrNull()?.let{time(it.wall)} ?: "—",fontSize=9.sp,fontFamily=Mono,color=Muted)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            metric.keys.forEachIndexed{i,k->Text(k.substringBefore("_")+" "+(cursor?.let{c->series[i].getOrNull(c)?.let{num(it,1)}} ?: ""),color=colors[i%3],fontSize=9.sp,fontFamily=Mono)}
        }
    }
}
@Composable private fun NavIcon(index:Int,color:Color) {
    Canvas(Modifier.size(23.dp)) {
        val w=size.width;val h=size.height
        val p=Path()
        when(index) {
            0->{p.moveTo(0f,h*.5f);p.lineTo(w*.2f,h*.5f);p.lineTo(w*.35f,h*.22f);p.lineTo(w*.5f,h*.8f);p.lineTo(w*.67f,h*.35f);p.lineTo(w*.8f,h*.5f);p.lineTo(w,h*.5f)}
            1->{p.moveTo(w*.15f,h*.3f);p.lineTo(w*.85f,h*.3f);p.lineTo(w*.85f,h*.85f);p.lineTo(w*.15f,h*.85f);p.close();p.moveTo(w*.08f,h*.12f);p.lineTo(w*.92f,h*.12f);p.moveTo(w*.38f,h*.5f);p.lineTo(w*.62f,h*.5f)}
            2->{drawCircle(color,w*.35f,Offset(w*.5f,h*.48f),style=Stroke(1.6.dp.toPx()));p.moveTo(w*.5f,h*.27f);p.lineTo(w*.5f,h*.5f);p.lineTo(w*.66f,h*.58f)}
            else->{for(i in 1..3){val y=h*i/4;p.moveTo(w*.12f,y);p.lineTo(w*.88f,y);drawCircle(color,2.4.dp.toPx(),Offset(w*(if(i==2).65f else .35f),y))}}
        }
        drawPath(p,color,style=Stroke(1.6.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
    }
}
@Composable private fun EventDialog(event:EventInfo?,sessions:List<SessionInfo>,liveSession:String,onDismiss:()->Unit,
    onSave:(String,String,String,Long,String)->Unit,onSignal:(String)->Unit) {
    var kind by remember { mutableStateOf(event?.kind?.takeIf{it!="POR_CLASIFICAR"} ?: "PIPA_SIM") }
    var note by remember { mutableStateOf(event?.note ?: "") }
    var ago by remember { mutableStateOf("Ahora") }
    val kinds=listOf("PIPA","CIGARRO","VAPE","PIPA_SIM","OTRO")
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(event==null)"Registrar consumo" else "Revisar evento")},text={
        Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(if(event==null)"Anota lo ocurrido para relacionarlo con tus señales." else time(event.time,"dd MMM yyyy · HH:mm:ss")+" · "+stateLabel(event.state),fontSize=13.sp,color=Muted)
            if(event?.origin=="test") Text("Evento de prueba · excluido del entrenamiento",color=Amber,fontSize=12.sp)
            Text("Tipo",fontSize=12.sp)
            kinds.chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){row.forEach{k->FilterChip(selected=kind==k,onClick={kind=k},label={Text(title(k),fontSize=12.sp)})}}}
            if(event==null) {
                Text("Cuándo ocurrió",fontSize=12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    listOf("Ahora","Hace 1 min","Hace 5 min","Hace 15 min").forEach{v->FilterChip(selected=ago==v,onClick={ago=v},label={Text(v,fontSize=10.sp)})}
                }
            }
            OutlinedTextField(value=note,onValueChange={note=it.take(1000)},label={Text(if(event==null)"Notas (opcional)" else "Qué ocurrió / motivo")},minLines=2,modifier=Modifier.fillMaxWidth())
            if(event!=null && event.session.isNotBlank()) TextButton(onClick={onSignal(event.session)}){Text("Ver señales de la sesión →")}
            if(event!=null) {
                OutlinedButton(onClick={onSave(event.kind,note,"false_positive",event.time,event.session)},modifier=Modifier.fillMaxWidth()){Text("Descartar · falso positivo")}
                if(event.state!="pending") TextButton(onClick={onSave(kind,note,"pending",event.time,event.session)}){Text("Devolver a «Por revisar»")}
            }
        }
    },confirmButton={Button(onClick={
        val minutes=when(ago){"Hace 1 min"->1;"Hace 5 min"->5;"Hace 15 min"->15;else->0}
        val t=event?.time ?: (System.currentTimeMillis()-minutes*60000L)
        val id=event?.session ?: sessions.find{t>=it.start && (it.end==0L && it.id==liveSession || t<=it.end)}?.id.orEmpty()
        onSave(kind,note,"confirmed",t,id)
    }){Text(if(event==null)"Guardar" else "Confirmar")}},dismissButton={TextButton(onClick=onDismiss){Text("Cancelar")}})
}
