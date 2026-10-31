package org.pipalab.phone
import org.json.JSONObject
import org.pipalab.core.Csv
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Exporter {
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
                db.safeFile(s.id,"meta.json").parentFile?.listFiles()?.filter{it.isFile && (it.extension=="csv" || it.name=="meta.json")}?.forEach{f->
                    zip.putNextEntry(ZipEntry("originales/"+s.id+"/"+f.name)); f.inputStream().use{it.copyTo(zip)}; zip.closeEntry()
                }
            }
        }
    }
}
