package com.weskestis.astrastudio.project

object Resampling {
    fun source(d: ProjectDocument,slot: Int,whole: Boolean): ProjectDocument {
        require(d.valid())
        if(whole){require(d.project.regions.isNotEmpty()){ "Place clips in Arrangement first." };return d}
        val clip=d.project.clip(slot)?:error("Select a clip to resample.")
        // Isolate this clip while retaining its instrument, rack, sends and master.
        return d.copy(project=d.project.copy(regions=listOf(Region(1,slot,0.0,clip.length)),mode=1,loop=false,tempoEnabled=false),
            tracks=d.tracks.mapIndexed{i,t->t.copy(solo=i==clip.track,mute=false)})
    }
    fun checkCapacity(d: ProjectDocument,source: ProjectDocument,rate: Int,tail: Float) {
        require(rate in listOf(44100,48000,96000,192000)&&tail.isFinite()&&tail in 0f..30f)
        val frames=ceilFrames(source.project.timelineSeconds(0.0,source.project.endBeat,source.bpm)*rate)+ceilFrames(tail*rate.toDouble())
        require(AudioStorage.canAdd(d.project,frames)){"This bounce exceeds the 2 GiB WAV, 8 GiB source-frame or 64 sample limits. Shorten the phrase or tail."}
        require(d.project.assets.size<64){"This project already contains 64 samples."}
    }
    private fun ceilFrames(value: Double)=kotlin.math.ceil(value).toLong()
}
