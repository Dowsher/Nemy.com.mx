package org.pipalab.phone

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import org.pipalab.core.ActivityTypes
import org.pipalab.core.TimeWindow
import java.io.File

internal fun captureStatus(status:String)=mapOf("draft" to "Lista para abrir cámara", "not_ready" to "Capturador del reloj apagado", "expired" to "Inicio caducado", "requested" to "Preparando","prepared" to "Lista: inicia en el reloj","recording" to "Registrando","finishing" to "Cerrando archivos","closed" to "Finalizada","interrupted" to "Interrumpida: revisar",
    "cancelled" to "Preparación cancelada","unconfirmed" to "Preparación sin confirmar","unsupported" to "Actualiza Visio en el reloj","busy" to "El reloj ya registra otra sesión","pending_other" to "Hay otra preparación en el reloj","already_started" to "Ya se inició; espera sincronización","not_pending" to "No hay preparación pendiente").getOrDefault(status,status)
private fun reviewText(status:String)=mapOf("accepted" to "Revisada","excluded" to "Excluir del entrenamiento","unreviewed" to "Pendiente de revisión").getOrDefault(status,status)
private data class TrainingSnapshot(val records:List<TrainingRecord> = emptyList(),val types:List<TrainingType> = emptyList(),val sessions:List<SessionInfo> = emptyList(),val transcripts:List<Transcript> = emptyList())

