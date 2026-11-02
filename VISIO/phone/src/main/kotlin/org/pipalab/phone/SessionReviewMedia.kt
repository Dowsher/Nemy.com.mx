package org.pipalab.phone

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.abs

@Composable internal fun SessionReviewMedia(store:Store,record:TrainingRecord) {
    val context=LocalContext.current
    val media=remember {SessionMedia(store)}
    val meta=remember(record.id,record.status){media.read(record.id)}
    val file=remember(record.id){media.video(record.id)}
    val scope=rememberCoroutineScope()
    var message by remember {mutableStateOf("")}
    var video by remember {mutableStateOf<VideoView?>(null)}
    var audio by remember {mutableStateOf<MediaPlayer?>(null)}
    var position by remember {mutableFloatStateOf(0f)}
    var duration by remember {mutableFloatStateOf(1f)}
    var playing by remember {mutableStateOf(false)}
    var loaded by remember {mutableStateOf(false)}
    var signalText by remember {mutableStateOf("")}
    var quality by remember {mutableStateOf<List<String>>(emptyList())}
    val audioMeta=remember(record.id,record.verified) {runCatching {JSONObject(store.safeFile(record.session,"audio.json").readText())}.getOrDefault(JSONObject())}
    val aligned=meta.has("watch_minus_phone_ns") && meta.has("video_origin_phone_ns") && audioMeta.has("first_sample_elapsed_ns")
    val audioShiftMs=if(aligned) (meta.optLong("video_origin_phone_ns")+meta.optLong("watch_minus_phone_ns")-audioMeta.optLong("first_sample_elapsed_ns"))/1_000_000 else 0
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->
        if(uri!=null)scope.launch {message="Exportando esta sesión…";message=withContext(Dispatchers.IO) {
            runCatching {context.contentResolver.openOutputStream(uri)!!.use {Exporter.writeTraining(store,it,captureId=record.id)};"Sesión exportada con originales y manifiestos."}.getOrElse {"No se pudo exportar: ${it.message}"}
        }}
    }
    DisposableEffect(record.id) {onDispose {video?.stopPlayback();audio?.release();audio=null}}
    LaunchedEffect(record.id,record.verified) {
        quality=withContext(Dispatchers.IO) {listOf(metrics[0],metrics[3],metrics[1],metrics[2]).map {metric->
            var total=0;var valid=0
            store.readableDatabase.rawQuery("SELECT data FROM samples WHERE session=? AND stream=?",arrayOf(record.session,metric.stream)).use {c->
                while(c.moveToNext()) {
                    total++;val j=JSONObject(c.getString(0))
                    val fields=j.keys().asSequence().associateWith {j.optString(it)}
                    val channels=org.pipalab.core.SignalValues.channels(metric.stream,fields)
                    if(channels[metric.keys.first()].orEmpty().isNotEmpty())valid++
                }
            }
            "${metric.label}: $total muestras · $valid con valor válido"+(if(valid==0)" · FALTA SEÑAL VÁLIDA" else "")
        } }
    }
    LaunchedEffect(record.id,record.verified,aligned) {
        if(aligned && record.verified && audio==null) {
            val wav=store.safeFile(record.session,"audio.wav")
            if(wav.exists())runCatching {audio=MediaPlayer().also {it.setDataSource(wav.absolutePath);it.prepare()}}.onFailure {message="Audio no reproducible: ${it.message}"}
        }
    }
    LaunchedEffect(video,audio) {
        var lastSensor=0L
        while(isActive) {
            video?.let {v->
                if(loaded) {
                    position=v.currentPosition/1000f;playing=v.isPlaying
                    val target=v.currentPosition.toLong()+audioShiftMs
                    audio?.let {a->
                        if(playing && target>=0 && target<a.duration) {
                            if(abs(a.currentPosition-target)>250)a.seekTo(target.toInt())
                            if(!a.isPlaying)a.start()
                        } else if(a.isPlaying)a.pause()
                    }
                    if(aligned && abs(v.currentPosition.toLong()-lastSensor)>750) {
                        lastSensor=v.currentPosition.toLong()
                        val session=withContext(Dispatchers.IO){store.sessions().find {it.id==record.session}}
                        val sm=session?.let{JSONObject(it.meta)}
                        if(sm!=null) {
                            val watchNs=meta.optLong("video_origin_phone_ns")+meta.optLong("watch_minus_phone_ns")+v.currentPosition*1_000_000L
                            val wall=sm.optLong("start_wall_ms")+(watchNs-sm.optLong("start_elapsed_ns"))/1_000_000
                            signalText=withContext(Dispatchers.IO){listOf(metrics[0],metrics[3],metrics[1],metrics[2]).joinToString("\n") {metric->
                                val point=store.rawPage(record.session,metric.stream,wall-2000,wall+100,0,200).minByOrNull{abs(it.wall-wall)}
                                metric.label+": "+if(point==null) "sin muestra cercana" else metric.keys.joinToString(" / "){num(value(point,it),2)}+" "+metric.unit
                            }}
                        }
                    }
                }
            }
            delay(150)
        }
    }
    SurfaceCard {
        Eyebrow("VIDEO FRONTAL Y SESIÓN COMPLETA")
        if(file.exists() && meta.optBoolean("video_readable")) {
            Text("${meta.optJSONObject("video")?.optInt("width")} × ${meta.optJSONObject("video")?.optInt("height")} · video ${meta.optString("state")}",color=Muted,fontSize=12.sp)
            AndroidView(factory={ctx->VideoView(ctx).also {v->video=v;v.setVideoPath(file.absolutePath)
                v.setOnPreparedListener {duration=(v.duration/1000f).coerceAtLeast(1f);loaded=true;v.seekTo(1)}
                v.setOnErrorListener {_,what,extra->message="No se pudo reproducir ($what/$extra)";true}
                v.setOnCompletionListener {playing=false;audio?.pause()}
            }},modifier=Modifier.fillMaxWidth().height(270.dp))
            Slider(value=position.coerceIn(0f,duration),onValueChange={position=it;video?.seekTo((it*1000).toInt());audio?.let {a->a.seekTo(((it*1000).toLong()+audioShiftMs).coerceIn(0,a.duration.toLong()).toInt())}},valueRange=0f..duration,enabled=loaded)
            Text("${num(position.toDouble(),1)} / ${num(duration.toDouble(),1)} s",color=Mint,fontFamily=Mono)
            Button(enabled=loaded,onClick={if(playing){video?.pause();audio?.pause()}else video?.start();playing=!playing}){Text(if(playing)"Pausar" else "Reproducir sesión")}
            Text(if(aligned && audio!=null) "Audio del reloj alineado de forma aproximada. Incertidumbre de red ±${num(meta.optLong("clock_round_trip_ns")/2e6,1)} ms, más demora no medida de cámara/audio."
                else "Video sin sonido: falta el WAV o una referencia de sincronización. Puedes escuchar el audio por separado.",fontSize=11.sp,color=Amber)
            if(signalText.isNotBlank())Text("Lecturas cercanas al instante del video (±2 s)\n$signalText",fontSize=12.sp,color=Mint)
        } else Text(if(meta.length()==0)"Esta sesión anterior no incluye video." else "Video no disponible o interrumpido: ${meta.optString("error",meta.optString("state"))}",fontSize=12.sp,color=Muted)
        if(meta.length()>0)Text("Cierre: ${meta.optString("stop_reason","pendiente")}. Audio y sensores: ${if(record.verified)"copia verificada" else "copia pendiente"}.",color=Muted,fontSize=12.sp)
        quality.forEach {Text(it,color=if(it.contains("FALTA"))Amber else Mint,fontSize=12.sp)}
        Text("Una copia completa no garantiza buena señal. Revisa contacto, valores, audio y video antes de aceptar el ejemplo.",color=Muted,fontSize=11.sp)
        OutlinedButton(enabled=record.status !in setOf("recording","finishing") && meta.optString("state") !in setOf("starting","recording","saving"),onClick={export.launch("Visio-sesion-${record.id}.zip")},modifier=Modifier.fillMaxWidth()){Text("Exportar solo esta sesión · ZIP")}
        if(message.isNotBlank())Text(message,color=Amber,fontSize=12.sp)
    }
}
