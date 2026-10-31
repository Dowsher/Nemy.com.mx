package org.pipalab.phone

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.pipalab.core.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

data class LearningVersion(val id:String,val created:Long,val train:Int,val validation:Int,val score:Double,val coverage:Double,val eligible:Boolean)
data class LearningState(val busy:Boolean=false,val message:String="Revisando ejemplos…",val trained:Int=0,val reserved:Int=0,
    val skipped:Int=0,val counts:Map<String,Pair<Int,Int>> = emptyMap(),val active:String="",val automatic:Boolean=true,
    val versions:List<LearningVersion> = emptyList(),val suggestion:String="Sin modelo habilitado",val reasons:List<String> = emptyList())

/** All models and feature vectors stay in app-private storage. Labels always come from a human review. */
class LearningEngine private constructor(private val context:Context) {
    val state=MutableStateFlow(LearningState())
    private val db=Store.get(context)
    private val executor=Executors.newSingleThreadExecutor()
    private val queued=AtomicBoolean(false)
    private val directory=File(context.filesDir,"activity_models").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("learning",Context.MODE_PRIVATE)
    @Volatile private var active:ActivityModel?=null
    @Volatile private var labels:Map<String,String> = emptyMap()
    private var lastSignature=""
    private fun save(file:File,j:JSONObject){val atomic=AtomicFile(file);val out=atomic.startWrite();try{out.write(j.toString(2).toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}}
    private fun digest(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){ "%02x".format(it) }
    fun refresh(force:Boolean=false) {
        if(!queued.compareAndSet(false,true))return
        executor.execute {try{rebuild(force)}catch(e:Exception){active=null;state.value=state.value.copy(busy=false,active="",message="Aprendizaje pendiente: "+e.message)}finally{queued.set(false)}}
    }
    private fun rows(session:String,stream:String):List<Sample> {
        val result=mutableListOf<Sample>()
        db.readableDatabase.rawQuery("SELECT name FROM files WHERE session=? AND name LIKE ? ORDER BY name",arrayOf(session,stream+"%.csv")).use{c->while(c.moveToNext()){
            val file=db.safeFile(session,c.getString(0));if(!file.exists())continue
            file.reader().use { reader -> val iterator=Csv.rows(reader).iterator();if(!iterator.hasNext())return@use
                val keys=iterator.next();while(iterator.hasNext()){val values=iterator.next();if(values.size!=keys.size)continue
                    val map=keys.zip(values).toMap();val elapsed=map["t_elapsed_ns"]?.toLongOrNull() ?: continue
                    result.add(Sample(map["t_wall_ms"]?.toLongOrNull() ?: 0,elapsed,map))}
            }
        }}
        return result.distinctBy{it.elapsed}.sortedBy{it.elapsed}
    }
    @Synchronized private fun rebuild(force:Boolean){
        val records=TrainingStore(db).records()
        val accepted=records.filter{it.review=="accepted" && !it.technicalTest && it.status=="closed" && it.verified}
        labels=TrainingStore(db).types().associate{it.id to it.name}
        val signature=digest(ActivityModel.VERSION+accepted.sortedBy{it.id}.joinToString{"${it.id}:${it.typeId}:${it.original}:${it.session}"})
        if(!force && lastSignature==signature)return
        state.value=state.value.copy(busy=true,message="Aprendiendo de sesiones revisadas…")
        val train=mutableListOf<LearningExample>();val validation=mutableListOf<LearningExample>();val reasons=mutableListOf<String>()
        for(record in accepted){
            val motion=rows(record.session,"acelerometro")
            val worn=rows(record.session,"offbody").lastOrNull()?.values?.get("worn")?.toIntOrNull()
            if(worn!=1){reasons.add(record.activity+": contacto del reloj no confirmado");continue}
            val features=SessionFeatures.extract(motion,rows(record.session,"heart_rate"),rows(record.session,"temperatura"))
            if(features==null){reasons.add(record.activity+": movimiento insuficiente o con huecos");continue}
            val row=LearningExample(record.id,record.typeId,features)
            if(JSONObject(record.original).optBoolean("evaluation_only"))validation.add(row) else train.add(row)
        }
        val counts=(train+validation).map{it.label}.distinct().associate{label->(labels[label] ?: label) to (train.count{it.label==label} to validation.count{it.label==label})}
        var chosen=prefs.getString("active","").orEmpty()
        var chosenJson=readVersion(chosen)
        // An exclusion or corrected label immediately invalidates older models containing that example.
        if(chosenJson!=null && !validVersion(chosenJson,train)){chosen="";chosenJson=null;prefs.edit().remove("active").apply();active=null}
        val auto=prefs.getBoolean("automatic",true)
        if(train.map{it.label}.distinct().size>=2 && train.groupingBy{it.label}.eachCount().values.all{it>=2}){
            val model=ActivityModel.fit(train);val evaluation=model.evaluate(validation);val eligible=model.eligible(validation)
            val id=signature.take(20);val file=File(directory,"$id.json")
            if(!file.exists())save(file,JSONObject().put("schema","visio-local-learner-v1").put("algorithm",ActivityModel.VERSION).put("id",id)
                .put("created_ms",System.currentTimeMillis()).put("signature",signature).put("training",examples(train)).put("validation",examples(validation))
                .put("evaluation",evaluationJson(evaluation)).put("eligible",eligible).put("labels",JSONObject(labels)).put("feature_names",JSONArray(ActivityModel.names)))
            if(auto && eligible){
                val oldScore=chosenJson?.takeIf{validVersion(it,train)}?.let{ActivityModel.fit(parseExamples(it.getJSONArray("training"))).evaluate(validation).balancedAccuracy} ?: -1.0
                if(evaluation.balancedAccuracy>=oldScore){chosen=id;chosenJson=readVersion(id);prefs.edit().putString("active",id).apply()}
            }
        }
        // A changed validation label may make an older selected version ineligible too.
        if(chosenJson!=null){val model=ActivityModel.fit(parseExamples(chosenJson.getJSONArray("training")))
            if(model.eligible(validation))active=model else{active=null;chosen="";prefs.edit().remove("active").apply()}}
        else active=null
        val versions=versions()
        lastSignature=signature
        val message=when{accepted.isEmpty()->"Revisa y acepta sesiones para comenzar. No se aprende de etiquetas pendientes."
            train.map{it.label}.distinct().size<2->"Faltan al menos dos tipos de actividad con ejemplos válidos."
            active==null->"Modelo en preparación. Para sugerencias: 5 sesiones de entrenamiento y 2 de evaluación por tipo; evaluación suficiente."
            else->"Modelo local habilitado para sugerencias experimentales. Nunca confirma una actividad por sí solo."}
        state.value=state.value.copy(busy=false,message=message,trained=train.size,reserved=validation.size,skipped=records.size-accepted.size+reasons.size,
            counts=counts,active=chosen,automatic=auto,versions=versions,reasons=reasons.distinct(),suggestion=if(active==null)"No sé: todavía falta evidencia" else "Esperando 30 segundos de señal nueva")
        save(File(directory,"state.json"),JSONObject().put("active",chosen).put("automatic",auto).put("signature",signature).put("train_count",train.size).put("validation_count",validation.size).put("message",message))
    }
    private fun validVersion(j:JSONObject,current:List<LearningExample>):Boolean=runCatching{
        parseExamples(j.getJSONArray("training")).all{old->current.any{it.id==old.id && it.label==old.label && it.features==old.features}}
    }.getOrDefault(false)
    private fun readVersion(id:String):JSONObject?=if(!id.matches(Regex("[a-f0-9]{20}")))null else runCatching{JSONObject(File(directory,"$id.json").readText())}.getOrNull()
    private fun versions()=directory.listFiles()?.filter{it.name.matches(Regex("[a-f0-9]{20}\\.json"))}?.mapNotNull{file->runCatching{
        val j=JSONObject(file.readText());val e=j.getJSONObject("evaluation");LearningVersion(j.getString("id"),j.getLong("created_ms"),j.getJSONArray("training").length(),j.getJSONArray("validation").length(),e.getDouble("balanced_accuracy"),e.getDouble("coverage"),j.getBoolean("eligible"))
    }.getOrNull()}?.sortedByDescending{it.created} ?: emptyList()
    fun automatic(value:Boolean){prefs.edit().putBoolean("automatic",value).apply();refresh(true)}
    fun select(id:String){
        // Validation/revocation is rechecked by rebuild; no old model silently restores excluded examples.
        prefs.edit().putBoolean("automatic",false).putString("active",id).apply();lastSignature="";refresh(true)
    }
    fun live(){
        val model=active ?: return;val link=PhoneLive.state.value
        if(!link.connected || !link.recording || System.currentTimeMillis()-link.lastData>10000){state.value=state.value.copy(suggestion="No sé: no hay señal reciente");return}
        val end=System.currentTimeMillis()-link.clockDelta
        val motion=LiveSamples.points(link.session,"acelerometro",end-30000,end,1000)
        if(motion.size<500 || motion.last().wall-motion.first().wall<25000){state.value=state.value.copy(suggestion="Esperando 30 segundos de señal continua");return}
        val worn=LiveSamples.latest(link.session,"offbody")?.values?.get("worn")?.toIntOrNull()
        if(worn!=1){state.value=state.value.copy(suggestion="No sé: revisa el contacto del reloj");return}
        val features=SessionFeatures.extract(motion,LiveSamples.points(link.session,"heart_rate",end-30000,end),LiveSamples.points(link.session,"temperatura",end-30000,end))
        val guess=features?.let{model.predict(it)}
        val text=guess?.label?.let{"Se parece a: "+(labels[it] ?: it)+" · sugerencia experimental"} ?: "No sé: señal insuficiente, ambigua o diferente a los ejemplos"
        state.value=state.value.copy(suggestion=text)
    }
    @Synchronized fun export():JSONObject=JSONObject().put("state",runCatching{JSONObject(File(directory,"state.json").readText())}.getOrDefault(JSONObject()))
        .put("versions",JSONArray(directory.listFiles()?.filter{it.name.matches(Regex("[a-f0-9]{20}\\.json"))}?.map{JSONObject(it.readText())} ?: emptyList<JSONObject>()))
    private fun examples(rows:List<LearningExample>)=JSONArray(rows.map{JSONObject().put("id",it.id).put("label",it.label).put("features",JSONArray(it.features.map{v->v ?: JSONObject.NULL}))})
    private fun parseExamples(rows:JSONArray)=(0 until rows.length()).map{val j=rows.getJSONObject(it);val f=j.getJSONArray("features");LearningExample(j.getString("id"),j.getString("label"),(0 until f.length()).map{i->if(f.isNull(i))null else f.getDouble(i)})}
    private fun evaluationJson(e:LearningEvaluation)=JSONObject().put("total",e.total).put("answered",e.answered).put("correct",e.correct)
        .put("balanced_accuracy",e.balancedAccuracy).put("coverage",e.coverage).put("recalls",JSONObject(e.recalls)).put("confusion",JSONObject(e.confusion))
    companion object { @Volatile private var instance:LearningEngine?=null
        fun get(context:Context)=instance ?: synchronized(this){instance ?: LearningEngine(context.applicationContext).also{instance=it}}
    }
}

internal object SessionFeatures {
    fun extract(motion:List<Sample>,pulse:List<Sample>,temperature:List<Sample>):List<Double?>? {
        val rows=motion.distinctBy{it.elapsed}.sortedBy{it.elapsed}.mapNotNull{s->
            val xyz=listOf("x_m_s2","y_m_s2","z_m_s2").map{s.values[it]?.toDoubleOrNull()}
            if(xyz.any{it==null || !it.isFinite()})null else s.elapsed to xyz.map{it!!}}
        if(rows.size<200)return null
        val duration=(rows.last().first-rows.first().first)/1e9
        if(duration<10 || rows.size/duration<18 || rows.zipWithNext().any{(a,b)->b.first-a.first>1_000_000_000})return null
        val vectors=rows.map{it.second};val mean=(0..2).map{i->vectors.map{it[i]}.average()}
        val norms=vectors.map{v->sqrt(v.sumOf{it*it})};val normMean=norms.average()
        val dynamics=vectors.map{v->sqrt((0..2).sumOf{i->(v[i]-mean[i]).pow(2)})}
        val jerks=rows.zipWithNext().mapNotNull{(a,b)->val dt=(b.first-a.first)/1e9;if(dt !in .01..0.2)null else sqrt((0..2).sumOf{i->((b.second[i]-a.second[i])/dt).pow(2)})}
        if(jerks.isEmpty())return null
        val hr=pulse.filter{it.values["status"]=="1"}.mapNotNull{it.values["bpm"]?.toDoubleOrNull()?.takeIf{v->v in 30.0..240.0}}
        val skin=temperature.filter{it.values["status"]=="0"}.mapNotNull{it.values["skin_c"]?.toDoubleOrNull()?.takeIf{v->v in 10.0..50.0}}
        val hrMean=hr.takeIf{it.size>=5}?.average()
        val sorted=norms.sorted()
        return listOf(sqrt(dynamics.sumOf{it*it}/dynamics.size),sqrt(norms.sumOf{(it-normMean).pow(2)}/norms.size),sqrt(jerks.sumOf{it*it}/jerks.size),
            sorted[(sorted.size*.95).toInt().coerceAtMost(sorted.lastIndex)],mean[0]/9.80665,mean[1]/9.80665,mean[2]/9.80665,dynamics.count{it>1.2}.toDouble()/dynamics.size,
            hrMean,hrMean?.let{m->sqrt(hr.sumOf{(it-m).pow(2)}/hr.size)},skin.takeIf{it.isNotEmpty()}?.average())
    }
}
