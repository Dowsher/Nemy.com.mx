package org.pipalab.phone

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import kotlin.math.*

data class MeterFrame(val session:String="",val values:List<Double> = emptyList(),val rms:Double=0.0,val peak:Double=0.0,val received:Long=0,val silenced:Boolean=false)
object AudioMeter {
    val state=MutableStateFlow(MeterFrame())
    fun accept(j:JSONObject) {
        val live=PhoneLive.state.value
        if(!live.connected || !live.recording || j.optString("training_id")!=live.trainingId || System.currentTimeMillis()-live.clockDelta-j.optLong("wall") !in -1000..3500)return
        val a=j.optJSONArray("envelope") ?: return
        state.value=MeterFrame(live.session,(0 until a.length().coerceAtMost(64)).map{a.optDouble(it,0.0).coerceIn(0.0,1.0)},j.optDouble("rms",0.0),j.optDouble("peak",0.0),System.currentTimeMillis(),j.optBoolean("silenced"))
    }
}
private val Red=Color(0xFFFF6174)
private fun severity(v:Double?,yellow:Double,red:Double)=if(v==null) -1 else if(v>=red)2 else if(v>=yellow)1 else 0
private fun tint(level:Int)=when(level){0->Lime;1->Amber;2->Red;else->Muted}
private fun threshold(c:android.content.Context,key:String,default:Double)=c.getSharedPreferences("prefs",0).getString(key,null)?.toDoubleOrNull()?.takeIf{it.isFinite() && it>0} ?: default

