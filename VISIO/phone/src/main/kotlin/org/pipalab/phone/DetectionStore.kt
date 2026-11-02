package org.pipalab.phone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Immutable machine evidence plus append-only, idempotent human corrections. */
class DetectionStore(private val store:Store) {
    companion object {
        fun create(db:SQLiteDatabase) {
            db.execSQL("CREATE TABLE detector_events(id TEXT PRIMARY KEY,session TEXT NOT NULL,training_id TEXT NOT NULL,wall INTEGER NOT NULL,evidence TEXT NOT NULL,label TEXT NOT NULL DEFAULT 'pending',prompt_ms INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE detector_reviews(id TEXT PRIMARY KEY,event_id TEXT NOT NULL,received_ms INTEGER NOT NULL,source TEXT NOT NULL,label TEXT NOT NULL,note TEXT NOT NULL,original TEXT NOT NULL,applied INTEGER NOT NULL)")
        }
    }
    fun add(id:String,session:String,training:String,wall:Long,evidence:JSONObject) {
        store.writableDatabase.execSQL("INSERT OR IGNORE INTO detector_events(id,session,training_id,wall,evidence) VALUES(?,?,?,?,?)",arrayOf<Any>(id,session,training,wall,evidence.toString()))
    }
    fun events(session:String?=null):List<JSONObject> = store.readableDatabase.rawQuery("SELECT * FROM detector_events"+(if(session==null) "" else " WHERE session=?")+" ORDER BY wall DESC",session?.let{arrayOf(it)}).use {c->
        buildList {while(c.moveToNext())add(JSONObject().apply {for(i in 0 until c.columnCount)put(c.getColumnName(i),c.getString(i))})}
    }
    fun review(id:String,label:String,note:String="",source:String="phone",responseId:String=UUID.randomUUID().toString(),original:JSONObject=JSONObject()):Boolean {
        require(label in setOf("confirmed","false_positive","unsure"));require(source in setOf("phone","watch"))
        val db=store.writableDatabase;db.beginTransaction()
        try {
            val current=db.rawQuery("SELECT label FROM detector_events WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())it.getString(0) else null} ?: return false
            val exists=db.rawQuery("SELECT 1 FROM detector_reviews WHERE id=?",arrayOf(responseId)).use{it.moveToFirst()}
            if(!exists) {
                val apply=source=="phone" || current=="pending"
                db.execSQL("INSERT INTO detector_reviews VALUES(?,?,?,?,?,?,?,?)",arrayOf<Any>(responseId,id,System.currentTimeMillis(),source,label,note.take(1200),original.toString(),if(apply)1 else 0))
                if(apply)db.execSQL("UPDATE detector_events SET label=? WHERE id=?",arrayOf(label,id))
            }
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    fun prompt(id:String,now:Long):Boolean {
        val db=store.writableDatabase;db.beginTransaction()
        try {
            // Same 45-minute / four-per-day budget as the reference. Kept across process restarts.
            val allowed=db.rawQuery("SELECT COUNT(*),MAX(prompt_ms) FROM detector_events WHERE prompt_ms>?",arrayOf((now-86_400_000).toString())).use{it.moveToFirst();it.getInt(0)<4 && (it.isNull(1)||now-it.getLong(1)>=2_700_000)}
            if(!allowed)return false
            val changed=db.compileStatement("UPDATE detector_events SET prompt_ms=? WHERE id=? AND prompt_ms=0 AND label='pending'").use{it.bindLong(1,now);it.bindString(2,id);it.executeUpdateDelete()==1}
            db.setTransactionSuccessful();return changed
        }finally{db.endTransaction()}
    }
    fun export(sessions:Set<String>?=null):JSONObject {
        val events=events().filter {sessions==null || it.getString("session") in sessions}
        val ids=events.map{it.getString("id")}.toSet();val reviews=JSONArray()
        store.readableDatabase.rawQuery("SELECT * FROM detector_reviews ORDER BY received_ms,id",null).use{c->while(c.moveToNext())if(c.getString(c.getColumnIndexOrThrow("event_id")) in ids)reviews.put(JSONObject().apply{for(i in 0 until c.columnCount)put(c.getColumnName(i),c.getString(i))})}
        return JSONObject().put("schema","visio-detector-feedback-v1").put("events",JSONArray(events)).put("reviews",reviews)
            .put("meaning","Scores are uncalibrated heuristic ranks. Human labels apply only to the indicated interval; pending/unsure are not negatives. Stale watch responses remain in audit with applied=0. No automatic training or sharing.")
    }
}
