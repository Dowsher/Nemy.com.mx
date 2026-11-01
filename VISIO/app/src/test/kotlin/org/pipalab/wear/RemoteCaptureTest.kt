package org.pipalab.wear

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.pipalab.core.ActivityCapture

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class RemoteCaptureTest {
    private fun plan()=ActivityCapture(java.util.UUID.randomUUID().toString(),"rest","Reposo","",true,30,System.currentTimeMillis())
    @Test fun stopBeforeDelayedStartPersistsAcrossReload() {
        val c=RuntimeEnvironment.getApplication();val p=plan()
        Live.state.value=RecorderStatus();TrainingInbox.pending.value=null
        TrainingInbox.block(c,p.id);TrainingInbox.load(c)
        assertTrue(TrainingInbox.blocked(c,p.id))
        assertEquals("cancelled",TrainingInbox.prepare(c,p))
    }
    @Test fun duplicateRequestCannotCreateSecondRecording() {
        val c=RuntimeEnvironment.getApplication();val p=plan()
        Live.state.value=RecorderStatus();TrainingInbox.pending.value=null
        assertEquals("prepared",TrainingInbox.prepare(c,p));assertEquals("prepared",TrainingInbox.prepare(c,p))
        TrainingInbox.started(c,p.id);TrainingInbox.load(c)
        assertEquals("already_started",TrainingInbox.prepare(c,p))
    }
    @Test fun anotherRequestDoesNotReplacePendingCapture() {
        val c=RuntimeEnvironment.getApplication();val p=plan()
        Live.state.value=RecorderStatus();TrainingInbox.pending.value=null
        assertEquals("prepared",TrainingInbox.prepare(c,p));assertEquals("pending_other",TrainingInbox.prepare(c,plan()))
        assertEquals(p.id,TrainingInbox.pending.value!!.id)
    }
}
