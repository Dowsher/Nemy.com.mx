package org.pipalab.phone

import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

data class DictationState(val available:Boolean=false,val listening:Boolean=false,val partial:String="",val message:String="Comprobando dictado local…",val language:String="es-MX")

/** Phone-only on-device recognition. No cloud fallback; transcripts remain annotations awaiting review. */
class TrainingDictation(private val context:Context,private val db:TrainingStore) : RecognitionListener {
    val state=MutableStateFlow(DictationState())
    private val handler=Handler(Looper.getMainLooper())
    private var recognizer:SpeechRecognizer?=null
    private var enabled=false
    private var busy=false
    private var capture=""
    private var started=0L
    private var ended=0L
    private var delta=0L
    private var partial=""
    init {
        if(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            runCatching { recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also{it.setRecognitionListener(this)} }
                .onSuccess {state.value=DictationState(true,message="Dictado local disponible · requiere permiso de micrófono");checkLanguage()}
                .onFailure {state.value=DictationState(message="Dictado local no disponible: "+it.message)}
        } else state.value=DictationState(message="Este teléfono no ofrece reconocimiento local. Puedes escribir la descripción.")
    }
    private fun request()=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,state.value.language).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true).putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,1)
    private fun checkLanguage() {
        if(Build.VERSION.SDK_INT>=33) recognizer?.checkRecognitionSupport(request(),context.mainExecutor,object:RecognitionSupportCallback {
            override fun onSupportResult(support:RecognitionSupport) {
                val spanish=support.installedOnDeviceLanguages.firstOrNull{it.startsWith("es",true)}
                if(spanish!=null) state.value=state.value.copy(language=spanish,message="Dictado local · $spanish")
                else state.value=state.value.copy(message="El modelo español puede necesitar descarga. Prueba dictar o solicita el modelo.")
            }
            override fun onError(error:Int) {state.value=state.value.copy(message="Dictado local: disponibilidad de idioma sin confirmar ($error)")}
        })
    }
    fun requestModel() {
        if(Build.VERSION.SDK_INT>=33) {
            runCatching {recognizer?.triggerModelDownload(request());state.value=state.value.copy(message="Modelo solicitado al servicio de Android. Revisa su descarga; aún no está confirmada.")}
                .onFailure{state.value=state.value.copy(message="No se pudo solicitar el modelo: "+it.message)}
        }
    }
    fun start(id:String) {
        if(!state.value.available || busy) return
        capture=id;enabled=true;listen()
    }
    private fun listen() {
        if(!enabled || busy) return
        started=System.currentTimeMillis();ended=0;delta=PhoneLive.state.value.clockDelta;partial="";busy=true
        state.value=state.value.copy(listening=true,partial="",message="Escuchando en el teléfono · describe tu actividad")
        runCatching { recognizer?.startListening(request()) }.onFailure{busy=false;enabled=false;state.value=state.value.copy(listening=false,message="No se pudo dictar: "+it.message)}
    }
    fun stop() { val wasActive=enabled || busy;enabled=false;handler.removeCallbacksAndMessages(null);if(busy)recognizer?.stopListening();if(wasActive)state.value=state.value.copy(listening=false,message="Finalizando descripción…") }
    fun destroy() {
        enabled=false;handler.removeCallbacksAndMessages(null)
        if(partial.isNotBlank() && capture.isNotBlank()) db.transcript(capture,started,if(ended>0)ended else System.currentTimeMillis(),delta,partial,true)
        partial="";recognizer?.cancel();recognizer?.destroy();recognizer=null;busy=false
    }
    override fun onReadyForSpeech(params:Bundle?) {}
    override fun onBeginningOfSpeech() { started=System.currentTimeMillis() }
    override fun onRmsChanged(rmsdB:Float) {}
    override fun onBufferReceived(buffer:ByteArray?) {}
    override fun onEndOfSpeech() { ended=System.currentTimeMillis();state.value=state.value.copy(message="Transcribiendo localmente…") }
    override fun onError(error:Int) {
        busy=false
        if(partial.isNotBlank()) db.transcript(capture,started,if(ended>0)ended else System.currentTimeMillis(),delta,partial,true)
        partial=""
        val retry=error in setOf(SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        if(!retry) enabled=false
        state.value=state.value.copy(listening=enabled,partial="",message=when(error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Falta permiso de micrófono del teléfono."
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Modelo español no disponible. Solicita el modelo local o escribe la descripción."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Micrófono o dictado ocupado. Vuelve a intentarlo."
            SpeechRecognizer.ERROR_NO_MATCH,SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if(enabled) "Esperando tu siguiente frase…" else "Sin frase reconocida."
            else -> "Dictado interrumpido ($error). La captura del reloj continúa."
        })
        if(enabled)handler.postDelayed({listen()},500)
    }
    override fun onResults(results:Bundle?) {
        busy=false
        val text=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        if(text.isNotBlank())db.transcript(capture,started,if(ended>0)ended else System.currentTimeMillis(),delta,text,false)
        partial="";state.value=state.value.copy(listening=enabled,partial="",message=if(text.isBlank())"Sin texto reconocido" else "Frase guardada; revísala al finalizar.")
        if(enabled)handler.postDelayed({listen()},350)
    }
    override fun onPartialResults(results:Bundle?) { partial=results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty();state.value=state.value.copy(partial=partial) }
    override fun onEvent(eventType:Int,params:Bundle?) {}
}
