package org.pipalab.phone
import org.junit.Assert.*
import org.junit.Test

class ActivityLearningTest {
    private fun row(id:String,label:String,x:Double)=LearningExample(id,label,List(11){if(it<8)x else null})
    private fun training()=(0..4).map{row("a$it","a",it*.01)}+(0..4).map{row("b$it","b",5+it*.01)}
    @Test fun heldOutSignalsClassifyAndUnfamiliarSignalsAbstain(){
        val model=ActivityModel.fit(training())
        assertEquals("a",model.predict(row("new","a",.03).features).label)
        assertNull(model.predict(row("far","a",500.0).features).label)
        assertNull(model.predict(row("middle","a",2.52).features).label)
        val close=ActivityModel.fit(listOf(row("ca1","a",0.0),row("ca2","a",0.0),row("cb1","b",.1),row("cb2","b",.1)))
        assertEquals("ambiguous",close.predict(row("tie","a",.05).features).reason)
    }
    @Test fun rejectsEvaluationLeakageAndDuplicateSessions(){
        val model=ActivityModel.fit(training())
        assertThrows(IllegalArgumentException::class.java){model.evaluate(listOf(training().first()))}
        assertThrows(IllegalArgumentException::class.java){ActivityModel.fit(training()+training().first())}
    }
    @Test fun evaluationCannotChangeTrainingNormalization(){
        val model=ActivityModel.fit(training());val before=model.scale.toList()
        model.evaluate(listOf(row("extreme","a",1000000.0)))
        assertEquals(before,model.scale)
        assertEquals("a",model.predict(row("new","a",.02).features).label)
    }
    @Test fun minimumEvidenceAndWrongExamplesBlockPromotion(){
        val model=ActivityModel.fit(training())
        val evaluation=listOf(row("ea1","a",.015),row("ea2","a",.025),row("eb1","b",5.015),row("eb2","b",5.025))
        assertTrue(model.eligible(evaluation))
        assertFalse(model.eligible(evaluation.take(1)))
        assertFalse(model.eligible(evaluation.map{it.copy(label=if(it.label=="a")"b" else "a")}))
        assertFalse(ActivityModel.fit(training().filter{it.id!="a0"}).eligible(evaluation))
    }
}
