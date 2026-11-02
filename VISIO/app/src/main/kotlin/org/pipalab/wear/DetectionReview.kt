package org.pipalab.wear

import android.app.*
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.util.UUID

/** Durable local outbox: labels survive a disconnected phone and are retried until acknowledged. */
object DetectionReview {
    val pending=MutableStateFlow<JSONObject?>(null)
    fun load(c:Context) {pending.value=c.getSharedPreferences("detection_review",0).getString("pending",null)?.let{runCatching{JSONObject(it)}.getOrNull()}}
    @Synchronized fun receive(c:Context,j:JSONObject) {
        require(runCatching{UUID.fromString(j.getString("id"))}.isSuccess)
        val now=System.currentTimeMillis();if(j.optLong("expires_ms") !in now..now+125000)return
        val p=c.getSharedPreferences("detection_review",0)
        if(p.getString("last_id","")==j.getString("id"))return
        p.edit().putString("pending",j.toString()).putString("last_id",j.getString("id")).commit();pending.value=j
        val nm=c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("review","Preguntas de entrenamiento",NotificationManager.IMPORTANCE_HIGH))
        val open=PendingIntent.getActivity(c,401,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(401,Notification.Builder(c,"review").setSmallIcon(R.drawable.ic_record).setContentTitle("¿Fue un consumo?")
            .setContentText("Patrón experimental: toca para confirmar o descartar").setContentIntent(open).setAutoCancel(true).build())
    }
    @Synchronized fun answer(c:Context,label:String) {
        require(label in setOf("confirmed","false_positive","unsure"))
        val event=pending.value ?: return
        val id=UUID.randomUUID().toString()
        val response=JSONObject().put("type","detection_response").put("id",id).put("event_id",event.getString("id"))
            .put("label",label).put("watch_wall_ms",System.currentTimeMillis()).put("watch_elapsed_ns",android.os.SystemClock.elapsedRealtimeNanos())
        c.getSharedPreferences("detection_review",0).edit().putString("response_$id",response.toString()).remove("pending").commit()
        pending.value=null;c.getSystemService(NotificationManager::class.java).cancel(401)
        PhoneBridge.notices.offer(response.toString())
    }
    fun responses(c:Context)=c.getSharedPreferences("detection_review",0).all.filterKeys{it.startsWith("response_")}.values.filterIsInstance<String>()
    fun acknowledge(c:Context,id:String){c.getSharedPreferences("detection_review",0).edit().remove("response_$id").commit()}
}
