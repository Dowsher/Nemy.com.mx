package org.pipalab.phone

import kotlin.math.*

data class LearningExample(val id:String,val label:String,val features:List<Double?>)
data class ActivityGuess(val label:String?,val distance:Double?,val separation:Double,val reason:String)
data class LearningEvaluation(val total:Int,val answered:Int,val correct:Int,val balancedAccuracy:Double,
    val recalls:Map<String,Double>,val confusion:Map<String,Map<String,Int>>) {
    val coverage get()=if(total==0)0.0 else answered.toDouble()/total
}

/** Fixed prototype learner. Normalization and rejection radii use TRAINING sessions only. */
class ActivityModel private constructor(val examples:List<LearningExample>) {
    companion object {
        const val VERSION="session-prototypes-v1"
        val names=listOf("dynamic_rms","norm_sd","jerk_rms","norm_p95","axis_x","axis_y","axis_z","active_fraction","pulse_mean","pulse_sd","skin_mean")
        private val floors=doubleArrayOf(.15,.1,1.0,.15,.08,.08,.08,.05,5.0,2.0,.3)
        private val weights=doubleArrayOf(1.0,1.0,.7,.5,.5,.5,.5,.7,.25,.15,.1)
        fun fit(examples:List<LearningExample>):ActivityModel {
            require(examples.isNotEmpty() && examples.map{it.id}.distinct().size==examples.size)
            require(examples.all{it.label.isNotBlank() && it.features.size==names.size && it.features.take(8).all{v->v!=null && v.isFinite()} && it.features.all{v->v==null || v.isFinite()}})
            return ActivityModel(examples.toList())
        }
    }
    val counts=examples.groupingBy{it.label}.eachCount()
    val scale=names.indices.map {i-> val values=examples.mapNotNull{it.features[i]}; val mean=values.average();maxOf(floors[i],if(values.size<2)0.0 else sqrt(values.sumOf{(it-mean).pow(2)}/values.size))}
    private val centers=examples.groupBy{it.label}.mapValues{(_,rows)->names.indices.map{i->rows.mapNotNull{it.features[i]}.takeIf{it.isNotEmpty()}?.average()}}
    private fun distance(a:List<Double?>,b:List<Double?>):Double {
        var sum=0.0;var weight=0.0
        for(i in names.indices){val x=a[i];val y=b[i];if(x!=null && y!=null){sum+=weights[i]*((x-y)/scale[i]).pow(2);weight+=weights[i]}}
        return if(weight>0)sqrt(sum/weight) else Double.POSITIVE_INFINITY
    }
    private val radii=centers.mapValues{(label,center)->
        val distances=examples.filter{it.label==label}.map{distance(it.features,center)}.sorted()
        // Conservative floor for sparse classes; never estimated from evaluation data.
        maxOf(.7,distances[(distances.size*.9).toInt().coerceAtMost(distances.lastIndex)]*2.0+.15)
    }
    fun predict(features:List<Double?>):ActivityGuess {
        if(features.size!=names.size || features.take(8).any{it==null || !it.isFinite()})return ActivityGuess(null,null,0.0,"insufficient_signal")
        if(counts.size<2 || counts.values.any{it<2})return ActivityGuess(null,null,0.0,"insufficient_examples")
        val ranked=centers.map{(label,center)->label to distance(features,center)}.sortedBy{it.second}
        val best=ranked[0];val second=ranked[1].second
        val margin=(second-best.second)/maxOf(second,.00001)
        if(best.second>radii.getValue(best.first))return ActivityGuess(null,best.second,margin,"outside_training_range")
        if(margin<.2)return ActivityGuess(null,best.second,margin,"ambiguous")
        return ActivityGuess(best.first,best.second,margin,"prototype_match")
    }
    fun evaluate(reserved:List<LearningExample>):LearningEvaluation {
        require(reserved.none{r->examples.any{it.id==r.id}}){"Una sesión no puede entrenar y evaluar el mismo modelo"}
        require(reserved.map{it.id}.distinct().size==reserved.size)
        val confusion=linkedMapOf<String,MutableMap<String,Int>>();var answered=0;var correct=0
        for(row in reserved){val guess=predict(row.features).label; if(guess!=null)answered++;if(guess==row.label)correct++
            val cells=confusion.getOrPut(row.label){linkedMapOf()};val key=guess ?: "unknown";cells[key]=(cells[key] ?: 0)+1}
        val recalls=confusion.mapValues{(label,cells)->(cells[label] ?: 0).toDouble()/cells.values.sum()}
        return LearningEvaluation(reserved.size,answered,correct,recalls.values.takeIf{it.isNotEmpty()}?.average() ?: 0.0,recalls,confusion)
    }
    fun eligible(evaluation:List<LearningExample>):Boolean {
        val evCounts=evaluation.groupingBy{it.label}.eachCount()
        if(counts.size<2 || counts.values.any{it<5} || counts.keys.any{(evCounts[it] ?: 0)<2} || evCounts.keys.any{it !in counts})return false
        val score=evaluate(evaluation)
        return score.balancedAccuracy>=.7 && score.coverage>=.6 && score.recalls.values.all{it>=.5}
    }
}
