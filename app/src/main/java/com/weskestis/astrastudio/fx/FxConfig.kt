package com.weskestis.astrastudio.fx

import com.weskestis.astrastudio.project.ProjectLimits

import kotlin.math.roundToInt

data class FxConfig(val name: String, val wire: List<Float>) {
    init { require(valid(wire)) { "Invalid effects configuration" } }
    operator fun get(id: Int) = wire[id]
    fun parameter(id: Int, value: Float): FxConfig {
        if (id !in FxSchema.parameters.indices || !value.isFinite()) return this
        val spec = FxSchema.parameters[id]
        return edit { data ->
            var safe = value.coerceIn(spec.minimum,spec.maximum)
            if (spec.integer) safe = safe.roundToInt().toFloat()
            repeat(3) { band ->
                if (id == FxSchema.Ott1Lower + band*3) safe = minOf(safe, data[FxSchema.Ott1Upper+band*3]-1)
                if (id == FxSchema.Ott1Upper + band*3) safe = maxOf(safe, data[FxSchema.Ott1Lower+band*3]+1)
            }
            data[id] = safe
        }
    }
    fun move(unit: Int, direction: Int): FxConfig = edit { data ->
        val at = order.indexOf(unit); val next = at + direction
        if (at in 0..7 && next in 0..7) {
            val a=FxSchema.PARAM_COUNT+at; val b=FxSchema.PARAM_COUNT+next
            val old=data[a];data[a]=data[b];data[b]=old
        }
    }
    fun lane(unit: Int, lane: Int): FxConfig = edit { if (unit in 0..7) it[FxSchema.PARAM_COUNT+8+unit]=lane.coerceIn(0,1).toFloat() }
    fun macro(index: Int, field: Int, value: Float): FxConfig {
        if (index !in 0..3 || field !in 0..2 || !value.isFinite()) return this
        val safe=when(field){0->value.roundToInt().coerceIn(0,16).toFloat();1->value.coerceIn(-1f,1f);else->value.coerceIn(0f,1f)}
        return edit { it[macroOffset(index)+field]=safe }
    }
    val order get()=wire.subList(FxSchema.PARAM_COUNT,FxSchema.PARAM_COUNT+8).map { it.toInt() }
    fun lane(unit: Int)=wire[FxSchema.PARAM_COUNT+8+unit].toInt()
    fun macroOffset(index: Int)=FxSchema.PARAM_COUNT+16+index*3
    fun effective(): List<Float> {
        val data=wire.take(FxSchema.PARAM_COUNT).toMutableList()
        repeat(4){m->val at=macroOffset(m);val target=wire[at].toInt()
            if(target>0){val id=macroTargets[target-1];val spec=FxSchema.parameters[id];data[id]+=wire[at+1]*wire[at+2]*(spec.maximum-spec.minimum)}}
        return data.mapIndexed { i,v->v.coerceIn(FxSchema.parameters[i].minimum,FxSchema.parameters[i].maximum) }
    }
    private fun edit(action: (MutableList<Float>)->Unit): FxConfig {
        val data=wire.toMutableList();action(data)
        return FxConfig(if(name.startsWith("Custom / ")) name else "Custom / $name",data)
    }
    fun encode()="1|${name.replace('|',' ').take(80)}|${wire.joinToString(",")}"
    companion object {
        val enabled=listOf(FxSchema.EqOn,FxSchema.OttOn,FxSchema.ShaperOn,FxSchema.ScOn,FxSchema.DelayOn,FxSchema.ReverbOn,FxSchema.ChorusOn,FxSchema.ModOn)
        val macroTargets=listOf(FxSchema.ShaperPre,FxSchema.DelayMix,FxSchema.ReverbMix,FxSchema.ChorusDepth,FxSchema.OttDepth,FxSchema.ScThreshold,FxSchema.ModDepth,
            FxSchema.Eq1Gain,FxSchema.Eq2Gain,FxSchema.Eq3Gain,FxSchema.Eq4Gain,FxSchema.Eq5Gain,FxSchema.Eq6Gain,FxSchema.Eq7Gain,FxSchema.Eq8Gain,FxSchema.RackOutput)
        fun initial(): FxConfig {
            val data=FxSchema.parameters.map { it.initial }.toMutableList()
            data.addAll((0..7).map{it.toFloat()});repeat(20){data.add(0f)}
            return FxConfig("Clean",data)
        }
        fun decode(text: String?): FxConfig? {
            if(text==null||text.length>12000)return null
            val fields=text.split('|',limit=3)
            if(fields.size!=3||fields[0]!="1"||fields[1].length>80)return null
            val data=fields[2].split(',').map{it.toFloatOrNull()?:return null}
            return if(valid(data))FxConfig(fields[1],data) else null
        }
        fun valid(data: List<Float>): Boolean {
            if(data.size!=FxSchema.WIRE_COUNT||data.any{!it.isFinite()})return false
            FxSchema.parameters.forEachIndexed { i,s->val v=data[i];if(v !in s.minimum..s.maximum||(s.integer&&v!=v.roundToInt().toFloat()))return false }
            val order=data.subList(FxSchema.PARAM_COUNT,FxSchema.PARAM_COUNT+8)
            if(order.any{it !in 0f..7f||it!=it.roundToInt().toFloat()}||order.toSet().size!=8)return false
            for(i in FxSchema.PARAM_COUNT+8 until FxSchema.PARAM_COUNT+16)if(data[i]!=0f&&data[i]!=1f)return false
            repeat(4){m->val at=FxSchema.PARAM_COUNT+16+m*3
                if(data[at] !in 0f..16f||data[at]!=data[at].roundToInt().toFloat()||data[at+1] !in -1f..1f||data[at+2] !in 0f..1f)return false}
            repeat(3){if(data[FxSchema.Ott1Lower+it*3]>=data[FxSchema.Ott1Upper+it*3])return false}
            return true
        }
    }
}

data class FxStats(
    val sidechain: List<Float> = List(ProjectLimits.TRACKS){0f}, val bands: List<Float> = List(ProjectLimits.TRACKS*3){0f},
    val limiterReduction: Float = 0f, val latencyFrames: Int = 0,
    val spectrum: List<Float> = List(64){-100f}, val sampleRate: Float = 48000f,
    val meterTrack: Int = -1, val analyzerDrops: Long = 0,
)