@Composable internal fun TrainingApp(store:Store,dictation:TrainingDictation,notice:String,notify:(String)->Unit,connect:()->Unit,
    espAction:(String,String)->Unit,dictate:(String)->Unit,export:()->Unit,exportLegacy:()->Unit) {
    val context=LocalContext.current
    val training=remember {TrainingStore(store)}
    val learner=remember {LearningEngine.get(context)}
    val learning by learner.state.collectAsState()
    val scope=rememberCoroutineScope()
    val link by PhoneLive.state.collectAsState()
    val esp by EspLive.state.collectAsState()
    val voice by dictation.state.collectAsState()
    var tab by rememberSaveable {mutableIntStateOf(2)}
    var selected by rememberSaveable {mutableStateOf("")}
    val captureLauncher=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {result->
        result.data?.getStringExtra("id")?.let {selected=it;tab=1}
    }
    var signalSession by rememberSaveable {mutableStateOf("")}
    var type by rememberSaveable {mutableStateOf("rest")}
    var description by rememberSaveable {mutableStateOf("")}
    val withAudio=true
    var technicalTest by rememberSaveable {mutableStateOf(false)}
    var evaluationOnly by rememberSaveable {mutableStateOf(false)}
    var seconds by rememberSaveable {mutableIntStateOf(60)}
    var snapshot by remember {mutableStateOf(TrainingSnapshot())}
    var newType by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<TrainingRecord?>(null)}
    val prefs=remember{context.getSharedPreferences("prefs",Context.MODE_PRIVATE)}
    var demo by remember {mutableStateOf(prefs.getBoolean("audio_demo",false))}
    LaunchedEffect(tab){demo=prefs.getBoolean("audio_demo",false)}
    var ledDuration by remember {mutableIntStateOf(prefs.getInt("esp_duration",1000))}
    var pendingOperation by remember {mutableStateOf(false)}
    LaunchedEffect(learner){learner.refresh();while(isActive){withContext(Dispatchers.IO){learner.live()};delay(3000)}}
    LaunchedEffect(tab,selected,link.trainingId) {while(isActive) {
        snapshot=withContext(Dispatchers.IO){TrainingSnapshot(training.records(),training.types(),store.sessions(),training.transcripts(if(tab==1)selected else PhoneLive.state.value.trainingId))}
        delay(1200)
    }}
    LaunchedEffect(link.recording,link.connected) {if(!link.recording || !link.connected){dictation.stop()}}
    fun command(action:String,id:String,label:String="") {
        context.startService(Intent(context,LinkService::class.java).setAction(action).putExtra("id",id).putExtra("label",label))
    }
    fun work(block:()->Unit) {scope.launch {runCatching{withContext(Dispatchers.IO){block()}}.onFailure{notify(it.message ?: "No se pudo guardar")}}}
    val current=snapshot.records.firstOrNull{it.id==link.trainingId && link.recording}
    val pending=snapshot.records.firstOrNull{it.status in setOf("draft") && it.session.isBlank()}
    val detail=snapshot.records.firstOrNull{it.id==selected}
    val fresh=link.connected && link.recording && System.currentTimeMillis()-link.lastData<10000
    Scaffold(containerColor=Ink,bottomBar={NavigationBar(containerColor=Ink) {
        listOf("Capturar","Sesiones","En vivo","Aprender","Ajustes").forEachIndexed { i,label -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("●","▤","⌁","◇","⚙")[i],color=if(tab==i)Lime else Muted,fontSize=22.sp)},label={Text(label,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xFF243720))) }
    }}) { inset ->
        LazyColumn(Modifier.fillMaxSize().padding(inset),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Visio",fontSize=30.sp,fontWeight=FontWeight.SemiBold);Spacer(Modifier.weight(1f));Column(horizontalAlignment=Alignment.End){Text("NEMY",color=Lime,fontFamily=Mono,letterSpacing=2.sp);Text("LIVE / 0.9",color=Muted,fontFamily=Mono,fontSize=9.sp)}}}
            if(notice.isNotBlank())item {Text(notice,color=Amber,fontSize=12.sp,modifier=Modifier.fillMaxWidth().clickable{notify("")}.padding(8.dp))}
            when(tab) {
                0 -> {
                    item {Text("Construye tu dataset",fontSize=27.sp,fontWeight=FontWeight.SemiBold);Text("Sesiones reales, etiquetas tuyas y evidencia revisable.",fontSize=13.sp,color=Muted)}
                    item {SurfaceCard {
                        Eyebrow(if(fresh) "SEÑALES RECIBIDAS" else if(link.connected) "RELOJ CONECTADO" else "CONECTA TU RELOJ")
                        Text(link.text,fontSize=13.sp,color=if(link.connected)Mint else Muted)
                        if(!link.connected)Button(onClick=connect){Text("Conectar reloj")}
                        else if(link.protocol<4)Text("Instala Visio Wear 0.6 o posterior y activa Listo para el teléfono.",color=Amber,fontSize=12.sp)
                        else if(!link.remoteReady)Text("En el reloj, abre Visio y activa «Listo para el teléfono».",color=Amber,fontSize=12.sp)
                        if(link.pendingTrainingId.isNotBlank() && !link.recording) TextButton(onClick={command("TRAINING_CANCEL",link.pendingTrainingId)}){Text("Cancelar preparación anterior del reloj")}
                        if(link.sync.isNotBlank())Text(link.sync,fontSize=11.sp,color=Muted)
                    }}
                    if(link.recording) {
                        item {SurfaceCard {
                            Eyebrow("CAPTURA EN EL RELOJ")
                            Text(link.activity.ifBlank{"Sesión anterior"},fontSize=24.sp,fontWeight=FontWeight.Medium)
                            Text("%02d:%02d".format(link.seconds/60,link.seconds%60),fontSize=46.sp,fontFamily=Mono,color=Lime)
                            if(link.durationSeconds>0)LinearProgressIndicator(progress={ (link.seconds.toFloat()/link.durationSeconds).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth(),color=Lime)
                            Text(if(link.stopping)"Guardando archivos…" else if(fresh)"Movimiento, pulso y temperatura: se conserva todo lo recibido durante esta sesión." else "Esperando muestras. Revisa el reloj.",fontSize=12.sp,color=Muted)
                            AudioBadge(link)
                            Text(learning.suggestion,fontSize=13.sp,color=if(learning.active.isNotBlank())Mint else Muted)
                            Button(onClick={dictation.stop();command("TRAINING_STOP",link.trainingId)},enabled=link.connected && !link.stopping && link.trainingId.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Detener y guardar sesión")}
                            if(link.trainingId.isBlank())Text("Detén esta sesión anterior desde el reloj antes de preparar una actividad.",color=Amber,fontSize=12.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {ActivityTypes.markers.forEach{(key,label)->OutlinedButton(enabled=link.connected && !link.stopping && link.trainingId.isNotBlank(),onClick={command("TRAINING_POINT",link.trainingId,key);notify("Marca solicitada: $label. Se guardará al recibirla el reloj.")}){Text("+ $label",fontSize=12.sp)}}}
                        }}
                        item {SurfaceCard {
                            Eyebrow("DESCRIPCIÓN POR VOZ / TELÉFONO")
                            Text(voice.message,fontSize=13.sp,color=if(voice.listening)Lime else Muted)
                            if(voice.partial.isNotBlank())Text(voice.partial,fontSize=18.sp)
                            Button(enabled=voice.available && current!=null,onClick={if(voice.listening)dictation.stop() else dictate(link.trainingId)},modifier=Modifier.fillMaxWidth()){Text(if(voice.listening)"Terminar dictado" else "Dictar descripción")}
                            Text("Tu voz se transcribe localmente en el teléfono mientras el reloj sigue registrando. Cada frase conserva su intervalo estimado; podrás revisarla al terminar.",fontSize=11.sp,color=Muted)
                            snapshot.transcripts.takeLast(3).forEach{t->Text(time(t.start)+" · "+t.text,fontSize=13.sp,color=Mint)}
                        }}
                    } else {
                        if(pending!=null)item {SurfaceCard {
                            Eyebrow("PREPARACIÓN")
                            Text(pending.activity,fontSize=21.sp);Text(captureStatus(pending.status),color=Amber,fontSize=13.sp)
                            Text("Abre la cámara frontal del teléfono. Di acción para iniciar y corte para guardar.",fontSize=12.sp,color=Muted)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick={dictation.stop();captureLauncher.launch(Intent(context,SessionCaptureActivity::class.java).putExtra("id",pending.id))}){Text("Abrir cámara")}
                                TextButton(onClick={training.status(pending.id,"cancelled")}){Text("Cancelar")}
                            }
                        }}
                        item {SurfaceCard {
                            Eyebrow("NUEVA SESIÓN")
                            Text("¿Qué vas a hacer?",fontSize=22.sp)
                            TypeSelector(snapshot.types,type,{type=it})
                            TextButton(onClick={newType=true}){Text("+ Crear tipo de actividad")}
                            OutlinedTextField(value=description,onValueChange={description=it.take(1200)},label={Text("Describe la actividad y el contexto")},placeholder={Text("Mano usada, postura, objetos, ambiente y qué quieres comparar…")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=7)
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf(30,60,120,300,600).forEach{n->FilterChip(selected=seconds==n,onClick={seconds=n},label={Text(if(n<60)"$n s" else "${n/60} min")})}}
                            Text("Cada sesión: video frontal ~480p + audio del reloj + pulso, temperatura, giroscopio y acelerómetro.",color=Mint,fontSize=13.sp)
                            Text("Crea la sesión, encuadra la cámara y di «acción». Di «corte» para cerrar y guardar. También tienes botones.",color=Muted,fontSize=12.sp)
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=technicalTest,onCheckedChange={technicalTest=it});Text("Solo prueba técnica: excluir del entrenamiento",fontSize=12.sp,modifier=Modifier.weight(1f))}
                            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=evaluationOnly,enabled=!technicalTest,onCheckedChange={evaluationOnly=it});Text("Reservar para evaluar: no usar para aprender",fontSize=12.sp,modifier=Modifier.weight(1f))}
                            Button(enabled=pending==null && !pendingOperation && !link.recording,onClick={
                                val selectedType=snapshot.types.firstOrNull{it.id==type} ?: return@Button
                                pendingOperation=true
                                scope.launch {runCatching {withContext(Dispatchers.IO){training.prepare(selectedType,description,withAudio,seconds,technicalTest,evaluationOnly)}}
                                    .onSuccess{training.status(it.id,"draft");dictation.stop();captureLauncher.launch(Intent(context,SessionCaptureActivity::class.java).putExtra("id",it.id))}
                                    .onFailure{notify(it.message.orEmpty())};pendingOperation=false}
                            },modifier=Modifier.fillMaxWidth()){Text("Crear nueva sesión de ejemplo")}
                        }}
                    }
                    item {SurfaceCard {Eyebrow("REGISTRO, NO CLASIFICACIÓN AUTOMÁTICA");Text("Tú defines la actividad y revisas cada sesión. Las señales, audio, dictados y marcas quedan agrupados por tipo para entrenar después.",fontSize=13.sp,color=Muted)}}
                }
                1 -> {
                    item {Text("Biblioteca de actividades",fontSize=26.sp,fontWeight=FontWeight.SemiBold);Text("${snapshot.records.size} sesiones · ${snapshot.records.count{it.review=="accepted"}} revisadas",fontSize=13.sp,color=Muted)}
                    if(detail!=null) {
                        item {TextButton(onClick={selected=""}){Text("← Volver a los tipos")}}
                        item {SurfaceCard {
                            Eyebrow(time(detail.created,"dd MMM yyyy · HH:mm"));Text(detail.activity,fontSize=25.sp)
                            Text(captureStatus(detail.status),color=if(detail.status=="closed")Lime else Amber)
                            Text(reviewText(detail.review),color=Muted,fontSize=12.sp)
                            if(detail.description.isNotBlank())Text(detail.description,fontSize=14.sp)
                            Text(if(detail.verified)"Archivos recibidos y verificados" else "Copia pendiente o incompleta; conserva el reloj cerca",fontSize=12.sp,color=if(detail.verified)Mint else Amber)
                            Text("Audio: ${detail.audioState} · ${num(detail.audioSeconds,1)} s",fontSize=12.sp)
                            if(detail.technicalTest)Text("PRUEBA TÉCNICA · excluir del entrenamiento",color=Amber,fontSize=12.sp)
                            else Text(if(JSONObject(detail.original).optBoolean("evaluation_only"))"Reservada para evaluación; nunca entrena el modelo" else "Puede entrenar al aceptarse y verificar su calidad",color=Mint,fontSize=12.sp)
                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(enabled=detail.status in setOf("closed","interrupted"),onClick={editing=detail}){Text("Revisar y describir")}
                                TextButton(enabled=detail.session.isNotBlank(),onClick={signalSession=detail.session;tab=2}){Text("Ver señales")}
                            }
                        }}
                        item {SessionReviewMedia(store,detail)}
                        item {AudioPlayer(store,detail)}
                        item {SurfaceCard {Eyebrow("DICTADOS COMPLETOS");if(snapshot.transcripts.isEmpty())Text("No hay descripciones por voz para esta sesión.",color=Muted,fontSize=12.sp)
                            snapshot.transcripts.forEach{t->Text(time(t.start)+" – "+time(t.end)+(if(t.partial)" · parcial" else ""),fontSize=10.sp,color=Muted);Text(t.text,fontSize=14.sp)}
                            if(snapshot.transcripts.isNotEmpty())Text("Transcripción automática: revísala y usa la descripción editable para corregir el significado. Los originales se conservan.",fontSize=11.sp,color=Muted)
                        }}
                        item {TrainingMarkers(store,detail.session)}
                        item {DetectionReviews(store,detail.session)}
                    } else {
                        snapshot.records.groupBy{it.typeId}.forEach{(_,group)->
                            item {Text(group.first().activity+" · "+group.size,fontSize=19.sp,color=Lime)}
                            items(group,key={it.id}) {r->SurfaceCard(onClick={selected=r.id}){
                                Text(time(r.created,"dd MMM · HH:mm"),fontSize=18.sp)
                                Text(captureStatus(r.status)+" · "+reviewText(r.review),fontSize=12.sp,color=Muted)
                                Text((if(r.audioRequested)"Audio + sensores" else "Solo sensores")+" · "+r.durationSeconds+" s previstos",fontSize=11.sp,color=Mint)
                                if(r.description.isNotBlank())Text(r.description.take(150),fontSize=12.sp,color=Muted)
                            }}
                        }
                        if(snapshot.records.isEmpty())item {Text("Prepara tu primera actividad en Capturar. Las grabaciones anteriores siguen disponibles en Señales.",color=Muted)}
                    }
                    item {Button(onClick=export,modifier=Modifier.fillMaxWidth()){Text("Exportar base de entrenamiento")}}
                }
                2 -> {
                    item {Text("Tu señal, en tiempo real",fontSize=27.sp,fontWeight=FontWeight.SemiBold)}
                    if(signalSession.isBlank())item {Column(verticalArrangement=Arrangement.spacedBy(16.dp)){LiveDashboard(store,connect)}}
                    item {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected=signalSession.isBlank(),onClick={signalSession=""},label={Text("En vivo")})
                        snapshot.sessions.forEach{s->FilterChip(selected=signalSession==s.id,onClick={signalSession=s.id},label={Text(time(s.start,"dd/MM HH:mm"))})}
                    }}
                    item {TrainingSignals(store,signalSession.ifBlank{link.session},signalSession.isBlank() && link.recording)}
                    item {DetectionReviews(store,signalSession.ifBlank{null})}
                }
                3 -> { item {LearningPanel(learner,learning)} }
                else -> {
                    item {DetectorSettings()}
                    item {SurfaceCard {Eyebrow("RELOJ");Text(link.text);Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick=connect){Text("Conectar")};OutlinedButton(onClick={context.stopService(Intent(context,LinkService::class.java))}){Text("Desconectar")}}}}
                    item {SurfaceCard {
                        Eyebrow("DEMO SONORA → LED")
                        Text("Chasquidos como referencia",fontSize=23.sp)
                        Text(esp.text,color=if(esp.ready)Lime else Muted,fontSize=13.sp)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={espAction("SCAN","")}){Text("Buscar ESP32")};OutlinedButton(onClick={espAction("CONNECT",prefs.getString("esp_address","").orEmpty())}){Text("Reconectar")}}
                        esp.devices.forEach{d->OutlinedButton(onClick={espAction("CONNECT",d.address)},modifier=Modifier.fillMaxWidth()){Text(d.name+" · "+d.address.takeLast(5))}}
                        Row(verticalAlignment=Alignment.CenterVertically){Text("Activar demo sonora",modifier=Modifier.weight(1f));Switch(checked=demo,onCheckedChange={demo=it;prefs.edit().putBoolean("audio_demo",it).apply()})}
                        Text("Inicia una sesión con audio, conecta el LED y activa la demo. Truena los dedos cerca del reloj después de un segundo de ambiente.",fontSize=12.sp,color=Muted)
                        Text("Esta demo responde a impulsos breves: también puede activarse con palmadas, golpes o un encendedor. No identifica exclusivamente el sonido ni etiqueta una actividad.",fontSize=12.sp,color=Amber)
                        Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){listOf(1000,3000,5000).forEach{n->FilterChip(selected=ledDuration==n,onClick={ledDuration=n;prefs.edit().putInt("esp_duration",n).apply()},label={Text("${n/1000} s")})}}
                        OutlinedButton(enabled=esp.ready,onClick={work{EspTrigger.pulse(context,"","test")}},modifier=Modifier.fillMaxWidth()){Text("Probar LED manualmente")}
                        if(esp.last.isNotBlank())Text(esp.last,fontSize=11.sp,color=Mint)
                    }}
                    item {SurfaceCard {Eyebrow("DICTADO LOCAL");Text(voice.message,fontSize=13.sp,color=Muted);OutlinedButton(enabled=voice.available,onClick={dictation.requestModel()}){Text("Solicitar modelo de idioma")};Text("El servicio de Android puede necesitar descargar el idioma. Visio no envía las grabaciones a un servidor ni usa un reconocedor remoto.",fontSize=11.sp,color=Muted)}}
                    item {SurfaceCard {Eyebrow("DATOS Y PORTABILIDAD");Text("La base nueva conserva sensores completos solo durante sesiones deliberadas y acotadas. El historial previo permanece intacto.",fontSize=13.sp,color=Muted);Button(onClick=export,modifier=Modifier.fillMaxWidth()){Text("Exportar entrenamiento")};OutlinedButton(onClick=exportLegacy,modifier=Modifier.fillMaxWidth()){Text("Exportar histórico anterior")}}}
                }
            }
            item {Spacer(Modifier.height(16.dp))}
        }
    }
    if(newType) {
        var name by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={newType=false},title={Text("Nuevo tipo")},text={OutlinedTextField(name,{name=it.take(60)},label={Text("Nombre de actividad")})},
            confirmButton={TextButton(enabled=name.isNotBlank(),onClick={work{type=training.addType(name)};newType=false}){Text("Crear")}},dismissButton={TextButton(onClick={newType=false}){Text("Cancelar")}})
    }
    editing?.let{record->ReviewTrainingDialog(record,snapshot.types,snapshot.transcripts,{editing=null}){newTypeId,note,decision->
        val chosen=snapshot.types.first{it.id==newTypeId};work{training.review(record,chosen,note,decision);learner.refresh(true)};editing=null
    }}
}

