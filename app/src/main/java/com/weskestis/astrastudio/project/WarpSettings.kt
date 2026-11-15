package com.weskestis.astrastudio.project

object WarpSettings {
    fun trim(clip: Clip,first: Long,last: Long,asset: AudioAsset,bpm: Float): Clip {
        require(clip.isAudio&&first>=0&&last>first&&last<=asset.frames)
        val downbeat=(clip.first+clip.warpStart).coerceIn(first,last-1)
        val beats=if(clip.warp)(last-downbeat).toDouble()/asset.rate*clip.sourceBpm/60 else (last-first).toDouble()/asset.rate*bpm/60
        return clip.resize(beats.coerceAtLeast(.0625)).copy(first=first,last=last,warpStart=downbeat-first)
    }
    fun apply(project: Project,slot: Int,enabled: Boolean,bpm: Double,downbeat: Long,pitch: Double): Project {
        val clip=requireNotNull(project.clip(slot)){"Select an audio clip."}
        require(clip.isAudio){"Warp needs an audio clip. Resample MIDI first."}
        require(bpm.isFinite()&&bpm in 30.0..300.0){"Source tempo must be 30–300 BPM."}
        require(pitch.isFinite()&&pitch in -24.0..24.0){"Pitch must be within −24 to +24 semitones."}
        require(downbeat in clip.first until clip.last){"Downbeat must be inside the selected source frames."}
        return project.put(clip.copy(warp=enabled,sourceBpm=bpm,warpStart=downbeat-clip.first,pitch=pitch))
    }
    fun fittedTempo(clip: Clip,asset: AudioAsset,downbeat: Long): Double {
        require(downbeat in clip.first until clip.last)
        return clip.length*60*asset.rate/(clip.last-downbeat)
    }
    fun sourceFrame(clip: Clip,asset: AudioAsset,phase: Double,audioOffset: Long=0): Double =
        clip.first+clip.warpStart+audioOffset+phase*60/clip.sourceBpm*asset.rate
}
