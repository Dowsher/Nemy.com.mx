package org.pipalab.phone
import org.json.JSONObject
import org.pipalab.core.Csv
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {
    fun writeTraining(db:Store,out:OutputStream,learning:JSONObject?=null) {
        val training=TrainingStore(db)
        val records=training.records()
        val ids=records.map{it.session}.filter{it.isNotBlank()}.toSet()
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name:String,text:String) {zip.putNextEntry(ZipEntry(name));zip.write(text.toByteArray());zip.closeEntry()}
            entry("dataset.json",training.export().toString(2))
            learning?.let{entry("aprendizaje.json",it.toString(2))}
            entry("actuaciones_led.csv",db.exportLed())
            entry("LEEME.txt","""
                Visio 0.7 · base de datos de actividades
                aprendizaje.json conserva versiones, características y resultados del modelo local cuando esté incluido.
                original_spec.evaluation_only=true reserva la sesión para evaluación; nunca entrena los prototipos.
                Las actividades son etiquetas humanas de sesiones. Revisar antes de entrenar: review=accepted/excluded/unreviewed.
                technical_test=1 nunca es un ejemplo de actividad. Conservar esos registros como comprobaciones técnicas.
                Cada sesión incluye las filas originales retenidas de movimiento a 25 Hz, pulso/IBI y temperatura.
                Audio opcional: WAV PCM16 mono 16 kHz, audio.json y audio_timeline.csv con anclas BOOTTIME/estimación declarada.
                audio.state=failed/empty/interrupted o ausencia de audio.wav no equivale a grabación válida.
                Las transcripciones son anotaciones de voz del teléfono, no etiquetas automáticas ni sincronización palabra a palabra.
                Los intervalos de dictado pueden contener voz también en el micrófono del reloj; excluirlos o etiquetarlos al analizar sonidos.
                Los marcadores POINT son manuales. audio_transient/CANDIDATE es una regla acústica de demostración, no un encendedor o chasquido confirmado.
                El LED registra órdenes y acuses, no garantiza percepción física de la luz.
                No dividir muestras/ventanas vecinas de una misma sesión entre entrenamiento y validación.
                Un archivo ausente o una sesión sin finalización/copia verificada puede estar incompleto. Ver transferencia.json.
                La exportación es local y contiene audio, texto y señales personales. No se incluye ninguna clave privada.
            """.trimIndent())
            val manifest=org.json.JSONArray()
            for(session in db.sessions().filter{it.id in ids}) {
                val files=org.json.JSONArray()
                val missing=org.json.JSONArray()
                db.readableDatabase.rawQuery("SELECT name,sha256,size FROM files WHERE session=? ORDER BY name",arrayOf(session.id)).use {c->while(c.moveToNext()) {
                    val name=c.getString(0);if(!org.pipalab.core.ActivityTypes.transferable(name))continue
                    val f=db.safeFile(session.id,name)
                    if(!f.exists() || db.sha(f)!=c.getString(1)){missing.put(name);continue}
                    zip.putNextEntry(ZipEntry("sesiones/"+session.id+"/"+name));f.inputStream().use{it.copyTo(zip)};zip.closeEntry()
                    files.put(JSONObject().put("name",name).put("sha256",c.getString(1)).put("bytes",c.getLong(2)))
                }}
                manifest.put(JSONObject().put("session",session.id).put("verified_complete_copy",session.complete && missing.length()==0).put("missing_or_changed",missing).put("files",files))
            }
            entry("transferencia.json",manifest.toString(2))
        }
    }
    fun write(db:Store,out:OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name:String,bytes:ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            entry("feedback.csv",db.exportFeedback().toByteArray())
            entry("actuaciones_led.csv",db.exportLed().toByteArray())
            entry("promedios_minuto.csv",db.exportTable("minute_stats").toByteArray())
            entry("ventanas_entrenamiento.csv",db.exportTable("measurement_windows").toByteArray())
            entry("README.txt",("Visio 0.4 · ecosistema Nemy · exportación local\n"+
                "compact_v1: promedios/minuto y segmentos conservados; el resto de señal de alta frecuencia solo circula en RAM.\n"+
                "Controles periódicos sin etiqueta: 10 s/5 min. Revisar antes de usarlos como negativos de entrenamiento.\n"+
                "Los promedios incluyen n válido, min/max, desviación y estados de calidad. IBI se asigna al minuto del lote de origen.\n"+
                "Las alertas son reglas experimentales, no un modelo entrenado.\n"+
                "origin=test: excluir del entrenamiento. pending: todavía sin etiqueta.\n"+
                "confirmed/false_positive: decisiones humanas; pueden corregirse y cada cambio conserva una fila de auditoría.\n"+
                "event_wall_ms y window_end_wall_ms delimitan la señal del evento; consultar también 30 s antes/después.\n"+
                "Los eventos manuales usan el reloj del teléfono; los sensores conservan el reloj del wearable. Considerar desfase.\n"+
                "Las sesiones sin finalizar pueden estar incompletas; los CSV exportados desde la base reflejan lo recibido hasta este momento.\n").toByteArray())
            for(s in db.sessions()) {
                entry("sesiones/"+s.id+"/meta.json",s.meta.toByteArray())
                db.readableDatabase.rawQuery("SELECT DISTINCT file FROM samples WHERE session=? ORDER BY file",arrayOf(s.id)).use { files ->
                    while(files.moveToNext()) {
                        val name=files.getString(0)
                        zip.putNextEntry(ZipEntry("sesiones/"+s.id+"/"+name))
                        db.readableDatabase.rawQuery("SELECT data FROM samples WHERE session=? AND file=? ORDER BY idx",arrayOf(s.id,name)).use{rows->
                            var keys:List<String>?=null
                            while(rows.moveToNext()) {
                                val row=JSONObject(rows.getString(0))
                                if(keys==null) { keys=row.keys().asSequence().toList(); zip.write((Csv.line(keys!!)+"\n").toByteArray()) }
                                zip.write((Csv.line(keys!!.map{if(row.isNull(it)) "" else row.get(it).toString()})+"\n").toByteArray())
                            }
                        }
                        zip.closeEntry()
                    }
                }
                // Preserve original files, including empty sensor streams, separately from the normalized view.
                db.safeFile(s.id,"meta.json").parentFile?.listFiles()?.filter{it.isFile && org.pipalab.core.ActivityTypes.transferable(it.name)}?.forEach{f->
                    zip.putNextEntry(ZipEntry("originales/"+s.id+"/"+f.name)); f.inputStream().use{it.copyTo(zip)}; zip.closeEntry()
                }
            }
        }
    }
}
