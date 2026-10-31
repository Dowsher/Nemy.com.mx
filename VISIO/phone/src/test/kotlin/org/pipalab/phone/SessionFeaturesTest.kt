package org.pipalab.phone
import org.junit.Assert.*
import org.junit.Test
class SessionFeaturesTest {
    private fun motion()=(0..299).map{i->Sample(i*40L,1_000_000_000L+i*40_000_000L,mapOf("x_m_s2" to "0","y_m_s2" to "0","z_m_s2" to "9.80665"))}
    @Test fun staticMotionKeepsMissingHealthMissing(){
        val features=SessionFeatures.extract(motion(),emptyList(),emptyList())!!
        assertEquals(0.0,features[0]!!,.00001);assertEquals(1.0,features[6]!!,.00001)
        assertTrue(features.drop(8).all{it==null})
    }
    @Test fun largeGapAndTooFewSamplesDoNotBecomeExamples(){
        assertNull(SessionFeatures.extract(motion().take(100),emptyList(),emptyList()))
        assertNull(SessionFeatures.extract(motion().mapIndexed{i,s->if(i>=100)s.copy(elapsed=s.elapsed+2_000_000_000L) else s},emptyList(),emptyList()))
    }
    @Test fun invalidHealthStatusesAreNotTrainingValues(){
        val invalid=(0..6).map{i->Sample(i*1000L,i*1_000_000_000L,mapOf("status" to "0","bpm" to "80"))}
        assertNull(SessionFeatures.extract(motion(),invalid,emptyList())!![8])
        val valid=invalid.map{it.copy(values=it.values+mapOf("status" to "1"))}
        assertEquals(80.0,SessionFeatures.extract(motion(),valid,emptyList())!![8]!!,0.0)
    }
}