@Composable private fun TypeSelector(types:List<TrainingType>,selected:String,onSelect:(String)->Unit) {
    Column {types.chunked(2).forEach { pair -> Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {pair.forEach{type->FilterChip(selected=selected==type.id,onClick={onSelect(type.id)},label={Text(type.name,fontSize=12.sp)},modifier=Modifier.weight(1f))};if(pair.size==1)Spacer(Modifier.weight(1f))}}}
}
@Composable private fun AudioBadge(link:LinkStatus) {
    val label=when(link.audioState){"recording"->if(link.audioSilenced)"Micrófono silenciado por Android" else "● Audio grabándose en el reloj";"failed"->"Audio falló: "+link.audioMessage;"empty"->"Audio vacío: revisar micrófono";"closed"->"Audio cerrado";else->"Sesión sin audio"}
    Text(label,color=if(link.audioState=="recording" && !link.audioSilenced)Lime else Amber,fontSize=12.sp)
    if(link.audioState=="recording")LinearProgressIndicator(progress={link.audioPeak.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth(),color=Mint)
}
@Composable private fun ReviewTrainingDialog(record:TrainingRecord,types:List<TrainingType>,transcripts:List<Transcript>,dismiss:()->Unit,save:(String,String,String)->Unit) {
    var type by remember(record.id){mutableStateOf(record.typeId)}
    var note by remember(record.id){mutableStateOf(record.description)}
    var decision by remember(record.id){mutableStateOf(record.review)}
    AlertDialog(onDismissRequest=dismiss,title={Text("Revisar sesión")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TypeSelector(types,type,{type=it})
        OutlinedTextField(note,{note=it.take(1200)},label={Text("Descripción revisada")},minLines=3)
        if(transcripts.isNotEmpty())TextButton(onClick={note=transcripts.joinToString(" "){it.text}.take(1200)}){Text("Usar dictado como descripción")}
        Text("Resumen de hasta 1,200 caracteres; los dictados completos se conservan aparte.",fontSize=11.sp,color=Muted)
        listOf("unreviewed","accepted","excluded").forEach{v->FilterChip(selected=decision==v,enabled=v!="accepted" || !record.technicalTest,onClick={decision=v},label={Text(reviewText(v))})}
    }},confirmButton={TextButton(onClick={save(type,note,decision)}){Text("Guardar revisión")}},dismissButton={TextButton(onClick=dismiss){Text("Cancelar")}})
}

