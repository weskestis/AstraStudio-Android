package com.weskestis.astrastudio.astra

import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.synth.SynthPatch
import com.weskestis.astrastudio.synth.SynthSchema as S
import com.weskestis.astrastudio.fx.FxSchema as F
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.floor

data class AstraPlan(val summary: String, val changes: List<String>, val document: ProjectDocument, val json: String)
data class AstraState(
    val working: Boolean = false, val status: String = "Type a command, speak, or choose an example.",
    val summary: String = "", val changes: List<String> = emptyList(), val json: String = "",
    val proposal: Long = 0, val revision: Long = -1, val keySaved: Boolean = false,
    val audioName: String = "", val audioBytes: Int = 0, val history: List<String> = emptyList(),
)

/** A pure, bounded transaction compiler. No IO, JNI, transport control or mutation of the input. */
object AstraCommands {
    val sources = listOf("off","lfo1","lfo2","lfo3","lfo4","env1","env2","env3","velocity","key")
    val targets = listOf("osc_a_position","osc_b_position","fm_amount","pitch","filter1_cutoff","filter2_cutoff","amplitude","osc_a_warp","osc_b_warp","filter1_resonance","filter2_resonance","fm_index","filter1_drive","filter2_drive","osc_a_level","osc_b_level")
    val shapes = listOf("sine","down","up","square")
    val allowed = linkedMapOf(
        "tempo" to setOf("bpm"),
        "pattern" to setOf("track","scene","style","root","scale","bars","seed","name","replace"),
        "midi_clip" to setOf("track","scene","name","length","notes","root","scale","replace"),
        "preset" to setOf("track","preset"),
        "synth" to setOf("track","parameter","value"),
        "lfo" to setOf("track","lfo","beats","shape","retrigger"),
        "modulation" to setOf("track","slot","source","destination","amount"),
        "sidechain" to setOf("sourceTrack","targetTrack","threshold","ratio","attack","release","mix"),
        "mix" to setOf("track","volume","pan","mute","solo","sendA","sendB"),
        "effect" to setOf("track","parameter","value"),
        "place" to setOf("track","scene","beat"),
    )
    private fun JSONObject.keysOnly(names: Set<String>) {
        require(keys().asSequence().all { it in names }) { "Unknown JSON field. Check Astra's command format." }
    }
    private fun JSONObject.num(key: String, low: Double, high: Double, default: Double? = null): Double {
        if (!has(key) && default != null) return default
        val n = opt(key) as? Number ?: error("'$key' must be a number.")
        return n.toDouble().also { require(it.isFinite() && it in low..high) { "'$key' must be between $low and $high." } }
    }
    private fun JSONObject.int(key: String, low: Int, high: Int, default: Int? = null): Int {
        val n = num(key,low.toDouble(),high.toDouble(),default?.toDouble()); require(n == floor(n)) { "'$key' must be a whole number." }; return n.toInt()
    }
    private fun JSONObject.str(key: String, default: String? = null, limit: Int = 64): String {
        if (!has(key) && default != null) return default
        val s = opt(key) as? String ?: error("'$key' must be text.")
        require(s.isNotBlank() && s.length <= limit && s.none { it.isISOControl() && !(key=="summary" && it in "\n\t") }) { "'$key' is empty, too long or contains a control character." }; return s
    }
    private fun JSONObject.bool(key: String, default: Boolean = false): Boolean = if(!has(key)) default else opt(key) as? Boolean ?: error("'$key' must be true or false.")
    private fun JSONObject.track() = int("track",1,16)-1
    private fun JSONObject.slot() = (int("scene",1,8)-1)*16 + track()
    private fun JSONObject.minor(): Boolean = when(str("scale","minor")) { "minor" -> true; "major" -> false; else -> error("Scale must be minor or major.") }
    private fun replaceClip(d: ProjectDocument, c: Clip, replace: Boolean, changes: MutableList<String>): ProjectDocument {
        val old=d.project.clip(c.slot)
        require(old==null || replace) { "Track ${c.track+1}, scene ${c.scene+1} already contains '${old?.name}'. Choose an empty scene or explicitly request replacement." }
        require(old?.isAudio != true) { "Astra cannot replace audio clips. Choose an empty MIDI slot." }
        require(d.project.regions.none { it.slot==c.slot && it.offset>=c.length }) { "Existing arrangement offsets exceed the new clip length." }
        changes += "${if(old==null)"Create" else "Replace"} ${c.name}: ${c.notes.size} notes, ${c.length} beats on track ${c.track+1}, scene ${c.scene+1}${if(old!=null)" (all its arrangement regions use the new notes)" else ""}."
        return d.copy(project=d.project.put(c))
    }
    fun compile(raw: String, original: ProjectDocument, presets: List<SynthPatch>): AstraPlan {
        require(original.valid() && presets.size == S.presets.size)
        val root=StrictJson.objectFrom(raw);root.keysOnly(setOf("version","summary","commands"));require(root.int("version",1,1)==1)
        val summary=root.str("summary",limit=4000)
        val commands=root.opt("commands") as? JSONArray ?: error("'commands' must be an array.")
        require(commands.length()<=32) { "Astra accepts at most 32 commands per proposal." }
        var d=original;val changes=mutableListOf<String>()
        repeat(commands.length()) { index ->
            try {
                val c=commands.opt(index) as? JSONObject ?: error("Each command must be an object.")
                val op=c.str("op");val fields=allowed[op] ?: error("Unsupported operation '$op'.")
                c.keysOnly(fields+"op")
                when(op) {
                    "tempo" -> { val bpm=c.num("bpm",30.0,300.0).toFloat();d=d.copy(bpm=bpm);changes+="Tempo: ${original.bpm} → $bpm BPM (whole project)." }
                    "pattern" -> {
                        val slot=c.slot();val style=c.str("style");require(style in PatternGenerator.styles) { "Unsupported pattern style." }
                        val key=c.str("root","F");val minor=c.minor();val name=c.str("name","$key ${if(minor)"minor" else "major"} $style")
                        var clip=PatternGenerator.create(slot,style,key,minor,c.int("bars",1,16,4),d.project.barBeats,c.int("seed",0,1000000,0),name)
                        if(d.project.instruments[slot%16].mode==2){
                            val pitch=mapOf("kick" to 36,"snare" to 38,"hats" to 42)[style]
                            if(pitch!=null)clip=clip.copy(notes=clip.notes.map{it.copy(pitch=pitch)})
                            require(clip.notes.all{it.pitch in 36..51}){"This track is a drum rack (MIDI 36–51). Use a drum pattern or choose a synth/sampler track for the bassline."}
                        }
                        d=replaceClip(d,clip,c.bool("replace"),changes)
                    }
                    "midi_clip" -> {
                        val slot=c.slot();val length=c.num("length",.0625,65536.0);val key=PatternGenerator.root(c.str("root","F"))
                        val notes=c.opt("notes") as? JSONArray ?: error("'notes' must be an array.");require(notes.length() in 1..256) { "A MIDI clip needs 1–256 notes." }
                        val list=(0 until notes.length()).map { i ->
                            val n=notes.opt(i) as? JSONObject ?: error("Each note must be an object.");n.keysOnly(setOf("beat","length","pitch","velocity"))
                            MidiNote(i+1,n.num("beat",0.0,length),n.num("length",1.0/960,length),n.int("pitch",0,127),n.num("velocity",.001,1.0,.85).toFloat())
                        }
                        require(list.all { it.beat+it.length<=length+1e-8 }) { "A note extends past the clip end." }
                        d=replaceClip(d,Clip(slot,c.str("name","Astra phrase"),length,list.sortedBy{it.beat},root=key,minor=c.minor()),c.bool("replace"),changes)
                    }
                    "preset" -> {
                        val t=c.track();val preset=c.str("preset");val i=S.presets.indexOfFirst{it.equals(preset,true)};require(i>=0) { "Choose one of the eight factory synth presets." }
                        d=d.copy(project=d.project.instrument(t){it.copy(mode=0)},patches=d.patches.toMutableList().also{it[t]=presets[i]});changes+="Track ${t+1}: load ${S.presets[i]} (selects the synth and replaces its complete patch; sample assignments are retained)."
                    }
                    "synth" -> {
                        val t=c.track();require(d.project.instruments[t].mode==0){"Select a synth preset before changing synth parameters on a sample track."};val name=c.str("parameter");val id=S.keys.indexOf(name);require(id>=0) { "Unknown synth parameter '$name'." }
                        val spec=S.parameters[id];val n=c.num("value",spec.minimum.toDouble(),spec.maximum.toDouble());val value=n.toFloat()
                        require(!spec.integer || n==floor(n)) { "'$name' needs an integer." }
                        d=d.copy(patches=d.patches.toMutableList().also{it[t]=it[t].parameter(id,value)});changes+="Track ${t+1}: ${spec.name} = $value."
                    }
                    "lfo" -> {
                        val t=c.track();require(d.project.instruments[t].mode==0){"LFO commands target a synth track. Select a synth preset first."};val lfo=c.int("lfo",1,4)-1;val beats=c.num("beats",.0625,16.0).toFloat();val shape=shapes.indexOf(c.str("shape","sine"));require(shape>=0){"LFO shape must be sine, down, up or square."}
                        val p=d.patches[t].parameter(S.Lfo1Beats+lfo*4,beats).parameter(S.Lfo1Sync+lfo*4,1f).parameter(S.Lfo1Retrigger+lfo*4,if(c.bool("retrigger",true))1f else 0f).shape(lfo,shape)
                        d=d.copy(patches=d.patches.toMutableList().also{it[t]=p});changes+="Track ${t+1}: LFO ${lfo+1}, ${shapes[shape]}, $beats beats per cycle, ${if(c.bool("retrigger",true))"note retrigger" else "free running"}."
                    }
                    "modulation" -> {
                        val t=c.track();require(d.project.instruments[t].mode==0){"Modulation commands target a synth track. Select a synth preset first."};val source=sources.indexOf(c.str("source"));val target=targets.indexOf(c.str("destination"));require(source>=0 && target>=0){"Unknown modulation source or destination."}
                        val amount=c.num("amount",-1.0,1.0).toFloat();val p=d.patches[t]
                        val existing=(0 until S.MOD_SLOTS).firstOrNull{p.wire[p.routeOffset(it)].toInt()==source&&p.wire[p.routeOffset(it)+1].toInt()==target}
                        val slot=if(c.has("slot"))c.int("slot",1,16)-1 else existing ?: (0 until S.MOD_SLOTS).firstOrNull{p.wire[p.routeOffset(it)]==0f} ?: error("All modulation slots are occupied. Specify a slot to replace.")
                        val next=p.route(slot,0,source.toFloat()).route(slot,1,target.toFloat()).route(slot,2,amount)
                        d=d.copy(patches=d.patches.toMutableList().also{it[t]=next});changes+="Track ${t+1}, modulation slot ${slot+1}: ${sources[source]} → ${targets[target]}, amount $amount."
                    }
                    "sidechain" -> {
                        val source=c.int("sourceTrack",1,16)-1;val target=c.int("targetTrack",1,16)-1;require(source!=target){"External sidechain needs two different tracks."}
                        var fx=d.effects[target].parameter(F.ScOn,1f).parameter(F.ScSource,source.toFloat())
                        val knobs=listOf(Triple("threshold",F.ScThreshold,-28.0),Triple("ratio",F.ScRatio,6.0),Triple("attack",F.ScAttack,1.0),Triple("release",F.ScRelease,100.0),Triple("mix",F.ScMix,1.0))
                        for((key,id,default)in knobs){val spec=F.parameters[id];fx=fx.parameter(id,c.num(key,spec.minimum.toDouble(),spec.maximum.toDouble(),default).toFloat())}
                        d=d.copy(effects=d.effects.toMutableList().also{it[target]=fx});changes+="Track ${source+1} drives track ${target+1}'s sidechain: ${fx[F.ScThreshold]} dB, ${fx[F.ScRatio]}:1, ${fx[F.ScAttack]} ms attack, ${fx[F.ScRelease]} ms release."
                    }
                    "mix" -> {
                        val t=c.track();val old=d.tracks[t];require(c.length()>2){"Supply at least one mixer value."}
                        val next=old.copy(gain=c.num("volume",0.0,2.0,old.gain.toDouble()).toFloat(),pan=c.num("pan",-1.0,1.0,old.pan.toDouble()).toFloat(),mute=c.bool("mute",old.mute),solo=c.bool("solo",old.solo),sendA=c.num("sendA",0.0,1.0,old.sendA.toDouble()).toFloat(),sendB=c.num("sendB",0.0,1.0,old.sendB.toDouble()).toFloat())
                        d=d.copy(tracks=d.tracks.toMutableList().also{it[t]=next});changes+="Track ${t+1} mixer: ${c.keys().asSequence().filter{it!="op"&&it!="track"}.joinToString{ "$it=${c.get(it)}" }}."
                    }
                    "effect" -> {
                        val t=c.track();val name=c.str("parameter");val id=F.keys.indexOf(name);require(id>=0){"Unknown effect parameter '$name'."};require(id!=F.ScSource){"Use the sidechain operation to route tracks."}
                        val spec=F.parameters[id];val n=c.num("value",spec.minimum.toDouble(),spec.maximum.toDouble());val value=n.toFloat();require(!spec.integer||n==floor(n)){"'$name' needs an integer."}
                        // Construct and validate the whole wire; never silently clamp invalid band thresholds.
                        val old=d.effects[t];val fx=old.copy(name="Custom / Astra",wire=old.wire.toMutableList().also{it[id]=value})
                        d=d.copy(effects=d.effects.toMutableList().also{it[t]=fx});changes+="Track ${t+1}: $name = $value."
                    }
                    "place" -> { val slot=c.slot();val at=c.num("beat",0.0,65536.0);d=d.copy(project=d.project.place(slot,at));changes+="Place track ${slot%16+1}, scene ${slot/16+1} at beat $at in Arrangement." }
                }
                require(d.valid()){ "The command would create an invalid project or exceed its capacity." }
            } catch(e: IllegalArgumentException) { throw IllegalArgumentException("Command ${index+1}: ${e.message}",e) }
              catch(e: IllegalStateException) { throw IllegalArgumentException("Command ${index+1}: ${e.message}",e) }
        }
        return AstraPlan(summary,changes,d,root.toString(2))
    }

    fun envelope(summary: String, commands: List<JSONObject>) = JSONObject().put("version",1).put("summary",summary).put("commands",JSONArray(commands)).toString()
    fun command(op: String, vararg values: Pair<String,Any>): JSONObject = JSONObject().put("op",op).also { j->values.forEach{j.put(it.first,it.second)} }
}
