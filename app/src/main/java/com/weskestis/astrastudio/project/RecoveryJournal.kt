package com.weskestis.astrastudio.project

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class RecoveryEntry(val token: String,val name: String,val savedAt: Long)

/** Serialized on the project I/O executor. Each replacement is a synced file plus same-directory rename. */
class RecoveryJournal(private val directory: File) {
    private val current=File(directory,"current.astra.json")
    private val previous=(1..3).map{File(directory,"previous-$it.astra.json")}
    var notice: String?=null; private set
    private fun read(file: File): ByteArray {
        require(file.length()<=ProjectCodec.MAX_JSON_BYTES){"Autosave is too large."}
        return file.inputStream().use{it.readNBytes(ProjectCodec.MAX_JSON_BYTES+1)}.also{require(it.size<=ProjectCodec.MAX_JSON_BYTES)}
    }
    private fun decode(bytes: ByteArray)=ProjectCodec.decode(bytes.toString(Charsets.UTF_8))
    private fun token(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun atomic(file: File,bytes: ByteArray){
        directory.mkdirs();val temp=File(directory,file.name+".pending")
        try{FileOutputStream(temp).use{it.write(bytes);it.fd.sync()}
            Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }finally{temp.delete()}
    }
    private fun quarantine(file: File){
        val saved=File(directory,"damaged-${System.currentTimeMillis()}-${file.name}")
        Files.move(file.toPath(),saved.toPath(),StandardCopyOption.ATOMIC_MOVE)
        directory.listFiles{f->f.name.startsWith("damaged-")&&f.name.endsWith(".astra.json")}?.sortedByDescending{it.lastModified()}?.drop(3)?.forEach{it.delete()}
    }
    fun load(accept: (ProjectDocument)->Unit={}): ProjectDocument? {
        notice=null;var failures=0;var quarantined=false
        for(file in listOf(current)+previous){
            if(!file.exists())continue
            try{val d=decode(read(file));accept(d)
                if(file!=current)notice="Recovered ${d.project.name} from a previous autosave."+if(quarantined)" The damaged copy was preserved."else ""
                return d
            }catch(e: Exception){failures++;if(file==current)quarantined=runCatching{quarantine(file)}.isSuccess}
        }
        check(failures==0){"No valid autosave with readable audio remains. Damaged data was preserved; open a portable project."}
        return null
    }
    fun save(d: ProjectDocument){
        val bytes=ProjectCodec.encode(d).toByteArray(Charsets.UTF_8)
        val oldTime=current.lastModified()
        val old=if(current.exists())runCatching{read(current).also{decode(it)}}.getOrNull()else null
        if(old!=null&&old.contentEquals(bytes))return
        if(old!=null){for(i in previous.lastIndex downTo 1)if(previous[i-1].exists()){
                val data=runCatching{read(previous[i-1]).also{decode(it)}}.getOrNull();if(data!=null){val time=previous[i-1].lastModified();atomic(previous[i],data);previous[i].setLastModified(time)}
            };atomic(previous[0],old);previous[0].setLastModified(oldTime)
        }else if(current.exists())quarantine(current)
        atomic(current,bytes)
    }
    fun entries(): List<RecoveryEntry> = previous.mapNotNull{file->runCatching{
        val bytes=read(file);val d=decode(bytes);RecoveryEntry(token(bytes),d.project.name,file.lastModified())
    }.getOrNull()}.distinctBy{it.token}
    fun restore(expected: String,accept: (ProjectDocument)->Unit={}): ProjectDocument {
        for(file in previous){val bytes=runCatching{read(file)}.getOrNull()?:continue
            if(token(bytes)==expected){val d=decode(bytes);accept(d);return d}}
        error("This recovery copy changed. Close and reopen Recovery to refresh the list.")
    }
}