@Composable private fun AudioPlayer(store:Store,record:TrainingRecord) {
    val file=remember(record.session){if(record.session.isNotBlank())store.safeFile(record.session,"audio.wav") else null}
    var player by remember {mutableStateOf<MediaPlayer?>(null)}
    var playing by remember {mutableStateOf(false)}
    var position by remember {mutableFloatStateOf(0f)}
    var length by remember {mutableFloatStateOf(1f)}
    var error by remember {mutableStateOf("")}
    DisposableEffect(record.id){onDispose{player?.release();player=null}}
    LaunchedEffect(player){while(isActive){player?.let{position=runCatching{it.currentPosition/1000f}.getOrDefault(0f);playing=runCatching{it.isPlaying}.getOrDefault(false)};delay(250)}}
    SurfaceCard {
        Eyebrow("AUDIO ORIGINAL DEL RELOJ")
        if(record.verified && file?.exists()==true) {
            Text("PCM · mono · 16 kHz",fontSize=12.sp,color=Muted)
            Slider(value=position.coerceIn(0f,length),onValueChange={position=it;player?.seekTo((it*1000).toInt())},valueRange=0f..length)
            Text("${num(position.toDouble(),1)} / ${num(if(player!=null)length.toDouble() else record.audioSeconds,1)} s",fontFamily=Mono,fontSize=12.sp)
            OutlinedButton(onClick={runCatching {
                if(player==null)player=MediaPlayer().also{it.setDataSource(file.absolutePath);it.prepare();length=(it.duration/1000f).coerceAtLeast(1f)}
                if(playing)player?.pause() else player?.start();playing=!playing
            }.onFailure{error=it.message.orEmpty()}}){Text(if(playing)"Pausar audio" else "Escuchar audio")}
        } else Text(when {record.audioState in setOf("failed","empty")->"La captura de audio no fue válida. Revisa el estado de la sesión.";record.audioRequested->"El audio aparecerá cuando termine la copia verificada desde el reloj.";else->"No se solicitó audio para esta sesión."},fontSize=12.sp,color=Muted)
        if(error.isNotBlank())Text(error,color=Amber,fontSize=12.sp)
    }
}

