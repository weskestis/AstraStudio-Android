package com.weskestis.astrastudio.astra

import com.weskestis.astrastudio.project.ProjectLimits
import com.weskestis.astrastudio.project.Clip
import java.util.Random
import java.util.Locale

/** Original deterministic phrase algorithms, not a language model or sampled audio. */
object PatternGenerator {
    val styles = listOf("riddim", "psytrance", "trance", "house", "kick", "snare", "hats")
    fun root(name: String): Int {
        val n = name.trim().replace("♯", "#").replace("♭", "b").lowercase(Locale.ROOT)
        return mapOf("c" to 0,"c#" to 1,"db" to 1,"d" to 2,"d#" to 3,"eb" to 3,"e" to 4,"fb" to 4,"e#" to 5,"f" to 5,"f#" to 6,"gb" to 6,"g" to 7,"g#" to 8,"ab" to 8,"a" to 9,"a#" to 10,"bb" to 10,"b" to 11,"cb" to 11,"b#" to 0)[n] ?: error("Use a note name such as F, C# or Bb.")
    }
    fun create(slot: Int, style: String, root: String, minor: Boolean, bars: Int, barBeats: Double, seed: Int, name: String): Clip {
        require(slot in 0 until ProjectLimits.CLIPS && style in styles && bars in 1..16)
        val key = PatternGenerator.root(root); val length = bars * barBeats
        var clip = Clip(slot, name, length, root=key, minor=minor)
        val random = Random(seed.toLong())
        val scale = if (minor) intArrayOf(0,2,3,5,7,8,10) else intArrayOf(0,2,4,5,7,9,11)
        fun add(at: Double, pitch: Int, duration: Double, velocity: Float) {
            if (at < length) clip = clip.add(at, pitch, minOf(duration,length-at),velocity)
        }
        val step = when (style) { "psytrance", "trance" -> .25; "hats" -> .5; else -> 1.0 }
        var i = 0
        while (i*step < length) {
            val at = i*step; val inBar = at % barBeats
            when (style) {
                "psytrance" -> if (i%4 != 0) add(at,24+key+(if(i%16==15&&random.nextBoolean())12 else 0),.16f.toDouble(),if(i%4==1).9f else .72f)
                "trance" -> { val degree = intArrayOf(0,2,4,6,4,2,1,4)[(i + seed.mod(8))%8]; add(at,60+key+scale[degree],.19,if(i%4==0).84f else .65f) }
                "riddim" -> { val shift = if(i%4==3&&random.nextBoolean()).25 else 0.0; val degree = if(random.nextInt(4)==0)intArrayOf(2,4,6)[random.nextInt(3)]else 0
                    add(at+shift,24+key+scale[degree],if(i%4==0).72 else .42,if(i%4==0).94f else .80f) }
                "house" -> add(at+.5,36+key+if(i%8==7)scale[4] else 0,.33,.84f)
                "kick" -> add(at,36,.4,.95f)
                "snare" -> if(inBar>=barBeats*.5 && inBar<barBeats*.5+1.0) add(at,50,.45,.92f)
                "hats" -> add(at,66,.10,if(i%2==0).55f else .78f)
            }
            i++
        }
        require(clip.notes.isNotEmpty()) { "This time signature is too short for that pattern." }
        return clip
    }
}
