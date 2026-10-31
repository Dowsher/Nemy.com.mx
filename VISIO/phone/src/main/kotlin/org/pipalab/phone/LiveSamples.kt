package org.pipalab.phone

/** Five minutes in RAM, never silently converted into permanent raw history. */
object LiveSamples {
    private var session=""
    private val streams=mutableMapOf<String,java.util.TreeMap<Long,Sample>>()
    @Synchronized fun add(id:String,stream:String,rows:List<Sample>) {
        if(session!=id){session=id;streams.clear()}
        val values=streams.getOrPut(stream){java.util.TreeMap()}
        rows.forEach{values[it.elapsed]=it}
        val latest=values.lastEntry()?.value?.wall ?: return
        while(values.isNotEmpty() && ((values.firstEntry()?.value?.wall ?: latest)<latest-300000 || values.size>18000)) values.pollFirstEntry()
    }
    @Synchronized fun latest(id:String,stream:String)=if(id==session)streams[stream]?.lastEntry()?.value else null
    @Synchronized fun points(id:String,stream:String,from:Long,to:Long,max:Int=800):List<Sample> {
        if(id!=session)return emptyList()
        val rows=streams[stream]?.values?.filter{it.wall in from..to} ?: emptyList()
        val stride=((rows.size+max-1)/max).coerceAtLeast(1)
        return rows.filterIndexed{i,_->i%stride==0}
    }
}
