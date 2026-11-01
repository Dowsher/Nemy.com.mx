package org.pipalab.phone

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SessionArchiveTest {
    private fun store()=Store.get(RuntimeEnvironment.getApplication())
    @Test fun singleSessionZipDoesNotLeakOtherSessionsOrTranscripts() {
        val store=store();val training=TrainingStore(store);val media=SessionMedia(store)
        val a=training.prepare(TrainingType("rest","Reposo"),"SELECTED",true,30)
        val b=training.prepare(TrainingType("other-secret","PRIVATE_OTHER_TYPE"),"PRIVATE_OTHER",true,30)
        training.transcript(a.id,100,200,0,"SELECTED_VOICE",false)
        training.transcript(b.id,100,200,0,"PRIVATE_OTHER_VOICE",false)
        for(id in listOf(a.id,b.id)) {media.create(id);media.update(id){it.put("state","closed")};media.video(id).writeBytes(byteArrayOf(1,2,3))}
        val bytes=ByteArrayOutputStream();Exporter.writeTraining(store,bytes,captureId=a.id)
        val files=mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.toByteArray().inputStream()).use {zip->while(true){val e=zip.nextEntry ?: break;files[e.name]=zip.readBytes()}}
        assertArrayEquals(byteArrayOf(1,2,3),files["telefono/${a.id}/video.mp4"])
        assertFalse(files.keys.any {b.id in it})
        assertFalse(files.containsKey("actuaciones_led.csv"))
        val data=JSONObject(files.getValue("dataset.json").decodeToString())
        assertEquals(1,data.getJSONArray("training_sessions").length())
        assertEquals(1,data.getJSONArray("training_transcripts").length())
        assertFalse(data.toString().contains("PRIVATE_OTHER"))
    }
    @Test fun exportRefusesVideoThatIsStillBeingWritten() {
        val store=store();val training=TrainingStore(store);val media=SessionMedia(store)
        val a=training.prepare(TrainingType("rest","Reposo"),"",true,30)
        media.create(a.id);media.update(a.id){it.put("state","recording")}
        assertThrows(IllegalArgumentException::class.java) {Exporter.writeTraining(store,ByteArrayOutputStream(),captureId=a.id)}
    }
    @Test fun interruptedVideoIsKeptAndNeverReportedAsValid() {
        val media=SessionMedia(store());val id=java.util.UUID.randomUUID().toString()
        media.create(id);media.video(id).writeText("unfinished-mp4")
        media.update(id){it.put("state","recording")};media.recover()
        assertEquals("unfinished-mp4",media.video(id).readText())
        assertEquals("interrupted",media.read(id).getString("state"))
        assertFalse(media.read(id).getBoolean("video_readable"))
        assertThrows(IllegalStateException::class.java) {media.create(id)}
    }
    @Test fun capturePathCannotEscapeArchive() {
        val media=SessionMedia(store())
        for(id in listOf("../secret","a/b","a\\b","","C:\\temp"))assertThrows(IllegalArgumentException::class.java){media.video(id)}
    }
}
