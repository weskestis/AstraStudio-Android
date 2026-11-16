package com.weskestis.astrastudio.project

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlin.math.ceil

data class FinishedExport(val name: String,val path: String,val bytes: Long,val modified: Long)
object ExportNames {
    fun stem(title: String)=title.replace(Regex("[^A-Za-z0-9 _-]"),"_").trim().take(64).ifBlank{"Astra track"}
    fun bytes(project: Project,bpm: Float,rate: Int,tail: Float,floating: Boolean): Long {
        require(project.regions.isNotEmpty()&&rate in listOf(44100,48000)&&tail.isFinite()&&tail in 0f..30f)
        val frames=ceil(project.timelineSeconds(0.0,project.endBeat,bpm)*rate).toLong()+ceil(tail*rate.toDouble()).toLong()
        require(frames>0&&frames<=(0xffffffffL-36)/(if(floating)8 else 6)){"This track is too long for a standard WAV file."}
        return 44+frames*(if(floating)8 else 6)
    }
}
/** Completed exports are kept until the user deletes them; share recipients may read later. */
class FinishedTracks(private val context: Context) {
    val directory=File(context.filesDir,"finished-tracks").canonicalFile.also{check(it.isDirectory||it.mkdirs()){"Could not create finished-track folder."}}
    fun list()=directory.listFiles().orEmpty().filter{it.isFile&&it.extension=="wav"}.sortedByDescending{it.lastModified()}
        .map{FinishedExport(it.name,it.absolutePath,it.length(),it.lastModified())}
    fun begin(title: String,bytes: Long): File {
        require(directory.usableSpace>=bytes+64L*1024*1024){"Free more storage before rendering this track."}
        return File(directory,ExportNames.stem(title)+"_"+UUID.randomUUID().toString().take(8)+".part")
    }
    fun complete(part: File): File {
        require(part.canonicalFile.parentFile==directory.canonicalFile&&part.extension=="part"&&part.length()>44)
        val target=File(directory,part.nameWithoutExtension+".wav")
        check(!target.exists()&&part.renameTo(target)){"Could not finalize the finished track."};return target
    }
    fun file(path: String): File=File(path).canonicalFile.also{
        require(it.parentFile==directory.canonicalFile&&it.extension=="wav"&&it.isFile){"Choose a completed track."}
    }
    fun delete(path: String){val f=file(path);check(f.delete()){ "Could not delete the export." }}
    fun sendIntent(path: String): Intent {
        val f=file(path);val uri=FileProvider.getUriForFile(context,context.packageName+".finished",f)
        return Intent(Intent.ACTION_SEND).apply{
            type="audio/wav";putExtra(Intent.EXTRA_STREAM,uri);putExtra(Intent.EXTRA_SUBJECT,f.nameWithoutExtension)
            clipData=ClipData.newUri(context.contentResolver,f.name,uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
