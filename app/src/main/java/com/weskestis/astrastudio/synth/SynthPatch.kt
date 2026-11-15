package com.weskestis.astrastudio.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/** Immutable UI/control-thread representation. Native code validates the same wire contract. */
data class SynthPatch(val name: String, val wire: List<Float>) {
    init { require(valid(wire)) { "Invalid synthesis patch" } }
    operator fun get(parameter: Int): Float = wire[parameter]
    fun parameter(id: Int, value: Float): SynthPatch {
        if (id !in SynthSchema.parameters.indices || !value.isFinite()) return this
        val spec = SynthSchema.parameters[id]
        val safe = value.coerceIn(spec.minimum, spec.maximum).let { if (spec.integer) it.roundToInt().toFloat() else it }
        return edit { it[id] = safe }
    }
    fun route(slot: Int, field: Int, value: Float): SynthPatch {
        if (slot !in 0 until SynthSchema.MOD_SLOTS || field !in 0..2 || !value.isFinite()) return this
        val safe = when (field) { 0 -> value.roundToInt().coerceIn(0, 9).toFloat(); 1 -> value.roundToInt().coerceIn(0, 15).toFloat(); else -> value.coerceIn(-1f, 1f) }
        return edit { it[routeOffset(slot) + field] = safe }
    }
    fun paint(lfo: Int, from: Int, fromValue: Float, to: Int, toValue: Float): SynthPatch {
        if (lfo !in 0..3 || !fromValue.isFinite() || !toValue.isFinite()) return this
        val a = from.coerceIn(0, 31); val b = to.coerceIn(0, 31)
        return edit { data ->
            for (i in minOf(a, b)..maxOf(a, b)) {
                val t = if (a == b) 1f else (i - a).toFloat() / (b - a)
                data[SynthSchema.PARAM_COUNT + lfo * 32 + i] = (fromValue + (toValue - fromValue) * t).coerceIn(-1f, 1f)
            }
        }
    }
    fun shape(lfo: Int, kind: Int): SynthPatch = edit { data ->
        if (lfo in 0..3) for (i in 0..31) {
            val phase = i / 32f
            data[SynthSchema.PARAM_COUNT + lfo * 32 + i] = when (kind) {
                1 -> 1f - 2f * phase
                2 -> 2f * phase - 1f
                3 -> if (i < 16) 1f else -1f
                else -> -cos(2.0 * PI * phase).toFloat()
            }
        }
    }
    fun routeOffset(slot: Int) = SynthSchema.PARAM_COUNT + 128 + slot * 3
    fun shapePoints(lfo: Int) = wire.subList(SynthSchema.PARAM_COUNT + lfo * 32, SynthSchema.PARAM_COUNT + (lfo + 1) * 32)
    private fun edit(block: (MutableList<Float>) -> Unit): SynthPatch {
        val next = wire.toMutableList(); block(next)
        return SynthPatch(if (name.startsWith("Custom / ")) name else "Custom / $name", next)
    }
    fun encode(): String = "1|${name.replace('|', ' ').take(80)}|${wire.joinToString(",")}"
    companion object {
        fun initial(): SynthPatch {
            val wire = SynthSchema.parameters.map { it.initial }.toMutableList()
            repeat(4) { repeat(32) { i -> wire.add(-cos(2.0 * PI * i / 32).toFloat()) } }
            repeat(SynthSchema.MOD_SLOTS) { wire.addAll(listOf(0f, 0f, 0f)) }
            return SynthPatch("Init Sine", wire)
        }
        fun decode(text: String?): SynthPatch? {
            if (text == null || text.length > 16000) return null
            val fields = text.split('|', limit = 3)
            if (fields.size != 3 || fields[0] != "1" || fields[1].length > 80) return null
            val values = fields[2].split(',').map { it.toFloatOrNull() ?: return null }
            return if (valid(values)) SynthPatch(fields[1], values) else null
        }
        fun valid(wire: List<Float>): Boolean {
            if (wire.size != SynthSchema.WIRE_COUNT || wire.any { !it.isFinite() }) return false
            SynthSchema.parameters.forEachIndexed { i, spec ->
                val v = wire[i]
                if (v !in spec.minimum..spec.maximum || (spec.integer && v != v.roundToInt().toFloat())) return false
            }
            for (i in SynthSchema.PARAM_COUNT until SynthSchema.PARAM_COUNT + 128) if (wire[i] !in -1f..1f) return false
            repeat(SynthSchema.MOD_SLOTS) { slot ->
                val i = SynthSchema.PARAM_COUNT + 128 + slot * 3
                if (wire[i] !in 0f..9f || wire[i] != wire[i].roundToInt().toFloat() ||
                    wire[i+1] !in 0f..15f || wire[i+1] != wire[i+1].roundToInt().toFloat() || wire[i+2] !in -1f..1f) return false
            }
            return true
        }
    }
}
