package org.pipalab.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.pipalab.core.Csv
import org.pipalab.core.MinuteBook
import org.pipalab.core.MinuteStats
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class SessionInfo(val id:String,val start:Long,val end:Long,val count:Long,val complete:Boolean,val meta:String)
data class EventInfo(val id:String,val time:Long,val end:Long,val session:String,val kind:String,val origin:String,val state:String,val note:String,val features:String)
data class Sample(val wall:Long,val elapsed:Long,val values:Map<String,String>,val source:String="raw",val file:String="",val index:Long=0)
data class MinuteInfo(val stream:String,val minute:Long,val rows:Long,val first:Long,val last:Long,val stats:String,val quality:String,val source:String)
data class MeasurementInfo(val id:String,val session:String,val kind:String,val start:Long,val end:Long,val available:Long,val status:String)
class Store private constructor(context:Context): SQLiteOpenHelper(context,"pipalab.db",null,4) {
    val root=File(context.getExternalFilesDir(null),"archives").apply { mkdirs() }
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,start INTEGER NOT NULL,end INTEGER NOT NULL DEFAULT 0,meta TEXT NOT NULL DEFAULT '{}',complete INTEGER NOT NULL DEFAULT 0,n INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE samples(session TEXT NOT NULL,file TEXT NOT NULL,idx INTEGER NOT NULL,stream TEXT NOT NULL,elapsed INTEGER NOT NULL,wall INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(session,file,idx))")
        db.execSQL("CREATE INDEX sample_time ON samples(session,stream,wall)")
        db.execSQL("CREATE TABLE events(id TEXT PRIMARY KEY,time INTEGER NOT NULL,end INTEGER NOT NULL,session TEXT NOT NULL,kind TEXT NOT NULL,origin TEXT NOT NULL,state TEXT NOT NULL,note TEXT NOT NULL,features TEXT NOT NULL,model TEXT NOT NULL)")
        db.execSQL("CREATE INDEX event_time ON events(time)")
        db.execSQL("CREATE TABLE feedback(id TEXT PRIMARY KEY,event TEXT NOT NULL,time INTEGER NOT NULL,decision TEXT NOT NULL,kind TEXT NOT NULL,reason TEXT NOT NULL,source TEXT NOT NULL)")
        db.execSQL("CREATE TABLE files(session TEXT NOT NULL,name TEXT NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,PRIMARY KEY(session,name))")
        migrateAudit(db)
        createLed(db)
        createSummaries(db)
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int) { if(old<2) migrateAudit(db); if(old<3) createLed(db);if(old<4)createSummaries(db) }
    private fun createSummaries(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE minute_stats(session TEXT,stream TEXT,minute INTEGER,rows INTEGER,first INTEGER,last INTEGER,stats TEXT,quality TEXT,source TEXT,PRIMARY KEY(session,stream,minute))")
        db.execSQL("CREATE TABLE measurement_windows(id TEXT PRIMARY KEY,session TEXT,kind TEXT,start INTEGER,end INTEGER,available INTEGER,status TEXT)")
        db.execSQL("CREATE TABLE summary_cache(session TEXT PRIMARY KEY,n INTEGER)")
    }
    private fun createLed(db:SQLiteDatabase) { db.execSQL("CREATE TABLE led_commands(id TEXT PRIMARY KEY,event TEXT NOT NULL,source TEXT NOT NULL,requested_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,status TEXT NOT NULL,result_ms INTEGER NOT NULL,detail TEXT NOT NULL)") }
    @Synchronized fun ledRequest(id:String,event:String,source:String,duration:Int) {
        writableDatabase.execSQL("INSERT INTO led_commands VALUES(?,?,?,?,?,'requested',0,'')",arrayOf<Any>(id,event,source,System.currentTimeMillis(),duration))
    }
    @Synchronized fun ledResult(id:String,status:String,detail:String) {
        writableDatabase.execSQL("UPDATE led_commands SET status=?,result_ms=?,detail=? WHERE id=?",arrayOf<Any>(status,System.currentTimeMillis(),detail,id))
    }
    fun exportLed():String = buildString {
        appendLine(Csv.line(listOf("command_id","event_id","source","requested_wall_ms","duration_ms","status","result_wall_ms","detail")))
        readableDatabase.rawQuery("SELECT * FROM led_commands ORDER BY requested_ms",null).use {c->while(c.moveToNext())appendLine(Csv.line((0 until c.columnCount).map{c.getString(it)}))}
    }
    private fun migrateAudit(db:SQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN original_kind TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE events ADD COLUMN original_note TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE events SET original_kind=kind,original_note=note")
        db.execSQL("CREATE TABLE stream_counts(session TEXT NOT NULL,stream TEXT NOT NULL,n INTEGER NOT NULL,PRIMARY KEY(session,stream))")
        db.execSQL("INSERT INTO stream_counts SELECT session,stream,COUNT(*) FROM samples GROUP BY session,stream")
    }
    @Synchronized fun putMeta(id:String,meta:JSONObject) {
        val db=writableDatabase
        db.execSQL("INSERT OR IGNORE INTO sessions(id,start) VALUES(?,?)",arrayOf<Any>(id,meta.optLong("start_wall_ms",System.currentTimeMillis())))
        db.execSQL("UPDATE sessions SET start=?,end=?,meta=? WHERE id=?",arrayOf<Any>(meta.optLong("start_wall_ms"),meta.optLong("end_wall_ms"),meta.toString(),id))
    }
    @Synchronized fun insertRows(id:String,file:String,stream:String,rows:List<Pair<Long,JSONObject>>) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO sessions(id,start) VALUES(?,?)",arrayOf<Any>(id,rows.firstOrNull()?.second?.optLong("t_wall_ms") ?: System.currentTimeMillis()))
            val st=db.compileStatement("INSERT OR IGNORE INTO samples(session,file,idx,stream,elapsed,wall,data) VALUES(?,?,?,?,?,?,?)")
            var added=0L
            st.use {
                for ((idx,row) in rows) {
                    it.clearBindings(); it.bindString(1,id); it.bindString(2,file); it.bindLong(3,idx); it.bindString(4,stream)
                    it.bindLong(5,row.getLong("t_elapsed_ns")); it.bindLong(6,row.getLong("t_wall_ms")); it.bindString(7,row.toString())
                    if(it.executeInsert()!=-1L) {added++;structured(id,stream,row)}
                }
            }
            db.execSQL("UPDATE sessions SET n=n+? WHERE id=?",arrayOf<Any>(added,id))
            db.execSQL("INSERT OR IGNORE INTO stream_counts VALUES(?,?,0)",arrayOf(id,stream))
            db.execSQL("UPDATE stream_counts SET n=n+? WHERE session=? AND stream=?",arrayOf<Any>(added,id,stream))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun sessions():List<SessionInfo> = readableDatabase.rawQuery("SELECT id,start,end,n,complete,meta FROM sessions ORDER BY start DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(SessionInfo(c.getString(0),c.getLong(1),c.getLong(2),c.getLong(3),c.getInt(4)==1,c.getString(5))) }
    }
    fun points(id:String,stream:String,from:Long,to:Long,max:Int=800):List<Sample> {
        val result=ArrayList<Sample>()
        val args=arrayOf(id,stream,from.toString(),to.toString())
        val count=readableDatabase.rawQuery("SELECT COUNT(*) FROM samples WHERE session=? AND stream=? AND wall BETWEEN ? AND ?",args).use{it.moveToFirst();it.getLong(0)}
        val stride=((count+max-1)/max).coerceAtLeast(1)
        readableDatabase.rawQuery("SELECT elapsed,wall,data FROM samples WHERE session=? AND stream=? AND wall BETWEEN ? AND ? AND (idx-1)%?=0 ORDER BY wall LIMIT ?",args+arrayOf(stride.toString(),(max+20).toString())).use { c ->
            while(c.moveToNext()) {
                val j=JSONObject(c.getString(2)); val values=j.keys().asSequence().associateWith { j.optString(it) }
                result.add(Sample(c.getLong(1),c.getLong(0),values))
            }
        }
        return result
    }
    fun latest(id:String,stream:String):Sample? = readableDatabase.rawQuery("SELECT elapsed,wall,data FROM samples WHERE session=? AND stream=? ORDER BY wall DESC LIMIT 1",arrayOf(id,stream)).use { c ->
        if(!c.moveToFirst()) null else { val j=JSONObject(c.getString(2)); Sample(c.getLong(1),c.getLong(0),j.keys().asSequence().associateWith{j.optString(it)}) }
    }
    fun latestLive(id:String,stream:String):Sample? {
        val live=LiveSamples.latest(id,stream);val stored=latest(id,stream)
        return if((live?.wall ?: 0)>(stored?.wall ?: 0))live else stored
    }
    private fun structured(id:String,stream:String,row:JSONObject) {
        if(stream=="resumen_minuto") writableDatabase.execSQL("INSERT INTO minute_stats VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(session,stream,minute) DO UPDATE SET rows=excluded.rows,first=excluded.first,last=excluded.last,stats=excluded.stats,quality=excluded.quality,source=excluded.source WHERE excluded.rows>=minute_stats.rows OR minute_stats.source!='watch'",arrayOf<Any>(id,row.getString("source_stream"),row.getLong("minute_start_ms"),row.getLong("samples"),row.getLong("first_sample_ms"),row.getLong("last_sample_ms"),row.getString("stats_json"),row.getString("quality_json"),"watch"))
        if(stream=="ventanas") window(row.getString("window_id"),id,row.getString("kind"),row.getLong("start_wall_ms"),row.getLong("end_wall_ms"),row.getLong("available_from_ms"),row.getString("state"))
    }
    @Synchronized fun window(id:String,session:String,kind:String,start:Long,end:Long,available:Long,status:String) {
        writableDatabase.execSQL("INSERT INTO measurement_windows VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET session=excluded.session,kind=excluded.kind,start=excluded.start,end=excluded.end,available=excluded.available,status=excluded.status WHERE measurement_windows.status NOT IN ('closed','interrupted') OR excluded.status IN ('closed','interrupted')",arrayOf<Any>(id,session,kind,start,end,available,status))
    }
    fun measurements():List<MeasurementInfo> = readableDatabase.rawQuery("SELECT * FROM measurement_windows WHERE kind='medicion_manual' ORDER BY start DESC",null).use{c->buildList{while(c.moveToNext())add(MeasurementInfo(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getLong(5),c.getString(6)))}}
    fun minutes(id:String):List<MinuteInfo> = readableDatabase.rawQuery("SELECT stream,minute,rows,first,last,stats,quality,source FROM minute_stats WHERE session=? ORDER BY minute DESC,stream",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(MinuteInfo(c.getString(0),c.getLong(1),c.getLong(2),c.getLong(3),c.getLong(4),c.getString(5),c.getString(6),c.getString(7)))}}
    @Synchronized fun ensureLegacyMinutes(id:String) {
        val session=sessions().find{it.id==id} ?: return
        if(JSONObject(session.meta).optString("storage_policy").startsWith("compact_"))return
        val count=readableDatabase.rawQuery("SELECT n FROM summary_cache WHERE session=?",arrayOf(id)).use{if(it.moveToFirst())it.getLong(0) else -1}
        if(count==session.count)return
        val book=MinuteBook()
        readableDatabase.rawQuery("SELECT stream,wall,data FROM samples WHERE session=? ORDER BY wall",arrayOf(id)).use{c->while(c.moveToNext()){val j=JSONObject(c.getString(2));book.add(c.getString(0),c.getLong(1),j.keys().asSequence().associateWith{j.optString(it)})}}
        val db=writableDatabase;db.beginTransaction()
        try {book.snapshots().forEach{s->
            val stats=JSONObject();s.channels.forEach{(k,v)->stats.put(k,JSONObject().put("n",v.count).put("mean",v.mean).put("min",v.min).put("max",v.max).put("sd",v.sd))}
            db.execSQL("INSERT OR REPLACE INTO minute_stats VALUES(?,?,?,?,?,?,?,?,?)",arrayOf<Any>(id,s.stream,s.start,s.rows,s.first,s.last,stats.toString(),JSONObject(s.quality).toString(),"legacy"))
        };db.execSQL("INSERT OR REPLACE INTO summary_cache VALUES(?,?)",arrayOf<Any>(id,session.count));db.setTransactionSuccessful()}finally{db.endTransaction()}
    }
    fun plot(id:String,stream:String,from:Long,to:Long):List<Sample> {
        val raw=points(id,stream,from,to);val live=LiveSamples.points(id,stream,from,to)
        val summaries=readableDatabase.rawQuery("SELECT minute,stats FROM minute_stats WHERE session=? AND stream=? AND minute BETWEEN ? AND ? AND source='watch' ORDER BY minute",arrayOf(id,stream,from.toString(),to.toString())).use{c->buildList{
            while(c.moveToNext()){val stats=JSONObject(c.getString(1));add(Sample(c.getLong(0),0,stats.keys().asSequence().associateWith{stats.getJSONObject(it).getDouble("mean").toString()},"minute"))}
        }}
        val detailed=(raw+live).associateBy{it.wall}.values.sortedBy{it.wall}.let{r->val stride=((r.size+899)/900).coerceAtLeast(1);r.filterIndexed{i,_->i%stride==0}}
        val summaryStride=((summaries.size+899)/900).coerceAtLeast(1)
        return (summaries.filterIndexed{i,_->i%summaryStride==0}+detailed).associateBy{it.wall}.values.sortedBy{it.wall}
    }
    fun rawPage(id:String,stream:String,from:Long,to:Long,page:Int,size:Int=40):List<Sample> = readableDatabase.rawQuery("SELECT elapsed,wall,data,file,idx FROM samples WHERE session=? AND stream=? AND wall BETWEEN ? AND ? ORDER BY wall,file,idx LIMIT ? OFFSET ?",arrayOf(id,stream,from.toString(),to.toString(),size.toString(),(page*size).toString())).use{c->buildList{while(c.moveToNext()){val j=JSONObject(c.getString(2));add(Sample(c.getLong(1),c.getLong(0),j.keys().asSequence().associateWith{j.optString(it)},"raw",c.getString(3),c.getLong(4)))}}}
    fun measurementStats(m:MeasurementInfo):List<MinuteStats> {
        val book=MinuteBook()
        readableDatabase.rawQuery("SELECT stream,data FROM samples WHERE session=? AND wall BETWEEN ? AND ? AND stream NOT IN ('resumen_minuto','ventanas') ORDER BY wall",arrayOf(m.session,m.start.toString(),m.end.toString())).use{c->while(c.moveToNext()){val j=JSONObject(c.getString(1));book.add(c.getString(0),0,j.keys().asSequence().associateWith{j.optString(it)})}}
        return book.snapshots()
    }
    fun exportTable(table:String):String {
        require(table in listOf("minute_stats","measurement_windows"))
        return readableDatabase.rawQuery("SELECT * FROM "+table,null).use{c->buildString{appendLine(Csv.line(c.columnNames.toList()));while(c.moveToNext())appendLine(Csv.line((0 until c.columnCount).map{c.getString(it)}))}}
    }
    fun events():List<EventInfo> = readableDatabase.rawQuery("SELECT id,time,end,session,kind,origin,state,note,features FROM events ORDER BY time DESC",null).use { c ->
        buildList { while(c.moveToNext()) add(EventInfo(c.getString(0),c.getLong(1),c.getLong(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8))) }
    }
    @Synchronized fun event(time:Long,end:Long,session:String,kind:String,origin:String,note:String="",features:String="{}"):String {
        val id=UUID.randomUUID().toString()
        val state=if(origin=="manual") "confirmed" else "pending"
        writableDatabase.execSQL("INSERT INTO events VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf<Any>(id,time,end,session,kind,origin,state,note,features,if(origin=="automatic") org.pipalab.core.GestureDetector.VERSION else "none",kind,note))
        if(origin=="manual") feedback(id,"confirmed",kind,note,"manual")
        return id
    }
    @Synchronized fun feedback(id:String,decision:String,kind:String,reason:String,source:String) {
        require(decision in listOf("confirmed","false_positive","pending"))
        val db=writableDatabase; db.beginTransaction()
        try {
            db.execSQL("INSERT INTO feedback VALUES(?,?,?,?,?,?,?)",arrayOf<Any>(UUID.randomUUID().toString(),id,System.currentTimeMillis(),decision,kind,reason,source))
            db.execSQL("UPDATE events SET state=?,kind=?,note=? WHERE id=?",arrayOf<Any>(decision,kind,reason,id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun safeFile(session:String,name:String):File {
        require(session.matches(Regex("[A-Za-z0-9_-]{1,120}")) && name.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && !name.contains(".."))
        val dir=File(root,session).apply { mkdirs() }
        return File(dir,name).also { require(it.canonicalFile.parentFile == dir.canonicalFile) }
    }
    fun sha(file:File):String = MessageDigest.getInstance("SHA-256").let { md -> file.inputStream().use { input -> val b=ByteArray(65536); while(true) { val n=input.read(b); if(n<0) break; md.update(b,0,n) } }; md.digest().joinToString(""){"%02x".format(it)} }
    @Synchronized fun importFile(id:String,file:File,expected:String):Boolean {
        require(sha(file)==expected) { "La copia no coincide con el reloj" }
        val known=readableDatabase.rawQuery("SELECT sha256 FROM files WHERE session=? AND name=?",arrayOf(id,file.name)).use{it.moveToFirst()&&it.getString(0)==expected}
        if(known) return false
        if(file.name=="meta.json") putMeta(id,JSONObject(file.readText()))
        else if(file.extension=="csv") {
            val stream=file.name.removeSuffix(".csv").replace(Regex("_\\d{3,}$"),"")
            file.reader().use { input ->
                val iterator=Csv.rows(input).iterator(); if(!iterator.hasNext()) return@use
                val headers=iterator.next(); var idx=0L; val batch=ArrayList<Pair<Long,JSONObject>>()
                while(iterator.hasNext()) {
                    val values=iterator.next(); require(values.size==headers.size) { "CSV incompleto: "+file.name }
                    val j=JSONObject(); headers.forEachIndexed { i,key -> j.put(key,values[i]) }
                    batch.add(++idx to j)
                    if(batch.size>=500) { insertRows(id,file.name,stream,batch); batch.clear() }
                }
                if(batch.isNotEmpty()) insertRows(id,file.name,stream,batch)
            }
        }
        writableDatabase.execSQL("INSERT OR REPLACE INTO files VALUES(?,?,?,?)",arrayOf<Any>(id,file.name,expected,file.length()))
        return true
    }
    fun completed(id:String) { writableDatabase.execSQL("UPDATE sessions SET complete=1 WHERE id=?",arrayOf<Any>(id)) }
    fun pendingVerification(id:String) { writableDatabase.execSQL("UPDATE sessions SET complete=0 WHERE id=?",arrayOf<Any>(id)) }
    fun importLocal():Int {
        var imported=0
        root.listFiles()?.filter{it.isDirectory}?.forEach { dir ->
            val meta=File(dir,"meta.json")
            if(meta.exists()) {
                putMeta(dir.name,JSONObject(meta.readText()))
                dir.listFiles()?.filter{it.isFile && (it.extension=="csv" || it.name=="meta.json")}?.forEach { if(importFile(dir.name,it,sha(it))) imported++ }
                // A finalized meta alone does not prove that every archived file arrived.
                // LinkService marks complete only after checking the watch's entire manifest.
            }
        }
        return imported
    }
    fun exportFeedback():String = buildString {
        appendLine(Csv.line(listOf("event_id","event_wall_ms","window_end_wall_ms","session","type","origin","current_status","note","features","detector_version","feedback_id","feedback_wall_ms","decision","label","reason","source","original_type","original_note")))
        readableDatabase.rawQuery("SELECT e.id,e.time,e.end,e.session,e.kind,e.origin,e.state,e.note,e.features,e.model,f.id,f.time,f.decision,f.kind,f.reason,f.source,e.original_kind,e.original_note FROM events e LEFT JOIN feedback f ON e.id=f.event ORDER BY e.time,f.time",null).use { c ->
            while(c.moveToNext()) appendLine(Csv.line((0 until c.columnCount).map{ if(c.isNull(it)) "" else c.getString(it) }))
        }
    }
    companion object {
        @Volatile private var instance:Store?=null
        fun get(context:Context)=instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also{instance=it} }
    }
}
