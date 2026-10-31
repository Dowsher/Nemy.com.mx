package org.pipalab.core

import kotlin.math.*

/** Numeric channels only: identifiers, clocks and status codes are never averaged. */
object SignalValues {
    private fun array(text:String?)=text?.removePrefix("[")?.removeSuffix("]")?.split(',')?.map{it.trim().toDoubleOrNull()} ?: emptyList()
    fun channels(stream:String,fields:Map<String,String>):Map<String,List<Double>> {
        val result=linkedMapOf<String,List<Double>>()
        fun add(key:String,valid:Boolean=true) { result[key]=fields[key]?.toDoubleOrNull()?.takeIf{valid && it.isFinite()}?.let{listOf(it)} ?: emptyList() }
        when(stream) {
            "acelerometro" -> listOf("x_m_s2","y_m_s2","z_m_s2").forEach{add(it)}
            "giroscopio" -> listOf("x_rad_s","y_rad_s","z_rad_s").forEach{add(it)}
            "heart_rate" -> {
                add("bpm",fields["status"]=="1" && (fields["bpm"]?.toDoubleOrNull() ?: 0.0)>0)
                val quality=array(fields["ibi_status_json"])
                result["ibi_ms_json"]=array(fields["ibi_ms_json"]).mapIndexedNotNull{i,v->v?.takeIf{it.isFinite() && it>0 && quality.getOrNull(i)==0.0}}
            }
            "ppg" -> listOf("green","ir","red").forEach{add(it,fields[it+"_status"]=="0")}
            "temperatura" -> {add("skin_c",fields["status"]=="0");add("ambient_c",fields["status"]=="0")}
            "eda" -> add("conductance_us",fields["status"]=="0")
            "offbody" -> add("worn",fields["worn"] in listOf("0","1"))
            "bateria" -> {add("percent",(fields["percent"]?.toDoubleOrNull() ?: -1.0)>=0);add("temperature_c")}
        }
        return result
    }
}

data class ChannelStats(val count:Long,val mean:Double,val min:Double,val max:Double,val sd:Double)
class Moments {
    var n=0L;private set
    private var mean=0.0;private var m2=0.0;private var min=Double.POSITIVE_INFINITY;private var max=Double.NEGATIVE_INFINITY
    fun add(value:Double) {if(!value.isFinite())return;n++;val d=value-mean;mean+=d/n;m2+=d*(value-mean);min=min(min,value);max=max(max,value)}
    fun snapshot()=if(n==0L)null else ChannelStats(n,mean,min,max,sqrt((m2/n).coerceAtLeast(0.0)))
}
data class MinuteStats(val stream:String,val start:Long,val rows:Long,val first:Long,val last:Long,val channels:Map<String,ChannelStats>,val quality:Map<String,Long>)
/** Retains small per-minute accumulators; late sensor batches update the same bucket. */
class MinuteBook {
    private class Bucket(val stream:String,val start:Long) {
        var rows=0L;var emitted=-1L;var first=Long.MAX_VALUE;var last=0L
        val values=linkedMapOf<String,Moments>();val quality=linkedMapOf<String,Long>()
        fun snapshot()=MinuteStats(stream,start,rows,first,last,values.mapNotNull{(k,v)->v.snapshot()?.let{k to it}}.toMap(),quality.toMap())
    }
    private val buckets=linkedMapOf<Pair<String,Long>,Bucket>()
    fun add(stream:String,wall:Long,fields:Map<String,String>) {
        val channels=SignalValues.channels(stream,fields);if(channels.isEmpty())return
        val minute=Math.floorDiv(wall,60000)*60000
        val bucket=buckets.getOrPut(stream to minute){Bucket(stream,minute)}
        bucket.rows++;bucket.first=min(bucket.first,wall);bucket.last=max(bucket.last,wall)
        channels.forEach{(key,values)->val m=bucket.values.getOrPut(key){Moments()};values.forEach(m::add)}
        fields.filterKeys{it=="status" || it.endsWith("_status")}.forEach{(key,value)->val k="$key=$value";bucket.quality[k]=(bucket.quality[k] ?: 0)+1}
    }
    fun ready(now:Long,all:Boolean=false):List<MinuteStats> = buckets.values.filter{it.rows!=it.emitted && (all || it.start+75000<=now)}.map{it.emitted=it.rows;it.snapshot()}
    fun snapshots()=buckets.values.map{it.snapshot()}
}

data class TrainingRow(val stream:String,val sequence:Long,val elapsed:Long,val wall:Long,val values:List<Any?>)
data class TrainingWindow(val id:String,val kind:String,val start:Long,val end:Long,val availableFrom:Long)
/** Bounded memory holds the previous 210 s. Only labeled windows and periodic controls reach disk. */
class TrainingBuffer(private val startWall:Long,private val keep:(TrainingRow)->Unit,private val window:(TrainingWindow)->Unit) {
    private val buffer=ArrayDeque<TrainingRow>()
    private val saved=HashSet<Pair<String,Long>>()
    private val windows=ArrayList<TrainingWindow>()
    private var control=-1L
    fun add(row:TrainingRow) {
        buffer.addLast(row)
        while(buffer.isNotEmpty() && (row.wall-buffer.first().wall>210000 || buffer.size>30000)) {
            val old=buffer.removeFirst();saved.remove(old.stream to old.sequence)
        }
        windows.removeAll{it.end<row.wall-210000}
        val slot=(row.wall-startWall).coerceAtLeast(0)/300000
        if(slot!=control) {
            control=slot
            preserve("control_${startWall}_$slot","control_sin_etiquetar",startWall+slot*300000,startWall+slot*300000+10000)
        }
        if(windows.any{row.wall in it.start..it.end})save(row)
    }
    fun preserve(id:String,kind:String,from:Long,to:Long):TrainingWindow {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && to>=from && to-from<=600000)
        windows.firstOrNull{it.id==id}?.let{return it}
        val w=TrainingWindow(id,kind,from,to,buffer.minOfOrNull{it.wall} ?: from)
        windows.add(w);buffer.filter{it.wall in from..to}.forEach(::save);window(w);return w
    }
    private fun save(row:TrainingRow) {if(saved.add(row.stream to row.sequence))keep(row)}
}

data class TimeWindow(val from:Long,val to:Long) {
    val span get()=(to-from).coerceAtLeast(1)
    fun constrain(first:Long,last:Long):TimeWindow {
        val upper=max(last,first+1);val width=span.coerceAtMost(upper-first)
        val start=from.coerceIn(first,upper-width);return TimeWindow(start,start+width)
    }
    fun transform(zoom:Double,panFraction:Double,anchor:Double,first:Long,last:Long):TimeWindow {
        if(!zoom.isFinite() || !panFraction.isFinite() || !anchor.isFinite())return this
        val width=(span/zoom.coerceIn(.05,20.0)).toLong().coerceIn(1000,max(1000,last-first))
        val start=(from+(span-width)*anchor.coerceIn(0.0,1.0)-panFraction*span).toLong()
        return TimeWindow(start,start+width).constrain(first,last)
    }
}
