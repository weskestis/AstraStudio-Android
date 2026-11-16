package com.weskestis.astrastudio.project

/** Storage capacity is separate from the fixed live audio page budget. */
object AudioStorage {
    const val MAX_FILE_BYTES=2L*1024*1024*1024
    const val MAX_PROJECT_BYTES=8L*1024*1024*1024
    const val MAX_ASSET_FRAMES=268435456L
    const val MAX_PROJECT_FRAMES=1073741824L
    const val MAX_RENDER_FRAMES=(MAX_FILE_BYTES-44)/8
    fun canAdd(project: Project,frames: Long)=project.assets.size<64&&frames in 1..MAX_RENDER_FRAMES&&frames<=MAX_PROJECT_FRAMES-project.assets.sumOf{it.frames}
}
data class StorageStats(val audioBytes: Long=0,val sourceBytes: Long=0,val streamed: Int=0,val missedFrames: Long=0,val diskReads: Long=0,val readErrors: Int=0)
