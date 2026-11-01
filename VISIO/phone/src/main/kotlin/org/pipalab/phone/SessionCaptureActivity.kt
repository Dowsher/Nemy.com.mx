package org.pipalab.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.pipalab.core.SessionGate
import org.pipalab.core.SessionVoice
import java.util.concurrent.Executors

/** The phone camera is intentionally foreground-only. Leaving the screen closes both captures. */
class SessionCaptureActivity:ComponentActivity() {
    private lateinit var store:Store
    private lateinit var media:SessionMedia
    private lateinit var row:TrainingRecord
    private lateinit var voice:TrainingDictation
    private val gate=SessionGate()
    private var camera:VideoCapture<Recorder>?=null
    private var provider:ProcessCameraProvider?=null
    private var recording:Recording?=null
    private var preview:PreviewView?=null
    private var cameraReady by mutableStateOf(false)
    private var cameraPermission by mutableStateOf(false)
    private var message by mutableStateOf("Preparando cámara frontal…")
    private var phase by mutableStateOf(SessionGate.Phase.READY)
    private var elapsed by mutableLongStateOf(0)
    private var spokenNotes by mutableStateOf(true)
    private var demo by mutableStateOf(false)
    private var startNs=0L
    private var seenWatch=false
    private var startSent=false
    private var stopReason="user"
    private var closing=false
    private var lastProbe=0L
    private val disk=Executors.newSingleThreadExecutor()
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraPermission=granted(Manifest.permission.CAMERA)
        if(!cameraPermission)message="Permite la cámara para grabar esta sesión."
        if(granted(Manifest.permission.RECORD_AUDIO) && ::voice.isInitialized)voice.start(row.id)
    }
    private fun granted(p:String)=checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        store=Store.get(this);media=SessionMedia(store)
        val id=intent.getStringExtra("id").orEmpty()
        row=TrainingStore(store).records().find {it.id==id} ?: run {finish();return}
        // Never resume a video or reuse its id after process recreation.
        if(media.video(id).exists() || savedInstanceState!=null) {finish();return}
        media.create(id)
        voice=TrainingDictation(this,TrainingStore(store),{command,text->
            media.event(id,"voice_command",text)
            when(command) {SessionVoice.Command.START->startCapture();SessionVoice.Command.STOP->stopCapture("voice_corte")}
        },{spokenNotes && phase==SessionGate.Phase.RECORDING})
        cameraPermission=granted(Manifest.permission.CAMERA)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(phase in setOf(SessionGate.Phase.STARTING,SessionGate.Phase.RECORDING,SessionGate.Phase.SAVING)) {
                    closing=true;stopCapture("back")
                } else finish()
            }
        })
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Lime,secondary=Mint,background=Ink,surface=Panel,onPrimary=Ink)) {
            val link by PhoneLive.state.collectAsState()
            val speech by voice.state.collectAsState()
            val esp by EspLive.state.collectAsState()
            Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Eyebrow("VISIO / NUEVA SESIÓN DE EJEMPLO")
                Text(row.activity,fontSize=27.sp,color=Lime)
                Text(row.description,color=Muted,fontSize=13.sp)
                if(cameraPermission) AndroidView(factory={ctx->PreviewView(ctx).also {preview=it;bindCamera(it)}},modifier=Modifier.fillMaxWidth().height(260.dp))
                else Button(onClick={permissions.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))}) {Text("Permitir cámara y comandos de voz")}
                SurfaceCard {
                    Text(when(phase){SessionGate.Phase.READY->"DI «ACCIÓN» PARA INICIAR";SessionGate.Phase.STARTING->"INICIANDO CAPTURA";SessionGate.Phase.RECORDING->"● GRABANDO · DI «CORTE»";SessionGate.Phase.SAVING->"GUARDANDO VIDEO";SessionGate.Phase.FINISHED->"VIDEO GUARDADO / REVISAR SESIÓN"},color=Lime,fontSize=18.sp)
                    Text(message,color=Muted,fontSize=13.sp)
                    Text("${elapsed/1000} s · límite ${row.durationSeconds} s",fontFamily=Mono,color=Lime)
                    Text(if(link.connected) (if(link.remoteReady)"Reloj listo" else "Activa «Listo para el teléfono» en Visio del reloj") else "Reloj desconectado",color=if(link.connected && link.remoteReady)Mint else Amber,fontSize=12.sp)
                    Text("Video frontal SD (~480p), sin audio del teléfono. Audio y cuatro señales se guardan en el reloj y se copian al terminar.",fontSize=12.sp,color=Muted)
                    if(phase==SessionGate.Phase.READY) Button(onClick=::startCapture,enabled=cameraReady && link.connected && link.remoteReady && link.protocol>=4,modifier=Modifier.fillMaxWidth()) {Text("ACCIÓN · iniciar")}
                    if(phase in setOf(SessionGate.Phase.STARTING,SessionGate.Phase.RECORDING)) Button(onClick={stopCapture("button")},modifier=Modifier.fillMaxWidth()) {Text("CORTE · detener y guardar")}
                    if(phase==SessionGate.Phase.FINISHED) Button(onClick={finish()},modifier=Modifier.fillMaxWidth()) {Text("Ir a mis sesiones")}
                }
                if(phase !in setOf(SessionGate.Phase.SAVING,SessionGate.Phase.FINISHED)) SurfaceCard {
                    Eyebrow(if(speech.listening) "● ESCUCHA DE VOZ ACTIVA / TELÉFONO" else "ESCUCHA DE VOZ APAGADA")
                    Text(speech.message,color=Muted,fontSize=12.sp)
                    if(speech.partial.isNotBlank())Text(speech.partial,color=Mint)
                    Row {
                        OutlinedButton(onClick={if(speech.listening)voice.stop() else if(granted(Manifest.permission.RECORD_AUDIO))voice.start(row.id) else permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))}) {Text(if(speech.listening)"Apagar escucha" else "Activar escucha")}
                        TextButton(onClick={voice.requestModel()}){Text("Idioma")}
                    }
                    Row {Checkbox(checked=spokenNotes,onCheckedChange={spokenNotes=it});Text("Transcribir también mis descripciones durante la captura",color=Muted,fontSize=12.sp,modifier=Modifier.weight(1f))}
                    Text("Di la palabra sola y espera la confirmación en pantalla. Usa los botones si el teléfono no la reconoce. El modelo de español debe estar instalado.",color=Muted,fontSize=11.sp)
                }
                if(phase==SessionGate.Phase.RECORDING) {
                    Text("Reloj: ${link.audioState} · audio ${num(link.audioSeconds,1)} s",color=if(link.audioState=="recording" && !link.audioSilenced)Lime else Amber)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        org.pipalab.core.ActivityTypes.markers.forEach {(id,label)->OutlinedButton(enabled=link.connected && !link.stopping,onClick={command("TRAINING_POINT",id);media.event(row.id,"marker_requested",id)}){Text("+ $label",fontSize=12.sp)}}
                    }
                    SurfaceCard {
                        Row {
                            Text("Demo sonido → LED",color=Mint,modifier=Modifier.weight(1f))
                            Switch(checked=demo,enabled=demo || (esp.ready && link.connected && link.audioState=="recording"),onCheckedChange={enabled->
                                demo=enabled;getSharedPreferences("prefs",MODE_PRIVATE).edit().putBoolean("audio_demo",enabled).apply()
                            })
                        }
                        Text(if(esp.ready)"Responde a impulsos breves; no identifica exclusivamente chasquidos o encendedores."
                            else "Conecta la placa desde Ajustes antes de crear la sesión para usar esta demo.",color=Muted,fontSize=11.sp)
                    }
                    TrainingSignals(store,link.session,link.recording)
                }
            }
        } }
        if(!cameraPermission || !granted(Manifest.permission.RECORD_AUDIO)) permissions.launch(arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO))
        else voice.start(id)
        lifecycleScope.launch {
            while(isActive) {
                val now=SystemClock.elapsedRealtimeNanos()
                val link=PhoneLive.state.value
                if(!link.connected || !link.recording)demo=false
                PhoneLive.clock.best(now)?.let { if(it.phoneReceive!=lastProbe) {media.probe(id,it);lastProbe=it.phoneReceive} }
                if(phase in setOf(SessionGate.Phase.STARTING,SessionGate.Phase.RECORDING)) {
                    elapsed=(now-startNs)/1_000_000
                    if(link.connected && link.trainingId==id && link.recording) {seenWatch=true;gate.acknowledged();phase=gate.phase;message="Video activo. Revisa calidad y llegada de señales abajo."}
                    val state=withContext(Dispatchers.IO){TrainingStore(store).records().find{it.id==id}?.status}
                    when {
                        state in setOf("not_ready","expired","busy","unsupported","cancelled","pending_other") -> stopCapture("watch_rejected:$state")
                        !seenWatch && now-startNs>15_000_000_000L -> stopCapture("watch_start_unconfirmed")
                        seenWatch && link.connected && !link.recording && link.statusReceivedNs>startNs && link.trainingId==id -> stopCapture("watch_stopped")
                        elapsed>=row.durationSeconds*1000L -> stopCapture("duration")
                    }
                    if(!link.connected)message="Enlace interrumpido: el reloj conserva su copia local. CORTE se reenviará al reconectar."
                }
                delay(500)
            }
        }
    }
    private fun bindCamera(view:PreviewView) {
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({runCatching {
            val p=future.get();provider=p
            check(p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {"No hay cámara frontal disponible"}
            val recorder=Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD,FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(1_000_000).build()
            val video=VideoCapture.withOutput(recorder)
            val preview=Preview.Builder().build().also {it.setSurfaceProvider(view.surfaceProvider)}
            p.unbindAll();p.bindToLifecycle(this,CameraSelector.DEFAULT_FRONT_CAMERA,preview,video)
            camera=video;cameraReady=true;message="Encuadra la actividad y di «acción». No se está grabando todavía."
        }.onFailure {cameraReady=false;message="No se pudo abrir la cámara: ${it.message}"}},mainExecutor)
    }
    private fun startCapture() {
        if(phase!=SessionGate.Phase.READY)return
        val live=PhoneLive.state.value
        val now=SystemClock.elapsedRealtimeNanos()
        if(!cameraReady || !live.connected || !live.remoteReady || live.protocol<4 || now-live.statusReceivedNs>10_000_000_000L) {message="Falta cámara o reloj listo. No se inició la grabación.";return}
        val probe=PhoneLive.clock.best(now) ?: run {message="Sincronizando reloj; vuelve a decir acción en unos segundos.";return}
        if(media.dir(row.id).usableSpace<250L*1024*1024) {message="Libera al menos 250 MB para esta sesión.";return}
        if(!gate.start())return
        phase=gate.phase;startNs=now;media.probe(row.id,probe)
        TrainingStore(store).status(row.id,"requested")
        media.event(row.id,"start_requested")
        media.update(row.id){it.put("state","starting").put("phone_start_requested_ns",now)}
        runCatching {
            val options=FileOutputOptions.Builder(media.video(row.id)).setDurationLimitMillis(row.durationSeconds*1000L).setFileSizeLimit(200L*1024*1024).build()
            recording=camera!!.output.prepareRecording(this,options).start(mainExecutor) {event->
                val callbackNs=SystemClock.elapsedRealtimeNanos()
                when(event) {
                    is VideoRecordEvent.Start -> {
                        if(phase==SessionGate.Phase.STARTING) {
                            media.update(row.id){it.put("state","recording").put("video_origin_phone_ns",callbackNs).put("video_start_callback_ns",callbackNs).put("video_callback_delay_unbounded",true)}
                            startSent=true;command("TRAINING_START")
                            message="Video iniciado; esperando confirmación de audio y sensores del reloj…"
                        } else recording?.stop()
                    }
                    is VideoRecordEvent.Status -> if(phase in setOf(SessionGate.Phase.STARTING,SessionGate.Phase.RECORDING)) {
                        // Earliest observed callback-derived origin reduces positive callback latency, without claiming an exact camera timestamp.
                        val origin=callbackNs-event.recordingStats.recordedDurationNanos
                        media.update(row.id){it.put("video_origin_phone_ns",minOf(it.optLong("video_origin_phone_ns",origin),origin)).put("last_recorded_duration_ns",event.recordingStats.recordedDurationNanos)}
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording=null
                        if(phase !in setOf(SessionGate.Phase.SAVING,SessionGate.Phase.FINISHED))stopCapture("camera_finalized")
                        val error=if(event.hasError() && event.error!=VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) "CameraX ${event.error}: ${event.cause?.message.orEmpty()}" else ""
                        disk.execute {
                            val result=runCatching {media.finalize(row.id,error,stopReason)}
                            mainExecutor.execute {gate.finish();phase=gate.phase;message=if(error.isBlank() && result.isSuccess)"Video guardado. El audio y CSV se copiarán del reloj por Bluetooth; revisa los archivos pendientes en Sesiones." else "Revisa la captura: ${error.ifBlank {result.exceptionOrNull()?.message.orEmpty()}}";if(closing)finish()}
                            disk.shutdown()
                        }
                    }
                }
            }
        }.onFailure {error->media.update(row.id){it.put("state","interrupted").put("error",error.toString())};TrainingStore(store).status(row.id,"unconfirmed");gate.finish();phase=gate.phase;message="No se inició el video: ${error.message}"}
    }
    private fun command(action:String,label:String="") {startService(Intent(this,LinkService::class.java).setAction(action).putExtra("id",row.id).putExtra("label",label))}
    private fun stopCapture(reason:String) {
        if(!gate.stop())return
        phase=gate.phase;stopReason=reason;voice.stop()
        demo=false;getSharedPreferences("prefs",MODE_PRIVATE).edit().putBoolean("audio_demo",false).apply()
        media.event(row.id,"stop_requested",reason);media.update(row.id){it.put("state","saving").put("stop_reason",reason)}
        // Tombstone is sent even if camera's Start callback has not arrived; delayed starts cannot reopen this id.
        command("TRAINING_STOP")
        recording?.stop()
    }
    override fun onStop() {
        if(::voice.isInitialized)voice.stop()
        if(::row.isInitialized)stopCapture("phone_background")
        super.onStop()
    }
    override fun finish() {
        if(::row.isInitialized)setResult(RESULT_OK,Intent().putExtra("id",row.id))
        super.finish()
    }
    override fun onDestroy() {
        if(::voice.isInitialized)voice.destroy()
        recording?.stop();provider?.unbindAll()
        if(phase in setOf(SessionGate.Phase.READY,SessionGate.Phase.FINISHED))disk.shutdown()
        // Finalize callbacks can outlive the Activity after Android stops it. Keep their small disk executor alive until completion.
        super.onDestroy()
    }
}