@Composable private fun TrainingMarkers(store:Store,session:String) {
    var marks by remember {mutableStateOf<List<Sample>>(emptyList())}
    LaunchedEffect(session){while(isActive){marks=withContext(Dispatchers.IO){if(session.isBlank())emptyList() else store.rawPage(session,"marcadores",0,Long.MAX_VALUE,0,500)};delay(1500)}}
    SurfaceCard {Eyebrow("MARCAS Y REFERENCIAS");marks.forEach{m->Text(time(m.wall,"HH:mm:ss.SSS")+" · "+m.values["event"]+" / "+m.values["phase"],fontSize=12.sp,color=if(m.values["phase"]=="CANDIDATE")Amber else Muted)};if(marks.isEmpty())Text("Sin marcas recibidas.",fontSize=12.sp,color=Muted)}
}

@Composable internal fun TrainingSignals(store:Store,session:String,live:Boolean) {
    var index by remember {mutableIntStateOf(0)}
    var viewport by remember(session){mutableStateOf<TimeWindow?>(null)}
    var points by remember {mutableStateOf<List<Sample>>(emptyList())}
    var first by remember {mutableLongStateOf(0L)}
    var last by remember {mutableLongStateOf(1L)}
    var window by remember {mutableStateOf(TimeWindow(0,1))}
    var point by remember {mutableStateOf<Sample?>(null)}
    var about by remember {mutableStateOf(false)}
    var raw by remember {mutableStateOf<RawQuery?>(null)}
    var detections by remember {mutableStateOf<List<EventInfo>>(emptyList())}
    var selectedDetection by remember {mutableStateOf<EventInfo?>(null)}
    val metric=metrics[index]
    LaunchedEffect(session,index,viewport,live){while(isActive){withContext(Dispatchers.IO){
        val s=store.sessions().firstOrNull{it.id==session}
        val end=if(live)System.currentTimeMillis()-PhoneLive.state.value.clockDelta else s?.end?.takeIf{it>0} ?: store.latestLive(session,"acelerometro")?.wall ?: 1L
        val start=s?.start ?: 0L
        val w=(viewport ?: TimeWindow(maxOf(start,end-60000),end.coerceAtLeast(start+1))).constrain(start,end.coerceAtLeast(start+1))
        val result=if(session.isBlank())emptyList() else store.plot(session,metric.stream,w.from,w.to)
        val marks=DetectionStore(store).events(session).filter{it.optString("label")=="confirmed"}.map {r->
            val e=JSONObject(r.getString("evidence"));EventInfo(r.getString("id"),e.optLong("start_wall_ms"),e.optLong("end_wall_ms"),session,"CONSUMO_CONFIRMADO","reference","confirmed","Confirmado por la persona",e.toString())
        }
        withContext(Dispatchers.Main){first=start;last=end.coerceAtLeast(start+1);window=w;points=result;detections=marks}
    };delay(1500)}}
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        ChartExplorer(metric,index,{index=it},points,detections,window,first,last,live,{viewport=it},{zoom,pan,anchor->viewport=window.transform(zoom,pan,anchor,first,last)},
            {viewport=null},{point=it},{selectedDetection=it},{about=true})
        if(session.isNotBlank()) {MinuteBrowser(store,session){raw=it};TextButton(onClick={raw=RawQuery(session,metric.stream,window.from,window.to)}){Text("Ver mediciones individuales")}}
    }
    selectedDetection?.let {e->AlertDialog(onDismissRequest={selectedDetection=null},title={Text("Consumo confirmado")},text={Text("${time(e.time)} – ${time(e.end)}. Puedes cambiar la calificación en Detecciones y etiquetas; la revisión anterior se conserva.")},confirmButton={TextButton(onClick={selectedDetection=null}){Text("Cerrar")}})}
    raw?.let{RawMeasurementsDialog(store,it){raw=null}}
    if(about)AlertDialog(onDismissRequest={about=false},title={Text(metric.label)},text={Text(sensorDescription(metric))},confirmButton={TextButton(onClick={about=false}){Text("Cerrar")}})
    point?.let{p->AlertDialog(onDismissRequest={point=null},title={Text(time(p.wall,"HH:mm:ss.SSS"))},text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())){Text(p.values.entries.joinToString("\n"){it.key+": "+it.value},fontFamily=Mono,fontSize=12.sp)}},confirmButton={TextButton(onClick={point=null}){Text("Cerrar")}})}
}
