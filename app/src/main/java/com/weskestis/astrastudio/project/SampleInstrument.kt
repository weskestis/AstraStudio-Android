package com.weskestis.astrastudio.project

/** Frame ranges are half-open source frames. Settings take effect on the next note. */
data class SampleZone(
    val asset: Int = -1, val first: Long = 0, val last: Long = 0,
    val loopFirst: Long = 0, val loopLast: Long = 0, val root: Int = 60,
    val pitch: Float = 0f, val gain: Float = 1f, val pan: Float = 0f,
    val attack: Float = .002f, val decay: Float = .3f, val sustain: Float = 1f, val release: Float = .05f,
    val reverse: Boolean = false, val loop: Boolean = false, val oneShot: Boolean = false, val choke: Int = 0
) {
    fun wire() = listOf(asset.toDouble(),first.toDouble(),last.toDouble(),loopFirst.toDouble(),loopLast.toDouble(),root.toDouble(),
        pitch.toDouble(),gain.toDouble(),pan.toDouble(),attack.toDouble(),decay.toDouble(),sustain.toDouble(),release.toDouble(),
        if(reverse)1.0 else 0.0,if(loop)1.0 else 0.0,if(oneShot)1.0 else 0.0,choke.toDouble())
    fun valid(assets: List<AudioAsset>): Boolean {
        if(asset !in -1..63||root !in 0..127||choke !in 0..8||first<0||last<0||loopFirst<0||loopLast<0)return false
        if(!pitch.isFinite()||pitch !in -48f..48f||!gain.isFinite()||gain !in 0f..2f||!pan.isFinite()||pan !in -1f..1f||
            !attack.isFinite()||attack !in 0f..5f||!decay.isFinite()||decay !in .001f..30f||!sustain.isFinite()||sustain !in 0f..1f||!release.isFinite()||release !in .005f..10f)return false
        if(asset<0)return first==0L&&last==0L&&loopFirst==0L&&loopLast==0L&&!loop
        val a=assets.firstOrNull{it.id==asset}?:return false
        return first<last&&last<=a.frames&&loopFirst>=first&&loopLast<=last&&loopFirst<loopLast&&!(loop&&oneShot)
    }
    fun load(a: AudioAsset)=copy(asset=a.id,first=0,last=a.frames,loopFirst=0,loopLast=a.frames)
}
data class SampleInstrument(val mode: Int = 0, val sample: SampleZone = SampleZone(),
    val pads: List<SampleZone> = List(16){SampleZone(root=36+it,decay=.4f,sustain=0f,oneShot=true)}) {
    fun valid(assets: List<AudioAsset>)=mode in 0..2&&pads.size==16&&sample.valid(assets)&&pads.all{it.valid(assets)}
    fun zone(pad: Int)=if(pad<0)sample else pads[pad]
    fun edit(pad: Int,edit: (SampleZone)->SampleZone)=if(pad<0)copy(sample=edit(sample)) else copy(pads=pads.toMutableList().also{it[pad]=edit(it[pad])})
    fun assets()=(listOf(sample)+pads).map{it.asset}.filter{it>=0}.toSet()
}