@Composable internal fun LiveDashboard(store:Store,connect:()->Unit) {
    val c=LocalContext.current
    val link by PhoneLive.state.collectAsState();val audio by AudioMeter.state.collectAsState();val detector by DetectorLive.state.collectAsState();val esp by EspLive.state.collectAsState()
    var now by remember {mutableLongStateOf(System.currentTimeMillis())}
    var message by remember {mutableStateOf("")};var requested by remember {mutableStateOf("")}
    var baseline by remember(link.session) {mutableStateOf<Map<String,Double>>(emptyMap())}
    var demo by remember {mutableStateOf(c.getSharedPreferences("prefs",0).getBoolean("audio_demo",false))}
    LaunchedEffect(Unit){while(true){now=System.currentTimeMillis();delay(250)}}
    val active=link.connected && link.recording
    SurfaceCard {
        Eyebrow("SEÑALES DEL RELOJ / EN VIVO")
        Text(if(active) "● Capturando" else "Captura apagada",color=if(active)Lime else Muted,fontSize=24.sp)
        Text(link.text,color=Muted,fontSize=12.sp)
        if(!link.connected)Button(onClick=connect){Text("Conectar reloj")}
        Text("Pulso · piel · acelerómetro · giroscopio · micrófono",color=Mint,fontSize=12.sp)
        if(!link.remoteReady)Text("Abre Visio en el reloj y activa «Listo para el teléfono».",color=Amber,fontSize=12.sp)
        if(active || requested.isNotBlank())Button(enabled=link.connected && !link.stopping,onClick={
            c.startService(Intent(c,LinkService::class.java).setAction("TRAINING_STOP").putExtra("id",link.trainingId.ifBlank{requested}));requested=""
        }) {Text("Detener y guardar")}
        else Button(enabled=link.connected && link.remoteReady && PhoneLive.clock.best(android.os.SystemClock.elapsedRealtimeNanos())!=null,onClick={
            runCatching {
                val training=TrainingStore(store);val id=training.addType("Observación sin etiqueta")
                val plan=training.prepare(TrainingType(id,"Observación sin etiqueta"),"Monitoreo deliberado de hasta 10 minutos, sin video. Revisar las actividades y los intervalos antes de entrenar.",true,600)
                requested=plan.id
                c.startService(Intent(c,LinkService::class.java).setAction("TRAINING_START").putExtra("id",plan.id))
            }.onFailure{message=it.message.orEmpty()}
        }){Text("Iniciar sensores + audio · 10 min")}
        LaunchedEffect(link.recording,link.trainingId){if(link.recording)requested=""}
        Text("La observación se guarda como sesión sin etiqueta. Para incluir video y descripción por voz, usa Capturar.",fontSize=11.sp,color=Muted)
        if(message.isNotBlank())Text(message,color=Amber)
    }
    val latest=metrics.associateWith{m->LiveSamples.latest(link.session,m.stream)?.takeIf{active && now-link.clockDelta-it.wall in -1000..10000}}
    LaunchedEffect(latest.mapValues{it.value?.elapsed}) {
        val updated=baseline.toMutableMap()
        for(m in listOf(metrics[0],metrics[3]))latest[m]?.let{s->value(s,m.keys[0])?.takeIf{it.isFinite()}?.let{updated.putIfAbsent(m.stream,it)}}
        baseline=updated
    }
    for(m in metrics) {
        val row=latest[m];val values=m.keys.map{key->row?.let{value(it,key)}}
        val magnitude=if(values.all{it!=null})sqrt(values.sumOf{it!!*it})else null
        val metric=when(m.stream){"heart_rate","temperatura"->values[0]?.let{v->baseline[m.stream]?.let{abs(v-it)}};"acelerometro"->magnitude?.let{abs(it-9.80665)};else->magnitude}
        val key=when(m.stream){"heart_rate"->"hr";"temperatura"->"temp";"acelerometro"->"acc";else->"gyro"}
        val defaults=when(key){"hr"->15.0 to 30.0;"temp"->.5 to 1.0;"acc"->.8 to 2.0;else->.6 to 1.2}
        val yellow=threshold(c,"attention_${key}_yellow",defaults.first);val red=threshold(c,"attention_${key}_red",defaults.second)
        val level=severity(metric,yellow,red)
        SurfaceCard {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(m.label,color=tint(level),fontSize=19.sp);Text(if(row==null)"SIN SEÑAL" else "EN VIVO",color=tint(level),fontSize=10.sp)}
            Text(values.joinToString(" / "){num(it,if(key=="hr")0 else 2)}+" "+m.unit,fontSize=23.sp,fontFamily=Mono)
            LinearProgressIndicator(progress={((metric ?: 0.0)/red).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=tint(level))
            Text(when(key){"hr"->"Ritmo cardíaco: contexto fisiológico. Color por cambio respecto a ${num(baseline[m.stream])} lpm al comenzar.";"temp"->"Temperatura de la piel: contexto lento y contacto. No es temperatura corporal central. Cambio respecto a ${num(baseline[m.stream],2)} °C.";"acc"->"Aceleración de la muñeca. Color por |magnitud − gravedad|; el detector separa gravedad y movimiento.";else->"Velocidad de giro de la muñeca: identifica elevación, permanencia y retorno."},fontSize=11.sp,color=Muted)
            Text("Atención ${num(yellow,2)} · alto ${num(red,2)} ${m.unit} · "+if(row==null)"esperando lectura válida" else "${((now-link.clockDelta-row.wall).coerceAtLeast(0)/1000)} s desde lectura",fontSize=10.sp,color=Muted)
        }
    }
    SurfaceCard {
        val fresh=active && audio.session==link.session && now-audio.received<3500 && !audio.silenced
        val dbfs=if(fresh && audio.peak>0)20*log10(audio.peak)else null
        val level=if(!fresh)-1 else if((dbfs ?: -120.0)>=-6)2 else if((dbfs ?: -120.0)>=-18)1 else 0
        Eyebrow("MICRÓFONO DEL RELOJ")
        Text(if(!fresh)"Sin audio en vivo" else "${num(dbfs,1)} dBFS · pico",color=tint(level),fontSize=22.sp)
        Canvas(Modifier.fillMaxWidth().height(80.dp).background(Color(0xFF0A1410),RoundedCornerShape(8.dp))) {
            val bars=if(fresh)audio.values else emptyList()
            bars.forEachIndexed{i,v->val x=(i+.5f)*size.width/64;val height=((20*log10(v.coerceAtLeast(.0001))+80)/80).coerceIn(0.0,1.0).toFloat()*size.height/2
                drawLine(tint(level),Offset(x,size.height/2-height),Offset(x,size.height/2+height),size.width/90)}
        }
        Text("Envolvente real de los últimos bloques de audio. Amarillo ≥ −18 dBFS, rojo ≥ −6 dBFS: nivel de grabación, no riesgo auditivo.",fontSize=11.sp,color=Muted)
        Row {Text("Chasquidos → LED",Modifier.weight(1f));Switch(checked=demo,onCheckedChange={demo=it;c.getSharedPreferences("prefs",0).edit().putBoolean("audio_demo",it).apply()})}
        Text(if(!demo)"Desactivado" else if(!esp.ready)"Activado · falta conectar ESP32" else if(!fresh)"Activado · esperando micrófono" else "Listo · truena los dedos cerca del reloj",color=if(demo && esp.ready && fresh)Lime else Amber,fontSize=12.sp)
        Text("Demo de impulsos breves; otros golpes pueden activarla. Apagar bloquea nuevas órdenes; un pulso ya enviado termina por su temporizador.",fontSize=11.sp,color=Muted)
        if(esp.last.isNotBlank())Text(esp.last,color=Mint,fontSize=11.sp)
    }
    SurfaceCard {
        val fresh=active && detector.optString("session")==link.session && now-detector.optLong("received_ms")<20000
        val score=if(fresh)detector.optDouble("S",0.0)else 0.0;val level=if(!fresh || detector.optDouble("valid_fraction",0.0)<.8)-1 else if(score>=6)2 else if(score>=3)1 else 0
        Eyebrow("DETECTOR / VISIO REF 1.1.0")
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("Normal","Cuidado","Potencial consumo").forEachIndexed{i,label->
            Surface(color=if(level==i)tint(i)else Color(0xFF24302A),shape=RoundedCornerShape(8.dp),modifier=Modifier.weight(1f)){Text(label,color=if(level==i)Ink else Muted,fontSize=10.sp,modifier=Modifier.padding(8.dp))}}}
        Text(if(fresh)"${detector.optString("state")} · acumulador ${num(score,2)} / 6" else "Esperando señal activa",fontFamily=Mono,fontSize=13.sp)
        Text("Ranking heurístico sin modelo validado. Los colores son atención de señales, no umbrales de peligro médico. El reloj pregunta para obtener tu etiqueta; no confirma automáticamente una actividad.",fontSize=11.sp,color=Muted)
        if(detector.optString("state")=="ERROR")Text(detector.optString("message"),color=Amber,fontSize=11.sp)
        TextButton(onClick={baseline=emptyMap()}){Text("Reiniciar referencia de pulso y piel")}
    }
}

