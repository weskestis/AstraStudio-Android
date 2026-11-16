package com.weskestis.astrastudio.project

/** Frame ranges are half-open source frames. Settings take effect on the next note. */
data class SampleZone(
    val asset: Int = -1, val first: Long = 0, val last: Long = 0,
    val loopFirst: Long = 0, val loopLast: Long = 0, val root: Int = 60,
    val pitch: Float = 0f, val gain: Float = 1f, val pan: Float = 0f,
    val attack: Float = .002f, val decay: Float = .3f, val sustain: Float = 1f, val release: Float = .05f,
    val reverse: Boolean = false, val loop: Boolean = false, val oneShot: Boolean = false, val choke: Int = 0,
    val fadeIn: Long = 0, val fadeOut: Long = 0, val crossfade: Long = 0
) {
    fun wire() = listOf(asset.toDouble(),first.toDouble(),last.toDouble(),loopFirst.toDouble(),loopLast.toDouble(),root.toDouble(),
        pitch.toDouble(),gain.toDouble(),pan.toDouble(),attack.toDouble(),decay.toDouble(),sustain.toDouble(),release.toDouble(),
        if(reverse)1.0 else 0.0,if(loop)1.0 else 0.0,if(oneShot)1.0 else 0.0,choke.toDouble())
    fun valid(assets: List<AudioAsset>): Boolean {
        if(asset !in -1..63||root !in 0..127||choke !in 0..8||first<0||last<0||loopFirst<0||loopLast<0)return false
        if(!pitch.isFinite()||pitch !in -48f..48f||!gain.isFinite()||gain !in 0f..2f||!pan.isFinite()||pan !in -1f..1f||
            !attack.isFinite()||attack !in 0f..5f||!decay.isFinite()||decay !in .001f..30f||!sustain.isFinite()||sustain !in 0f..1f||!release.isFinite()||release !in .005f..10f)return false
        if(fadeIn<0||fadeOut<0||crossfade<0)return false
        if(asset<0)return first==0L&&last==0L&&loopFirst==0L&&loopLast==0L&&!loop&&fadeIn==0L&&fadeOut==0L&&crossfade==0L
        val a=assets.firstOrNull{it.id==asset}?:return false
        return first<last&&last<=a.frames&&loopFirst>=first&&loopLast<=last&&loopFirst<loopLast&&!(loop&&oneShot)&&fadeIn<=last-first&&fadeOut<=last-first&&crossfade<=(loopLast-loopFirst)/2
    }
    fun load(a: AudioAsset)=copy(asset=a.id,first=0,last=a.frames,loopFirst=0,loopLast=a.frames,fadeIn=0,fadeOut=0,crossfade=0)
    fun edges()=listOf(fadeIn.toDouble(),fadeOut.toDouble(),crossfade.toDouble())
    fun fullWire()=wire()+edges()
    fun range(first: Long,last: Long,loopFirst: Long,loopLast: Long)=copy(first=first,last=last,loopFirst=loopFirst,loopLast=loopLast,fadeIn=fadeIn.coerceAtMost(last-first),fadeOut=fadeOut.coerceAtMost(last-first),crossfade=crossfade.coerceAtMost((loopLast-loopFirst)/2))
}
data class SampleInstrument(val mode: Int = 0, val sample: SampleZone = SampleZone(),
    val pads: List<SampleZone> = List(16){SampleZone(root=36+it,decay=.4f,sustain=0f,oneShot=true)},
    val layers: List<List<SampleZone>> = List(17){emptyList()}) {
    fun valid(assets: List<AudioAsset>)=mode in 0..2&&pads.size==16&&layers.size==17&&sample.valid(assets)&&pads.all{it.valid(assets)}&&layers.all{it.size<=3&&it.all{z->z.valid(assets)}}
    fun zone(pad: Int,layer: Int=0)=if(layer==0){if(pad<0)sample else pads[pad]}else layers[pad+1][layer-1]
    fun zones(pad: Int)=listOf(zone(pad))+layers[pad+1]
    fun edit(pad: Int,edit: (SampleZone)->SampleZone)=editLayer(pad,0,edit)
    fun editLayer(pad: Int,layer: Int,edit: (SampleZone)->SampleZone): SampleInstrument {
        require(pad in -1..15&&layer in zones(pad).indices)
        return if(layer==0){if(pad<0)copy(sample=edit(sample))else copy(pads=pads.toMutableList().also{it[pad]=edit(it[pad])})}
        else copy(layers=layers.toMutableList().also{rows->rows[pad+1]=rows[pad+1].toMutableList().also{it[layer-1]=edit(it[layer-1])}})
    }
    fun addLayer(pad: Int): SampleInstrument {require(zones(pad).size<4){"Each pad or sampler holds four layers."};return copy(layers=layers.toMutableList().also{it[pad+1]=it[pad+1]+SampleZone(root=if(pad<0)60 else 36+pad,oneShot=pad>=0,sustain=if(pad<0)1f else 0f)})}
    fun removeLayer(pad: Int,layer: Int): SampleInstrument {
        require(layer in zones(pad).indices)
        if(layer==0)return editLayer(pad,0){SampleZone(root=if(pad<0)60 else 36+pad,oneShot=pad>=0)}
        return copy(layers=layers.toMutableList().also{it[pad+1]=it[pad+1].filterIndexed{i,_->i!=layer-1}})
    }
    fun allZones()=listOf(sample)+pads+layers.flatten()
    fun assets()=allZones().map{it.asset}.filter{it>=0}.toSet()
    fun mapAssets(ids: Map<Int,Int>)=copy(sample=sample.copy(asset=ids[sample.asset]?:sample.asset),pads=pads.map{it.copy(asset=ids[it.asset]?:it.asset)},layers=layers.map{row->row.map{it.copy(asset=ids[it.asset]?:it.asset)}})
    fun extensionWire()=buildList<Double>{for(pad in -1..15){addAll(zone(pad).edges());add(layers[pad+1].size.toDouble());layers[pad+1].forEach{addAll(it.fullWire())}}}
    fun hasExtensions()=layers.any{it.isNotEmpty()}||allZones().any{it.fadeIn>0||it.fadeOut>0||it.crossfade>0}
}
