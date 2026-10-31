package org.pipalab.phone

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.pipalab.core.*
import java.io.File

data class PersonalStatus(val positives:Int=0,val negatives:Int=0,val excluded:Int=0,val windows:Int=0,val ready:Boolean=false)
object PersonalLive { val state=MutableStateFlow(PersonalStatus()) }

/** Rebuilds only from human-reviewed events and original accelerometer rows. Private to this phone. */
class PersonalDetector(private val context:Context,private val store:Store) {
    private var signature=""
    var profile=MotionProfile();private set
    fun refresh(session:String,force:Boolean=false):MotionProfile {
        val sessions=store.sessions().associateBy{it.id}
        val target=sessions[session] ?: sessions.values.maxByOrNull{it.start} ?: return profile
        fun identity(s:SessionInfo):String {val m=JSONObject(s.meta);return listOf("participant","wrist","dominant_hand").joinToString("/"){m.optString(it)}}
        val same=sessions.values.filter{identity(it)==identity(target)}.map{it.id}.toSet()
        val events=store.events().filter{it.origin!="test" && it.state in listOf("confirmed","false_positive")}
        val revision=store.readableDatabase.rawQuery("SELECT MAX(time) FROM feedback",null).use{it.moveToFirst();it.getLong(0)}
        val next=identity(target)+":"+revision+":"+sessions.values.filter{it.id in same}.sumOf{it.count}
        if(!force && signature==next)return profile
        val templates=ArrayList<MotionTemplate>();var excluded=0
        for(e in events.take(200)) {
            if(e.session !in same){excluded++;continue}
            val f=JSONObject(e.features)
            if(e.origin=="manual" && !f.optBoolean("link_connected",false)){excluded++;continue}
            val from=if(e.origin=="manual")f.optLong("watch_wall_estimate_ms",e.time) else e.time
            val until=from+(e.end-e.time).coerceIn(0,180000)
            val contact=store.readableDatabase.rawQuery("SELECT wall,data FROM samples WHERE session=? AND stream='offbody' AND wall<=? ORDER BY wall",arrayOf(e.session,(until+30000).toString())).use{c->buildList {
                while(c.moveToNext())add(c.getLong(0) to JSONObject(c.getString(1)).optInt("worn",-1))
            }}
            val points=store.readableDatabase.rawQuery("SELECT elapsed,data,wall FROM samples WHERE session=? AND stream='acelerometro' AND wall BETWEEN ? AND ? ORDER BY elapsed LIMIT 30000",arrayOf(e.session,(from-30000).toString(),(until+30000).toString())).use{c->buildList {
                while(c.moveToNext()) {
                    if(contact.lastOrNull{it.first<=c.getLong(2)}?.second!=1)continue
                    val j=JSONObject(c.getString(1));add(MotionPoint(c.getLong(0),j.optDouble("x_m_s2"),j.optDouble("y_m_s2"),j.optDouble("z_m_s2")))
                }
            }}
            val windows=MotionFingerprint.windows(points)
            if(windows.isEmpty()){excluded++;continue}
            // Bound exemplars per event so a long event cannot dominate storage/processing.
            val stride=((windows.size+15)/16).coerceAtLeast(1)
            windows.filterIndexed{i,_->i%stride==0}.take(16).forEach{templates.add(MotionTemplate(e.id,e.state=="confirmed",it))}
        }
        profile=MotionProfile(templates);signature=next
        PersonalLive.state.value=PersonalStatus(profile.positiveGroups,profile.negativeGroups,excluded,templates.size,true)
        val data=JSONObject().put("version",GestureDetector.VERSION).put("identity",identity(target)).put("updated",System.currentTimeMillis())
            .put("positives",profile.positiveGroups).put("negatives",profile.negativeGroups).put("excluded",excluded)
            .put("accuracy_validated",false).put("templates",JSONArray().also{a->templates.forEach{a.put(JSONObject().put("event",it.group).put("positive",it.positive).put("values",JSONArray(it.values)))}})
        val file=AtomicFile(File(context.filesDir,"personal-detector-v2.json"));val output=file.startWrite()
        try {output.write(data.toString().toByteArray());file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
        return profile
    }
}