@Composable internal fun DetectionReviews(store:Store,session:String?=null) {
    val reviews=remember{DetectionStore(store)};var rows by remember{mutableStateOf(emptyList<JSONObject>())};var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(session,refresh){while(true){rows=reviews.events(session).take(100);delay(2000)}}
    SurfaceCard {
        Eyebrow("DETECCIONES Y ETIQUETAS")
        Text("Revisa cada intervalo",fontSize=22.sp)
        if(rows.isEmpty())Text("Todavía no hay gestos completos analizados. Las lecturas sin datos válidos no generan detecciones.",color=Muted,fontSize=12.sp)
        rows.forEach {r->
            val evidence=JSONObject(r.getString("evidence"));val score=evidence.optJSONObject("gesture")?.optDouble("score")
            Text(time(r.getLong("wall"))+" · índice ${num(score,2)}",color=Mint,fontSize=13.sp)
            Text(when(r.optString("label")){"confirmed"->"Consumo confirmado";"false_positive"->"Detección incorrecta / descartada";"unsure"->"Sin certeza";else->"Pendiente de calificar"},color=Muted,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {listOf("confirmed" to "Sí", "false_positive" to "No", "unsure" to "No sé").forEach{(label,text)->
                OutlinedButton(onClick={reviews.review(r.getString("id"),label);refresh++}){Text(text)}}}
            Text("${time(evidence.optLong("start_wall_ms"))} – ${time(evidence.optLong("end_wall_ms"))} · correcciones con auditoría",fontSize=10.sp,color=Muted)
        }
        Text("Exportar entrenamiento incluye detecciones.json con evidencia, versión, intervalos, respuestas del reloj y correcciones.",fontSize=11.sp,color=Muted)
    }
}

@Composable internal fun DetectorSettings() {
    val c=LocalContext.current;val prefs=remember{c.getSharedPreferences("prefs",0)}
    var prompts by remember{mutableStateOf(prefs.getBoolean("detector_prompts",true))}
    SurfaceCard {
        Eyebrow("DETECTOR Y MONTAJE")
        Text("Muñeca izquierda · botones hacia la mano",color=Mint)
        Text("Configuración anatómica del Python: sx=+1, sy=+1. Falta validar las dos poses de calibración del algoritmo para este montaje.",color=Muted,fontSize=11.sp)
        Row{Text("Preguntar en el reloj",Modifier.weight(1f));Switch(checked=prompts,onCheckedChange={prompts=it;prefs.edit().putBoolean("detector_prompts",it).apply()})}
        Text("Máximo 4 preguntas en 24 h, separadas por 45 min. Solo se pregunta por candidatos recientes; puedes calificar todos aquí.",color=Muted,fontSize=11.sp)
        Text("Umbrales visuales de atención",fontSize=18.sp)
        for((key,label,limits) in listOf(Triple("hr","Cambio pulso (lpm)",15.0 to 30.0),Triple("temp","Cambio piel (°C)",.5 to 1.0),Triple("acc","Movimiento (m/s²)",.8 to 2.0),Triple("gyro","Giro (rad/s)",.6 to 1.2))) {
            var yellow by remember{mutableStateOf(threshold(c,"attention_${key}_yellow",limits.first).toString())}
            var red by remember{mutableStateOf(threshold(c,"attention_${key}_red",limits.second).toString())}
            Text(label,fontSize=12.sp)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(yellow,{yellow=it},label={Text("Amarillo")},singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(red,{red=it},label={Text("Rojo")},singleLine=true,modifier=Modifier.weight(1f))
            }
            val y=yellow.toDoubleOrNull();val r=red.toDoubleOrNull()
            TextButton(enabled=y!=null && r!=null && y.isFinite() && r.isFinite() && y>0 && r>y,onClick={prefs.edit().putString("attention_${key}_yellow",yellow).putString("attention_${key}_red",red).apply()}){Text("Guardar límites")}
        }
        Text("Estos límites cambian los colores; no cambian el algoritmo ni representan peligro clínico.",fontSize=11.sp,color=Muted)
    }
}
