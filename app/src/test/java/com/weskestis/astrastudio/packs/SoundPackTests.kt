package com.weskestis.astrastudio.packs

import com.weskestis.astrastudio.recording.*
import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.audio.TrackControls
import com.weskestis.astrastudio.fx.FxConfig
import com.weskestis.astrastudio.synth.SynthPatch
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.*

class SoundPackTests {
    private fun temp(test: (File,SoundPacks)->Unit){val root=Files.createTempDirectory("astra-pack-").toFile();try{test(root,SoundPacks(File(root,"packs")))}finally{root.deleteRecursively()}}
    private fun wav(headroom: Boolean=false): ByteArray {val data=FloatArray(256){if(headroom)1.5f else (it%32-16)/32f};return FloatWaveWriter.header(48000,1,data.size*4L)+ByteBuffer.allocate(data.size*4).order(ByteOrder.LITTLE_ENDIAN).apply{data.forEach(::putFloat)}.array()}
    private fun table(): ByteArray {val body=wav();val chunk="<!>32 ".toByteArray();val result=body.take(36).toByteArray()+"clm ".toByteArray()+ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunk.size).array()+chunk+body.drop(36).toByteArray();ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4,result.size-8);return result}
    private fun pack(count: Int=2,part: Int=1,content: ByteArray=wav(),mutate: (JSONObject)->Unit={}): ByteArray {
        val entries=JSONArray();repeat(count){i->entries.put(JSONObject().put("id","s$i").put("kind",if(content.contentEquals(table()))"wavetable"else "sample").put("cycle",32).put("file","audio/s$i.wav").put("name","Sound $i").put("category",if(i%2==0)"Bass"else "Drums").put("bytes",content.size).put("sha256",MessageDigest.getInstance("SHA-256").digest(content).joinToString(""){"%02x".format(it.toInt() and 255)}).put("rate",48000).put("channels",1).put("frames",256).put("bpm",140).put("key","F#"))}
        val m=JSONObject().put("format","rift-sound-pack").put("version",1).put("id","test-pack").put("name","Test pack").put("creator","Astra tests").put("part",part).put("parts",2).put("entries",entries);mutate(m)
        return ByteArrayOutputStream().also{bytes->ZipOutputStream(bytes).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(m.toString().toByteArray());z.closeEntry();repeat(count){z.putNextEntry(ZipEntry("audio/s$it.wav"));z.write(content);z.closeEntry()}}}.toByteArray()
    }
    private fun install(p: SoundPacks,bytes: ByteArray)=p.install(bytes.inputStream(),"test.riftpack",AtomicBoolean(false))
    private fun rejects(f: ()->Unit){assertTrue(runCatching(f).isFailure)}
    @Test fun installsMoreThanProjectCapacityAndReloads(){temp{_,p->val part=install(p,pack(80));assertEquals(80,part.sounds.size);assertEquals(80,p.catalog().sounds.size);assertEquals(0,Project().assets.size);assertEquals(140.0,part.sounds[0].bpm,0.0);assertEquals("F#",part.sounds[0].key)}}
    @Test fun trailingBytesOutsideRiffDoNotChangeAudio(){temp{_,p->val data=wav()+ByteArray(65536){(-1).toByte()};val sound=install(p,pack(content=data)).sounds.first();assertEquals(256L,sound.frames);assertArrayEquals(data,p.file(p.catalog(),sound).readBytes())}}
    @Test fun allOriginalBytesAndFloatHeadroomRemainIntact(){temp{_,p->val data=wav(true);val part=install(p,pack(content=data));val catalog=p.catalog();assertArrayEquals(data,p.file(catalog,part.sounds[0]).readBytes());assertEquals(1.5f,part.sounds[0].peak,0f)}}
    @Test fun partsAreIndependentAndReinstallDoesNotDuplicate(){temp{_,p->install(p,pack(part=2));install(p,pack(part=1));install(p,pack(part=1));assertEquals(2,p.catalog().packs.size);assertEquals(4,p.catalog().sounds.size)}}
    @Test fun favoritesSurviveFreshCatalogAndReinstall(){temp{root,p->val part=install(p,pack());val key=part.sounds[0].keyId;p.favorite(key);install(p,pack());assertTrue(key in SoundPacks(File(root,"packs")).catalog().favorites);p.favorite(key);assertTrue(p.catalog().favorites.isEmpty())}}
    @Test fun hashFailureLeavesExistingPackUnchanged(){temp{_,p->val part=install(p,pack());rejects{install(p,pack{it.getJSONArray("entries").getJSONObject(0).put("sha256","0".repeat(64))})};assertEquals(part,p.catalog().packs.single())}}
    @Test fun malformedMetadataDoesNotPublishAPart(){temp{_,p->rejects{install(p,pack{it.getJSONArray("entries").getJSONObject(0).put("frames",123)})};assertTrue(p.catalog().packs.isEmpty())}}
    @Test fun wrongCycleCannotPretendToBeWavetable(){temp{_,p->rejects{install(p,pack{it.getJSONArray("entries").getJSONObject(0).put("kind","wavetable")})};assertTrue(p.catalog().packs.isEmpty())}}
    @Test fun tableCyclesArePreserved(){temp{_,p->val sound=install(p,pack(content=table())).sounds.first();assertEquals(32,sound.cycle);assertEquals(8L,sound.frames/sound.cycle);WaveFile(p.file(p.catalog(),sound)).use{assertEquals(32,it.cycle)}}}
    @Test fun cancelNeverPublishesPartialPack(){temp{_,p->val cancel=AtomicBoolean(false);rejects{p.install(pack(3).inputStream(),"test",cancel){_,progress->if(progress>0)cancel.set(true)}};assertTrue(p.catalog().packs.isEmpty())}}
    @Test fun unsafeManifestPathIsRejected(){temp{_,p->rejects{install(p,pack{it.getJSONArray("entries").getJSONObject(0).put("file","../outside.wav")})};assertTrue(p.catalog().packs.isEmpty())}}
    @Test fun duplicateIdsAreRejected(){temp{_,p->rejects{install(p,pack{it.getJSONArray("entries").getJSONObject(1).put("id","s0")})}}}
    @Test fun nonfiniteAudioIsRejected(){temp{_,p->val data=wav();ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putFloat(44,Float.NaN);rejects{install(p,pack(content=data))}}}
    @Test fun originalWavZipIsAcceptedAndNamed(){temp{_,p->val data=ByteArrayOutputStream().also{b->ZipOutputStream(b).use{z->z.putNextEntry(ZipEntry("Drums/Kick.wav"));z.write(wav());z.closeEntry()}}.toByteArray();val sound=install(p,data).sounds.single();assertEquals("Kick",sound.name);assertEquals("Drums",sound.category)}}
    @Test fun unsafeZipPathIsRejected(){temp{root,p->val data=ByteArrayOutputStream().also{b->ZipOutputStream(b).use{z->z.putNextEntry(ZipEntry("../outside.wav"));z.write(wav());z.closeEntry()}}.toByteArray();rejects{install(p,data)};assertFalse(File(root,"outside.wav").exists())}}
    @Test fun missingInstalledAudioGetsRepairMessage(){temp{_,p->val sound=install(p,pack()).sounds.first();val c=p.catalog();p.file(c,sound).delete();rejects{p.file(c,sound)};install(p,pack());assertTrue(p.file(p.catalog(),sound).isFile)}}
    @Test fun interruptedDirectorySwapRestoresOldIndex(){temp{root,p->val part=install(p,pack());val folder=File(root,"packs");File(folder,part.directory).renameTo(File(folder,".previous-${part.directory}"));val pending=File(folder,".install-test").also{it.mkdir()};File(pending,"partial").writeText("partial");p.repairInterruptedInstalls();assertEquals(2,p.catalog().sounds.size);assertFalse(pending.exists())}}
    private fun doc(p: Project)=ProjectDocument(p,120f,.7f,0f,80f,List(50){TrackControls()},List(50){SynthPatch.initial()},List(50){FxConfig.initial()})
    private fun tableProject(): Project {val a=AudioAsset(0,"Table","table.wav",44100,2048*256L,List(1024){0f},cycle=2048);return Project(assets=listOf(a),tables=List(50){if(it==49)listOf(0,0)else listOf(-1,-1)})}
    @Test fun tablesRoundTripAndWireTargetsTrackFifty(){val d=doc(tableProject());assertTrue(d.valid());assertEquals(d,ProjectCodec.decode(ProjectCodec.encode(d)));assertArrayEquals(doubleArrayOf(0.0,0.0),d.project.nativeWire().takeLast(2).toDoubleArray(),0.0)}
    @Test fun legacyPhase12DefaultsToFactoryTables(){val d=doc(Project());val j=JSONObject(ProjectCodec.encode(d)).put("version",7);j.remove("tables");assertEquals(d,ProjectCodec.decode(j.toString()))}
    @Test fun invalidTableAssignmentsAreRejected(){val p=tableProject();assertFalse(p.copy(assets=p.assets.map{it.copy(cycle=0)}).valid());assertFalse(p.copy(tables=List(50){listOf(63,-1)}).valid());assertFalse(p.copy(assets=p.assets.map{it.copy(cycle=300)}).valid())}
    @Test fun changingCustomTableInvalidatesFreeze(){val p=tableProject().put(Clip(49,length=2.0).add(0.0,48,1.0)).place(49,0.0);val d=doc(p);assertNotEquals(FrozenTracks.fingerprint(d,49),FrozenTracks.fingerprint(d.copy(project=p.copy(tables=List(50){listOf(-1,-1)})),49))}
    @Test fun filenamePitchDefaultsRemainEditable(){assertEquals(66,PackPitch.root("F#min"));assertEquals(70,PackPitch.root("Bb"));assertEquals(60,PackPitch.root(""));assertEquals(69,PackPitch.root("A"))}
}

/** Optional local audit runner: user-owned audio is never committed or uploaded to public CI. */
object SoundPackAudit {
    @JvmStatic fun main(args: Array<String>){
        require(args.size==2);val sources=File(args[0]).listFiles()!!.filter{it.extension=="riftpack"}.sortedBy{it.name};require(sources.size==7)
        val library=SoundPacks(File(args[1]));val report=JSONArray()
        sources.forEach{file->val start=System.nanoTime();val p=file.inputStream().use{library.install(it,file.name,AtomicBoolean(false))};println("PASS ${file.name}: ${p.sounds.size} sounds");report.put(JSONObject().put("file",file.name).put("sounds",p.sounds.size).put("seconds",(System.nanoTime()-start)/1e9))}
        val c=library.catalog();check(c.sounds.size==1200);check(c.sounds.count{it.isTable}==6);check(c.sounds.count{!it.isTable}==1194)
        File(args[1],"audit.json").writeText(JSONObject().put("parts",report).put("samples",1194).put("wavetables",6).put("sourceBytes",c.sounds.sumOf{it.bytes}).put("status","passed").toString(2))
        println("PASS all 1,200 sounds: CRC, SHA-256, PCM decoding, finite values, metadata and retained source bytes")
    }
}
