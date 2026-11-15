package com.weskestis.astrastudio.astra

import com.weskestis.astrastudio.project.ProjectDocument
import com.weskestis.astrastudio.synth.SynthSchema
import org.json.JSONObject
import java.util.Locale
import com.weskestis.astrastudio.astra.AstraCommands.command as cmd

/** Explicit small grammar. Unsupported or ambiguous sentences fail instead of partially executing. */
object OfflineCommands {
    val examples = listOf(
        "Write a syncopated 145 BPM Riddim quarter-note bassline in F minor",
        "Create a rolling 138 BPM Psytrance bassline in F minor",
        "Load a metallic FM growl on Track 2 and map LFO 1 to the Comb Filter cutoff",
        "Route Track 1 Kick to the Sidechain input of the Sub Bass on Track 3",
        "Set tempo to 128 BPM",
    )
    private fun pattern(s: String) = Regex(s,RegexOption.IGNORE_CASE)
    fun compile(raw: String, selectedSlot: Int, d: ProjectDocument): String {
        require(selectedSlot in 0..127 && raw.length<=8000)
        val text=raw.trim().trimEnd('.').replace(Regex("\\s+")," ")
        require(text.isNotBlank()) { "Type a music command first." }
        if(text.startsWith("{")||text.startsWith("```json")) return raw
        val out=mutableListOf<JSONObject>()
        pattern("(?:set (?:the )?)?(?:tempo|bpm)(?: to)? ([0-9]+(?:\\.[0-9]+)?)(?: bpm)?").matchEntire(text)?.let {
            out+=cmd("tempo", "bpm" to it.groupValues[1].toDouble())
            return AstraCommands.envelope("Set the project tempo.",out)
        }
        val phrase=pattern("(?:write|create|generate|make)(?: an?)?(?: syncopated| rolling)?(?: ([0-9]+(?:\\.[0-9]+)?) bpm)? (riddim|dubstep|psytrance|trance|house|kick|snare|hats|hi-hat)(?: quarter-note)?(?: bassline| bass line| pattern| beat| melody| arpeggio)?(?: in ([a-g](?:#|b| sharp| flat)?)(?: (minor|major))?)?(?: (?:on|for) track ([0-9]+))?(?: (?:in|at) scene ([0-9]+))?(?: (?:for|over) ([0-9]+) bars?)?")
        phrase.matchEntire(text)?.let { match ->
            val g=match.groupValues
            fun integer(value: String, fallback: Int): Int = if(value.isEmpty())fallback else value.toIntOrNull()?:error("That number is too large.")
            val track=integer(g[5],selectedSlot%16+1);require(track in 1..16){"Choose track 1–16."}
            val scene=if(g[6].isNotEmpty())integer(g[6],1) else if(d.project.clip(selectedSlot)==null && track==selectedSlot%16+1) selectedSlot/16+1 else (d.project.emptySlot(track-1)?.div(16)?.plus(1)?:error("Track $track has no empty scene. Select another track or use JSON with explicit replacement."))
            val style=when(g[2].lowercase(Locale.ROOT)){"dubstep"->"riddim";"hi-hat"->"hats";else->g[2].lowercase(Locale.ROOT)}
            if(g[1].isNotEmpty())out+=cmd("tempo","bpm" to g[1].toDouble())
            out+=cmd("pattern","track" to track,"scene" to scene,"style" to style,"root" to g[3].ifBlank{"F"}.replace(" sharp","#",true).replace(" flat","b",true),"scale" to g[4].ifBlank{"minor"}.lowercase(Locale.ROOT),"bars" to integer(g[7],4),"seed" to 0)
            return AstraCommands.envelope("Create a $style phrase in an empty clip slot. The track's current synth is retained.",out)
        }
        pattern("load (?:a |an )?(.+?) (?:on|onto) track ([0-9]+)(?: and map lfo ([0-9]+) to (?:the )?comb filter cutoff)?").matchEntire(text)?.let {
            val requested=it.groupValues[1];val name=SynthSchema.presets.firstOrNull{p->p.equals(requested,true)}?:when(requested.lowercase(Locale.ROOT)){
                "metallic fm growl","metallic growl"->"FM Alloy";"riddim bass"->"Liquid Riddim";"trance lead","supersaw"->"Trance Prism";"sub bass"->"Sub Pressure";"donk"->"FM Donk";else->error("Use an exact factory preset name or ask Gemini to design a patch.")}
            val track=it.groupValues[2].toInt();out+=cmd("preset","track" to track,"preset" to name)
            if(it.groupValues[3].isNotEmpty()) {
                val lfo=it.groupValues[3].toInt();require(lfo in 1..4){"Choose LFO 1–4."}
                out+=cmd("synth","track" to track,"parameter" to "Filter1Type","value" to 4)
                out+=cmd("synth","track" to track,"parameter" to "Filter1Cutoff","value" to 320)
                out+=cmd("lfo","track" to track,"lfo" to lfo,"beats" to 1,"shape" to "down","retrigger" to true)
                out+=cmd("modulation","track" to track,"source" to "lfo$lfo","destination" to "filter1_cutoff","amount" to .4)
            }
            return AstraCommands.envelope("Load $name${if(out.size>1)" with a tempo-synced comb-filter movement" else ""}.",out)
        }
        pattern("route track ([0-9]+)(?: [a-z][a-z0-9 _-]{0,30}?)? to (?:the )?sidechain(?: input)? (?:of (?:the )?(?:[a-z][a-z0-9 _-]{0,30} )?on |of |on |to )?track ([0-9]+)").matchEntire(text)?.let{
            out+=cmd("sidechain","sourceTrack" to it.groupValues[1].toInt(),"targetTrack" to it.groupValues[2].toInt())
            return AstraCommands.envelope("Enable external kick-driven sidechain compression.",out)
        }
        error("That wording is outside the offline grammar. Try an example, paste command JSON, or use Ask Gemini for flexible requests. Nothing has changed.")
    }
}
