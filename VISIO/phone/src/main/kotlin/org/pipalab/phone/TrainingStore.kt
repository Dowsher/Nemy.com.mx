package org.pipalab.phone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.pipalab.core.ActivityCapture
import org.pipalab.core.ActivityTypes
import java.util.UUID

data class TrainingType(val id: String, val name: String)
data class TrainingRecord(val id: String, val typeId: String, val activity: String, val description: String,
    val audioRequested: Boolean, val durationSeconds: Int, val created: Long, val session: String, val status: String,
    val start: Long, val end: Long, val audioState: String, val audioSeconds: Double, val review: String,
    val technicalTest: Boolean, val original: String, val verified: Boolean)
data class Transcript(val id: String, val captureId: String, val start: Long, val end: Long, val text: String, val partial: Boolean)

class TrainingStore(private val store: Store) {
    private val db get() = store.writableDatabase
    fun types(): List<TrainingType> = db.rawQuery("SELECT id,name FROM activity_types ORDER BY name COLLATE NOCASE", null).use { c ->
        buildList { while(c.moveToNext()) add(TrainingType(c.getString(0), c.getString(1))) }
    }
    fun addType(name: String): String {
        val cleaned = name.trim(); require(cleaned.isNotEmpty() && cleaned.length <= 60)
        val id = UUID.randomUUID().toString()
        db.execSQL("INSERT OR IGNORE INTO activity_types VALUES(?,?,?)", arrayOf<Any>(id, cleaned, System.currentTimeMillis()))
        return db.rawQuery("SELECT id FROM activity_types WHERE name=? COLLATE NOCASE", arrayOf(cleaned)).use { it.moveToFirst(); it.getString(0) }
    }
    fun prepare(type: TrainingType, description: String, audio: Boolean, seconds: Int, technicalTest:Boolean=false, evaluationOnly:Boolean=false): ActivityCapture {
        val plan = ActivityCapture(UUID.randomUUID().toString(), type.id, type.name, description.trim(), audio, seconds, System.currentTimeMillis(),technicalTest)
        insert(plan, "requested")
        db.execSQL("UPDATE training_sessions SET original_spec=? WHERE id=?",arrayOf(plan.json().put("evaluation_only",evaluationOnly).toString(),plan.id))
        return plan
    }
    private fun insert(plan: ActivityCapture, state: String) {
        db.execSQL("INSERT OR IGNORE INTO activity_types VALUES(?,?,?)", arrayOf<Any>(plan.typeId, plan.activity, plan.createdMs))
        db.execSQL("INSERT OR IGNORE INTO training_sessions(id,type_id,type_name,description,audio_requested,duration_seconds,created_ms,status,technical_test,original_spec) VALUES(?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(plan.id,plan.typeId,plan.activity,plan.description,if(plan.audio)1 else 0,plan.durationSeconds,plan.createdMs,state,if(plan.technicalTest)1 else 0,plan.json().toString()))
    }
    fun records(): List<TrainingRecord> = db.rawQuery("SELECT t.*,COALESCE(s.complete,0) verified FROM training_sessions t LEFT JOIN sessions s ON s.id=t.watch_session ORDER BY t.created_ms DESC", null).use { c ->
        buildList { while(c.moveToNext()) {
            fun str(key:String) = c.getString(c.getColumnIndexOrThrow(key))
            fun long(key:String) = c.getLong(c.getColumnIndexOrThrow(key))
            add(TrainingRecord(str("id"),str("type_id"),str("type_name"),str("description"),long("audio_requested")==1L,long("duration_seconds").toInt(),
                long("created_ms"),str("watch_session"),str("status"),long("start_ms"),long("end_ms"),str("audio_state"),
                c.getDouble(c.getColumnIndexOrThrow("audio_seconds")),str("review"),long("technical_test")==1L,str("original_spec"),long("verified")==1L))
        } }
    }
    fun status(id: String, state: String) {
        require(state in setOf("draft","not_ready","expired","requested","prepared","busy","pending_other","already_started","cancelled","not_pending","unsupported","unconfirmed","sending_stop"))
        db.execSQL("UPDATE training_sessions SET status=? WHERE id=? AND watch_session=''",arrayOf(state,id))
    }
    fun ingestMeta(session: String, meta: JSONObject) {
        val training = meta.optJSONObject("training") ?: return
        val plan = ActivityCapture.parse(training)
        insert(plan, "recording")
        val audio = meta.optJSONObject("audio") ?: JSONObject()
        val status = if(meta.optBoolean("finalized")) { if(meta.optString("status")=="completed") "closed" else "interrupted" } else "recording"
        db.execSQL("UPDATE training_sessions SET watch_session=?,status=?,start_ms=?,end_ms=?,audio_state=?,audio_seconds=? WHERE id=?",
            arrayOf<Any>(session,status,meta.optLong("start_wall_ms"),meta.optLong("end_wall_ms"),audio.optString("state",if(plan.audio)"pending" else "off"),audio.optDouble("duration_seconds",0.0),plan.id))
    }
    fun live(id: String, session: String, recording: Boolean, stopping: Boolean, audio: String, seconds: Double) {
        if(id.isBlank() || !recording) return // A missing heartbeat never means a clean session end.
        db.execSQL("UPDATE training_sessions SET watch_session=?,status=?,audio_state=?,audio_seconds=? WHERE id=? AND status NOT IN ('closed','interrupted')",
            arrayOf<Any>(session,if(stopping)"finishing" else "recording",audio,seconds,id))
    }
    fun review(record: TrainingRecord, type: TrainingType, description: String, decision: String) {
        require(decision in setOf("unreviewed","accepted","excluded"))
        require(description.length <= 1200 && record.status in setOf("closed","interrupted"))
        require(decision != "accepted" || !record.technicalTest)
        db.beginTransaction()
        try {
            db.execSQL("INSERT INTO training_reviews VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(UUID.randomUUID().toString(),record.id,System.currentTimeMillis(),type.id,type.name,description,decision))
            db.execSQL("UPDATE training_sessions SET type_id=?,type_name=?,description=?,review=? WHERE id=?",arrayOf(type.id,type.name,description,decision,record.id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun transcript(captureId:String,start:Long,end:Long,delta:Long,text:String,partial:Boolean) {
        if(text.isBlank()) return
        require(records().any { it.id == captureId })
        db.execSQL("INSERT INTO training_transcripts VALUES(?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>(UUID.randomUUID().toString(),captureId,start,end,delta,
            start-delta,end-delta,text.take(10000),if(partial)"partial" else "on_device_final","unreviewed"))
    }
    fun transcripts(id: String): List<Transcript> = db.rawQuery("SELECT id,training_id,start_phone_ms,end_phone_ms,text,source FROM training_transcripts WHERE training_id=? ORDER BY start_phone_ms",arrayOf(id)).use { c ->
        buildList { while(c.moveToNext()) add(Transcript(c.getString(0),c.getString(1),c.getLong(2),c.getLong(3),c.getString(4),c.getString(5)=="partial")) }
    }
    fun pointRequest(id: String, captureId: String, label: String, phoneMs: Long) {
        db.execSQL("INSERT OR IGNORE INTO training_point_requests VALUES(?,?,?,?,'requested',0)",arrayOf<Any>(id,captureId,label,phoneMs))
    }
    fun pointAck(id: String, wall: Long) { db.execSQL("UPDATE training_point_requests SET state='received',watch_wall_ms=? WHERE id=?",arrayOf<Any>(wall,id)) }
    fun export(): JSONObject {
        val out = JSONObject().put("schema", "visio-training-dataset-v1").put("created_ms", System.currentTimeMillis())
            .put("activity_is_human_label", true).put("automatic_activity_detection", false)
            .put("voice_alignment", "Phone clock intervals and one-way BLE clock estimate; not word-level alignment. Transcripts need human review.")
            .put("audio_demo", "Acoustic impulses are demonstration triggers, never verified finger-snap or lighter labels.")
        for(table in listOf("activity_types","training_sessions","training_reviews","training_transcripts","training_point_requests")) {
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM $table",null).use { c -> while(c.moveToNext()) {
                val row=JSONObject(); for(i in 0 until c.columnCount) row.put(c.getColumnName(i),if(c.isNull(i))JSONObject.NULL else c.getString(i)); rows.put(row)
            } }
            out.put(table,rows)
        }
        return out
    }
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE activity_types(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE COLLATE NOCASE,created_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE training_sessions(id TEXT PRIMARY KEY,type_id TEXT NOT NULL,type_name TEXT NOT NULL,description TEXT NOT NULL,audio_requested INTEGER NOT NULL,duration_seconds INTEGER NOT NULL,created_ms INTEGER NOT NULL,watch_session TEXT NOT NULL DEFAULT '',status TEXT NOT NULL,start_ms INTEGER NOT NULL DEFAULT 0,end_ms INTEGER NOT NULL DEFAULT 0,audio_state TEXT NOT NULL DEFAULT 'off',audio_seconds REAL NOT NULL DEFAULT 0,review TEXT NOT NULL DEFAULT 'unreviewed',technical_test INTEGER NOT NULL DEFAULT 0,original_spec TEXT NOT NULL)")
            db.execSQL("CREATE INDEX training_type ON training_sessions(type_id,created_ms)")
            db.execSQL("CREATE TABLE training_reviews(id TEXT PRIMARY KEY,training_id TEXT NOT NULL,time_ms INTEGER NOT NULL,type_id TEXT NOT NULL,type_name TEXT NOT NULL,description TEXT NOT NULL,decision TEXT NOT NULL)")
            db.execSQL("CREATE TABLE training_transcripts(id TEXT PRIMARY KEY,training_id TEXT NOT NULL,start_phone_ms INTEGER NOT NULL,end_phone_ms INTEGER NOT NULL,clock_delta_ms INTEGER NOT NULL,start_watch_estimate_ms INTEGER NOT NULL,end_watch_estimate_ms INTEGER NOT NULL,text TEXT NOT NULL,source TEXT NOT NULL,review TEXT NOT NULL)")
            db.execSQL("CREATE TABLE training_point_requests(id TEXT PRIMARY KEY,training_id TEXT NOT NULL,label TEXT NOT NULL,phone_wall_ms INTEGER NOT NULL,state TEXT NOT NULL,watch_wall_ms INTEGER NOT NULL)")
            ActivityTypes.defaults.forEach { (id,name) -> db.execSQL("INSERT INTO activity_types VALUES(?,?,?)",arrayOf<Any>(id,name,System.currentTimeMillis())) }
        }
    }
}
