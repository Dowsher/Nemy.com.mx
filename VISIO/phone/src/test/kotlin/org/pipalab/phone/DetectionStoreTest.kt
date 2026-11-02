package org.pipalab.phone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.util.UUID

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34])
class DetectionStoreTest {
    @Test fun duplicateWatchAnswerDoesNotDuplicateAuditOrOverwritePhoneCorrection() {
        val db=DetectionStore(Store.get(RuntimeEnvironment.getApplication()));val id=UUID.randomUUID().toString()
        db.add(id,"session-a","training-a",100,JSONObject().put("score",.75))
        assertTrue(db.review(id,"confirmed",source="watch",responseId="response-1"))
        assertTrue(db.review(id,"confirmed",source="watch",responseId="response-1"))
        db.review(id,"false_positive",note="Fue beber agua")
        db.review(id,"confirmed",source="watch",responseId="response-late")
        assertEquals("false_positive",db.events("session-a").single().getString("label"))
        val exported=db.export(setOf("session-a"));assertEquals(3,exported.getJSONArray("reviews").length())
        val audit=exported.getJSONArray("reviews");val late=(0 until audit.length()).map{audit.getJSONObject(it)}.single{it.getString("id")=="response-late"}
        assertEquals("0",late.getString("applied"))
        assertEquals(0,db.export(setOf("session-b")).getJSONArray("reviews").length())
        assertFalse(db.review("absent","confirmed",source="watch"))
    }
    @Test fun unansweredEventsStayPendingAndPromptBudgetSurvivesNewInstance() {
        val store=Store.get(RuntimeEnvironment.getApplication());val db=DetectionStore(store)
        val now=System.currentTimeMillis()
        repeat(5){i->db.add("budget-$i","budget","training",now+i,JSONObject())}
        assertTrue(db.prompt("budget-0",now));assertFalse(db.prompt("budget-0",now))
        assertFalse(DetectionStore(store).prompt("budget-1",now+1000))
        assertTrue(db.prompt("budget-1",now+2_700_000));assertTrue(db.prompt("budget-2",now+5_400_000));assertTrue(db.prompt("budget-3",now+8_100_000))
        assertFalse(db.prompt("budget-4",now+10_800_000))
        assertEquals("pending",db.events("budget").last().getString("label"))
    }
}
