package com.weskestis.astrastudio

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.weskestis.astrastudio.audio.AudioBridge
import com.weskestis.astrastudio.audio.AudioService
import com.weskestis.astrastudio.recording.*
import androidx.test.rule.ServiceTestRule
import android.content.Intent
import com.weskestis.astrastudio.project.*
import com.weskestis.astrastudio.synth.SynthSchema
import com.weskestis.astrastudio.astra.*
import com.weskestis.astrastudio.astra.AstraCommands.command as cmd
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import androidx.test.rule.GrantPermissionRule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class WorkspaceSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @get:Rule val notifications=GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @After fun captureFinalState(){runCatching{shot("99-last-screen")};runCatching{compose.onAllNodes(isRoot())[0].printToLog("ASTRA_UI_TEST")}}
    private fun shot(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        // UTP uninstalls the app after the test, removing its private directory.
        // MediaStore output survives that cleanup and is collected from the emulator.
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,"Pictures/AstraStudio")
            put(android.provider.MediaStore.Images.Media.IS_PENDING,1)
        }
        val resolver=context.contentResolver
        val uri=requireNotNull(resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values))
        requireNotNull(resolver.openOutputStream(uri)).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        values.clear();values.put(android.provider.MediaStore.Images.Media.IS_PENDING,0);resolver.update(uri,values,null,null)
    }
    // Compose 1.6's performScrollTo reads layout geometry on the instrumentation
    // thread. Recording meters can invalidate that geometry between idle and read.
    // Fetch after synchronization, then measure and request scrolling on the UI thread.
    private fun SemanticsNodeInteraction.scrollOnUiThread(): SemanticsNodeInteraction {
        val node=fetchSemanticsNode()
        compose.runOnUiThread {
            val parent=generateSequence(node.parent){it.parent}.firstOrNull{it.config.contains(SemanticsActions.ScrollBy)}
                ?: throw AssertionError("No scrollable parent")
            val coordinates=parent.layoutInfo.coordinates
            val viewport=coordinates.boundsInParent().translate(coordinates.parentLayoutCoordinates?.positionInRoot()?:Offset.Zero)
            val bounds=Rect(node.positionInRoot,node.size.toSize())
            fun distance(before: Float,after: Float)=if(before*after>0) {
                if(kotlin.math.abs(before)<kotlin.math.abs(after))before else after
            } else 0f
            var dx=distance(bounds.left-viewport.left,bounds.right-viewport.right)
            var dy=distance(bounds.top-viewport.top,bounds.bottom-viewport.bottom)
            if(parent.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.reverseScrolling==true)dx=-dx
            if(parent.layoutInfo.layoutDirection==LayoutDirection.Rtl)dx=-dx
            if(parent.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.reverseScrolling==true)dy=-dy
            if(dx!=0f||dy!=0f)check(parent.config[SemanticsActions.ScrollBy].action?.invoke(dx,dy)==true)
        }
        compose.waitForIdle()
        return this
    }
    private fun click(tag: String){val node=compose.onNodeWithTag(tag);runCatching{node.scrollOnUiThread()};node.assertIsDisplayed().performClick()}
    @Test fun fiftyTracksPickerArchivePlaybackAndMasterExport(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("New project");click("Confirm project")
        click("Choose track");compose.onNodeWithTag("Track picker").performScrollToIndex(49);click("Select track 50")
        compose.onNodeWithTag("Choose track").assertTextContains("Track 50 / 50",substring=true)
        compose.waitUntil(10000){compose.onAllNodesWithTag("Empty 49").fetchSemanticsNodes().isNotEmpty()}
        shot("26-fifty-tracks-session-phone")
        click("Choose track");back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val binding=ServiceTestRule();val service=(binding.bindService(Intent(context,AudioService::class.java)) as AudioService.LocalBinder).service
        val source=File(context.cacheDir,"capacity-55Hz.wav");val archive=File(context.cacheDir,"capacity.astra");val wave=File(context.cacheDir,"capacity192.wav")
        val io=ProjectFiles(context);var restored: ProjectDocument?=null
        FloatWaveWriter(source,48000,1).use{writer->val samples=FloatArray(12000){(.002*kotlin.math.sin(2*Math.PI*55*it/48000)).toFloat()};writer.write(samples,samples.size)}
        try{
            service.tempo(120f);compose.waitUntil(10000){service.state.value.bpm==120f}
            service.importAudio(Uri.fromFile(source),49);compose.waitUntil(20000){service.state.value.project.clip(49)?.isAudio==true&&!service.state.value.busy}
            service.projectEdit{p->val c=p.clip(49)!!;p.copy(clips=List(50){c.copy(slot=it,name="Layer ${it+1}")},regions=List(50){Region(it+1,it,0.0,c.length)})}
            compose.waitUntil(10000){service.state.value.project.regions.size==50}
            click("Arrangement");compose.waitUntil(10000){compose.onAllNodesWithTag("Timeline track 50").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("Timeline track 50").assertIsDisplayed();shot("27-fifty-tracks-arrangement-phone")
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithTag("Timeline track 50").assertIsDisplayed();shot("28-fifty-tracks-landscape")
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed();compose.onNodeWithTag("Choose track").assertTextContains("Track 50 / 50",substring=true)
            val state=service.state.value
            val doc=ProjectDocument(state.project,state.bpm,state.master,state.limiterDrive,state.limiterRelease,state.tracks,state.patches,state.effects)
            val cancel=AtomicBoolean(false);io.saveArchive(doc,Uri.fromFile(archive),cancel);restored=io.openArchive(Uri.fromFile(archive),cancel)
            assertEquals(50,restored.tracks.size);assertEquals(doc.project.clips,restored.project.clips);assertEquals(doc.project.regions,restored.project.regions)
            val bridge=AudioBridge()
            try{
                assertTrue(bridge.loadAssets(restored.project.assets.map{it.id to io.audioFile(it)}))
                assertEquals(0,bridge.export(wave,restored,192000,0f,false))
                val head=ByteBuffer.wrap(wave.inputStream().use{it.readNBytes(44)}).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(192000,head.getInt(24));assertEquals(24,head.getShort(34).toInt());assertEquals(288044L,wave.length())
                WaveFile(wave).use{f->val data=FloatArray(24000);f.read(12000,12000,data);assertTrue(data.any{kotlin.math.abs(it)>.01f})}
                assertEquals(0,bridge.start());assertTrue(bridge.project(restored.project));assertTrue(bridge.tempo(120f));assertTrue(bridge.transport(true));assertTrue(bridge.scene(0,0f));assertTrue(bridge.meterTrack(49))
                compose.waitUntil(10000){bridge.sequence().active[49]==49&&bridge.fxStats().meterTrack==49}
                assertEquals(50,bridge.sequence().active.size);assertEquals(150,bridge.fxStats().bands.size)
                assertTrue(bridge.meterTrack(50));compose.waitUntil(10000){bridge.fxStats().meterTrack==50}
                bridge.stop()
            }finally{bridge.close()}
            service.undo();compose.waitUntil(10000){service.state.value.project.regions.isEmpty()}
            service.redo();compose.waitUntil(10000){service.state.value.project.regions.size==50}
        }finally{
            restored?.project?.assets?.forEach{io.audioFile(it).delete()}
            listOf(source,archive,wave).forEach{it.delete()};binding.unbindService()
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        }
    }
    @Test fun master192ControlsRenderAndShareExactPcm24(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("New project");click("Confirm project")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val binding=ServiceTestRule();val service=(binding.bindService(Intent(context,AudioService::class.java)) as AudioService.LocalBinder).service
        val source=File(context.cacheDir,"Master192_55Hz.wav");val saved=File(context.cacheDir,"Master192-saved.wav");val exported=File(context.cacheDir,"Master192-export.wav")
        val samples=FloatArray(24000){(.3*kotlin.math.sin(2*Math.PI*55*it/48000)).toFloat()}
        FloatWaveWriter(source,48000,1).use{it.write(samples,samples.size)}
        var completed: FinishedExport?=null
        try{
            service.tempo(120f);compose.waitUntil(10000){service.state.value.bpm==120f}
            service.importAudio(Uri.fromFile(source),0)
            compose.waitUntil(20000){service.state.value.project.clip(0)!=null&&!service.state.value.busy}
            service.projectEdit{it.place(0,0.0)};compose.waitUntil(10000){service.state.value.project.regions.isNotEmpty()}
            click("File");click("Export arrangement");click("Export 192 master")
            compose.onNodeWithTag("Export rate 192000").scrollOnUiThread().assertIsSelected()
            compose.onNodeWithTag("Export PCM24").scrollOnUiThread().assertIsSelected();shot("23-master192-export-phone")
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithTag("Export 192 master").scrollOnUiThread().assertIsSelected();shot("24-master192-export-landscape")
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
            click("File");click("Share finished track");click("Share 192 master")
            compose.onNodeWithTag("Share rate 192000").scrollOnUiThread().assertIsSelected()
            compose.onNodeWithTag("Share PCM24").scrollOnUiThread().assertIsSelected()
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
            compose.onNodeWithTag("Share 192 master").scrollOnUiThread().assertIsSelected()
            val previous=service.state.value.finishedExports.map{it.path}.toSet();click("Render finished track")
            compose.waitUntil(60000){!service.state.value.busy&&service.state.value.finishedExports.any{it.path !in previous}}
            completed=service.state.value.finishedExports.first{it.path !in previous};val store=FinishedTracks(context);val f=store.file(completed!!.path)
            val head=ByteBuffer.wrap(f.inputStream().use{it.readNBytes(44)}).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1,head.getShort(20).toInt());assertEquals(2,head.getShort(22).toInt());assertEquals(192000,head.getInt(24));assertEquals(1152000,head.getInt(28));assertEquals(6,head.getShort(32).toInt());assertEquals(24,head.getShort(34).toInt())
            assertEquals(5184044L,f.length())
            WaveFile(f).use{wave->assertEquals(192000,wave.rate);assertEquals(864000L,wave.frames);val data=FloatArray(96000);wave.read(19200,48000,data);assertTrue(data.any{kotlin.math.abs(it)>.01f})}
            compose.waitUntil(10000){compose.onAllNodesWithTag("Share WAV 0").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("Share WAV 0").scrollOnUiThread().assertIsDisplayed();shot("25-master192-finished")
            val uri=store.sendIntent(f.absolutePath).getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)!!
            context.contentResolver.openInputStream(uri)!!.use{assertArrayEquals(f.readBytes(),it.readBytes())}
            service.saveFinishedTrack(Uri.fromFile(saved),f.absolutePath);compose.waitUntil(10000){saved.length()==f.length()&&!service.state.value.busy};assertArrayEquals(f.readBytes(),saved.readBytes())
            service.exportWave(Uri.fromFile(exported),192000,4f,false);compose.waitUntil(60000){exported.length()==f.length()&&!service.state.value.busy};assertArrayEquals(f.readBytes(),exported.readBytes())
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        }finally{
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
            completed?.let{runCatching{FinishedTracks(context).delete(it.path)}}
            source.delete();saved.delete();exported.delete();binding.unbindService()
        }
    }
    @Test fun streamedAudioBounceAndFinishedTrackSharing(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("New project");click("Confirm project")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val binding=ServiceTestRule();val service=(binding.bindService(Intent(context,AudioService::class.java)) as AudioService.LocalBinder).service
        val source=File(context.cacheDir,"Streaming_55Hz.wav");val saved=File(context.cacheDir,"finished-copy.wav")
        val samples=FloatArray(48000*6){(.3*kotlin.math.sin(2*Math.PI*55*it/48000)).toFloat()}
        FloatWaveWriter(source,48000,1).use{it.write(samples,samples.size)}
        var finished: FinishedExport?=null
        try{
            service.importAudio(Uri.fromFile(source),0)
            compose.waitUntil(20000){service.state.value.project.clip(0)!=null&&!service.state.value.busy}
            compose.waitUntil(10000){service.state.value.storage.streamed>0}
            assertEquals(2L*1024*1024,service.state.value.storage.audioBytes)
            assertEquals(48000L*6*8,service.state.value.storage.sourceBytes)
            click("Resample");click("Bounce instrument")
            compose.waitUntil(20000){service.state.value.project.clip(ProjectLimits.TRACKS)!=null&&!service.state.value.busy}
            val p=service.state.value.project;assertTrue(p.clip(ProjectLimits.TRACKS)!!.isAudio);assertTrue(p.clip(ProjectLimits.TRACKS)!!.warp);assertNotNull(p.clip(0))
            val files=ProjectFiles(context);val bounced=files.audioFile(p.asset(p.clip(ProjectLimits.TRACKS)!!.asset)!!)
            WaveFile(bounced).use{wave->val values=FloatArray(1024);wave.read(1000,512,values);for(i in 0 until 512){assertEquals(samples[1000+i],values[i*2],0f);assertEquals(values[i*2],values[i*2+1],0f)}}
            service.undo();compose.waitUntil(10000){service.state.value.project.clip(ProjectLimits.TRACKS)==null};service.redo();compose.waitUntil(10000){service.state.value.project.clip(ProjectLimits.TRACKS)!=null}
            back();service.projectEdit{it.place(0,0.0)};compose.waitUntil(10000){service.state.value.project.regions.isNotEmpty()}
            click("File");click("Share finished track")
            val previous=service.state.value.finishedExports.map{it.path}.toSet()
            click("Render finished track")
            compose.waitUntil(30000){!service.state.value.busy&&service.state.value.finishedExports.any{it.path !in previous}}
            finished=service.state.value.finishedExports.first{it.path !in previous}
            val store=FinishedTracks(context);val f=store.file(finished!!.path)
            WaveFile(f).use{wave->assertEquals(48000,wave.rate);assertEquals(480000L,wave.frames);val b=FloatArray(48000);wave.read(12000,24000,b);assertTrue(b.any{kotlin.math.abs(it)>.01f})}
            val send=store.sendIntent(f.absolutePath)
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("audio/wav",send.type)
            val uri=requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java))
            assertEquals("content",uri.scheme);assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0);assertEquals(uri,send.clipData!!.getItemAt(0).uri)
            context.contentResolver.openInputStream(uri)!!.use{input->assertArrayEquals(f.readBytes(),input.readBytes())}
            assertTrue(runCatching{store.sendIntent(source.absolutePath)}.isFailure)
            assertTrue("Finished exports keep the same canonical identity when the store is reopened",FinishedTracks(context).list().any{it.path==f.absolutePath})
            compose.waitUntil(10000){compose.onAllNodesWithTag("Share WAV 0").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("Share WAV 0").scrollOnUiThread().assertIsDisplayed()
            shot("21-finished-track-phone")
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithTag("Share WAV 0").scrollOnUiThread().assertIsDisplayed();shot("22-finished-track-landscape")
            click("Share WAV 0")
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.waitUntil(10000){automation.rootInActiveWindow?.packageName?.let{it!=context.packageName}==true}
            assertTrue("Android share sheet accepts Back",automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(10000){automation.rootInActiveWindow?.packageName==context.packageName}
            compose.waitUntil(10000){compose.onAllNodesWithTag("Share WAV 0").fetchSemanticsNodes().isNotEmpty()}
            service.saveFinishedTrack(Uri.fromFile(saved),f.absolutePath)
            compose.waitUntil(10000){saved.exists()&&saved.length()==f.length()&&!service.state.value.busy}
            assertArrayEquals(f.readBytes(),saved.readBytes())
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        }finally{
            compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            finished?.let{runCatching{FinishedTracks(context).delete(it.path)}}
            source.delete();saved.delete();binding.unbindService()
        }
    }
    @Test fun warpControlsUndoRotationArchiveAndNativeExport(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("New project");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithTag("Empty 0").fetchSemanticsNodes().isNotEmpty()}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val binding=ServiceTestRule();val service=(binding.bindService(Intent(context,AudioService::class.java)) as AudioService.LocalBinder).service
        val source=File(context.cacheDir,"Warp_220Hz.wav");val archive=File(context.cacheDir,"warped.astra");val output=File(context.cacheDir,"warped.wav")
        val samples=FloatArray(48000*4){(.4*kotlin.math.sin(2*Math.PI*220*it/48000)).toFloat()}
        FloatWaveWriter(source,48000,1).use{it.write(samples,samples.size)}
        try{
            service.importAudio(Uri.fromFile(source),0)
            compose.waitUntil(20000){service.state.value.project.clip(0)!=null&&!service.state.value.busy}
            click("Warp");click("Warp enabled")
            compose.onNodeWithTag("Warp source BPM").scrollOnUiThread().performTextReplacement("120")
            compose.onNodeWithTag("Warp downbeat").scrollOnUiThread().performTextReplacement("24000")
            compose.onNodeWithTag("Warp pitch").scrollOnUiThread().performTextReplacement("7")
            click("Apply warp")
            compose.waitUntil(10000){service.state.value.project.clip(0)?.warp==true}
            compose.onNodeWithTag("Warp enabled").scrollOnUiThread().assertIsSelected();shot("19-warp-phone")
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithTag("Warp downbeat").scrollOnUiThread().assertTextContains("24000",substring=true)
            compose.onNodeWithTag("Warp pitch").scrollOnUiThread().assertTextContains("7",substring=true);shot("20-warp-landscape")
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
            click("Undo");compose.waitUntil(10000){service.state.value.project.clip(0)?.warp==false}
            click("Redo");compose.waitUntil(10000){service.state.value.project.clip(0)?.warp==true}
            val state=service.state.value;val c=state.project.clip(0)!!
            assertEquals(24000L,c.warpStart);assertEquals(7.0,c.pitch,0.0)
            val p=state.project.put(c.copy(length=4.0)).copy(mode=1,tempoEnabled=true,tempoPoints=listOf(TempoPoint(0.0,120f,true),TempoPoint(2.0,90f))).place(0,0.0)
            val document=ProjectDocument(p,state.bpm,state.master,state.limiterDrive,state.limiterRelease,state.tracks,state.patches,state.effects)
            val files=ProjectFiles(context);files.saveArchive(document,Uri.fromFile(archive),AtomicBoolean(false))
            val restored=files.openArchive(Uri.fromFile(archive),AtomicBoolean(false))
            // Archive import deliberately assigns fresh local filenames, so an
            // existing project's media can never be overwritten by reopening it.
            val remapped=document.copy(project=document.project.copy(assets=document.project.assets.map{a->
                val loaded=requireNotNull(restored.project.asset(a.id))
                assertArrayEquals(files.audioFile(a).readBytes(),files.audioFile(loaded).readBytes())
                a.copy(file=loaded.file)
            }))
            assertEquals(remapped,restored)
            val bridge=AudioBridge()
            try{assertTrue("Load restored audio",bridge.loadAssets(restored.project.assets.map{it.id to files.audioFile(it)}))
                assertEquals("Start live output before sending its project",0,bridge.start())
                assertTrue("Publish warped project to live engine",bridge.project(restored.project))
                assertTrue(bridge.tempo(restored.bpm));assertTrue(bridge.transport(true))
                compose.waitUntil(10000){bridge.sequence().active[0]==0&&bridge.stats().beat>.05}
                assertTrue(bridge.transport(false));bridge.stop()
                assertEquals(0,bridge.export(output,restored,48000,0f,true))
                val expected=kotlin.math.ceil(p.timelineSeconds(0.0,4.0,state.bpm)*48000).toLong()
                WaveFile(output).use{assertEquals(expected,it.frames);val read=FloatArray(96000);val count=it.read(12000,48000,read);assertTrue(count>0);assertTrue(read.any{kotlin.math.abs(it)>.01f})}
                assertTrue(files.audioFile(restored.project.assets.single()).exists())
            }finally{bridge.close()}
        }finally{binding.unbindService();listOf(source,archive,output).forEach{it.delete()}}
    }
    @Test fun recordingLabZoomPitchExtractionAndNavigation(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("New project");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithTag("Empty 0").fetchSemanticsNodes().isNotEmpty()}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val binding=ServiceTestRule();val service=(binding.bindService(Intent(context,AudioService::class.java)) as AudioService.LocalBinder).service
        val fixture=File(context.cacheDir,"Lab_A1_fixture.wav")
        val samples=FloatArray(48000){(.7*kotlin.math.sin(2*Math.PI*55*it/48000)).toFloat()}
        FloatWaveWriter(fixture,48000,1).use{it.write(samples,samples.size)}
        try{
            val previousTake=service.recorder.state.value.latest
            service.importToLab(Uri.fromFile(fixture))
            compose.waitUntil(20000){val latest=service.recorder.state.value.latest;latest.isNotBlank()&&latest!=previousTake&&!service.state.value.busy}
            val original=service.recorder.state.value.latest
            assertArrayEquals(fixture.readBytes(),File(original).readBytes())
            click("Record audio")
            compose.onNodeWithTag("Lab waveform").scrollOnUiThread().assertIsDisplayed()
            click("Lab zoom in");compose.onNodeWithTag("Lab view range").assertTextContains("0.25000 s",substring=true)
            compose.onNodeWithTag("Lab start frame").scrollOnUiThread().performTextReplacement("4800")
            compose.onNodeWithTag("Lab end frame").performTextReplacement("14400")
            click("Lab apply range");click("Lab fit selection")
            compose.onNodeWithTag("Lab selection").assertTextContains("9600 frames",substring=true)
            compose.onNodeWithTag("Lab waveform").scrollOnUiThread();shot("15-recording-waveform")
            click("Lab analyze")
            compose.waitUntil(15000){compose.onAllNodesWithTag("Lab pitch").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("Lab pitch").scrollOnUiThread().assertTextContains("A1",substring=true)
            compose.onNodeWithTag("Lab spectrum").scrollOnUiThread();shot("16-bass-analysis")
            compose.onNodeWithTag("Lab root note").assertTextContains("33",substring=true)
            click("Lab choose source");back();compose.onNodeWithTag("Lab waveform").assertExists()
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            compose.onNodeWithTag("Lab selection").scrollOnUiThread().assertTextContains("9600 frames",substring=true);shot("17-recording-landscape")
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
            click("Lab use sampler")
            compose.waitUntil(20000){service.state.value.project.instruments[0].mode==1&&!service.state.value.busy}
            val zone=service.state.value.project.instruments[0].sample;assertEquals(33,zone.root);assertEquals(9600L,zone.last)
            val asset=service.state.value.project.asset(zone.asset)!!
            WaveFile(File(service.samplePath(asset))).use{w->val cut=FloatArray(9600*2);w.read(0,9600,cut);repeat(9600){assertEquals(samples[it+4800],cut[it*2],0f)}}
            assertTrue(File(original).isFile)
            val saved=File(context.cacheDir,"lab-saved-selection.wav")
            service.saveLabWave(Uri.fromFile(saved),original,4800,14400)
            compose.waitUntil(20000){saved.length()>44&&!service.state.value.busy}
            WaveFile(saved).use{w->assertEquals(9600L,w.frames);val out=FloatArray(9600*2);w.read(0,9600,out);repeat(9600){assertEquals(samples[it+4800],out[it*2],0f)}}
            saved.delete()
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed();click("Undo")
            compose.waitUntil(10000){service.state.value.project.assets.isEmpty()};click("Redo")
            compose.waitUntil(10000){service.state.value.project.instruments[0].mode==1}
            click("Record audio");click("Lab add clip")
            compose.waitUntil(20000){service.state.value.project.clip(0)?.asset?.let{it>=0}==true&&!service.state.value.busy}
            back();compose.onNodeWithTag("Launch 0").scrollOnUiThread().assertExists()
        }finally{fixture.delete();binding.unbindService()}
    }
    @Test fun microphoneSurvivesBackAndSavesWave(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val binding=ServiceTestRule();val service=(binding.bindService(Intent(context,AudioService::class.java)) as AudioService.LocalBinder).service
        try{
            // Actual platform capture lifecycle; emulator input is silent, not a fidelity test.
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName,android.Manifest.permission.RECORD_AUDIO)
            val previous=service.recorder.state.value.latest
            click("Record audio");click("Start microphone")
            try{compose.waitUntil(15000){service.recorder.state.value.active&&service.recorder.state.value.frames>0}}
            catch(e: Exception){throw AssertionError("Microphone: ${service.recorder.state.value}",e)}
            val before=service.recorder.state.value.frames
            back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
            compose.waitUntil(10000){service.recorder.state.value.frames>before}
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            assertTrue(service.recorder.state.value.active)
            compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
            compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
            click("Record audio");click("Stop microphone")
            compose.waitUntil(15000){!service.recorder.state.value.active&&service.recorder.state.value.latest!=previous}
            val expectedRate=service.recorder.state.value.rate
            WaveFile(File(service.recorder.state.value.latest)).use{assertTrue(it.frames>0);assertEquals(expectedRate,it.rate)}
            shot("18-recorded-take");back()
        }finally{service.stopCapture();binding.unbindService()}
    }
    @Test fun createEditUndoArrangeAndRotate(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("Load starter song");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithText("Kick 1").fetchSemanticsNodes().isNotEmpty()}
        click("Audio")
        compose.waitUntil(15000){compose.onAllNodesWithText("Audio on").fetchSemanticsNodes().isNotEmpty()}
        click("Play")
        compose.waitUntil(10000){compose.onAllNodesWithText("▸").fetchSemanticsNodes().isNotEmpty()}
        click("■ Stop")
        shot("01-session-portrait")
        click("Edit clip")
        compose.onNodeWithTag("Piano roll").scrollOnUiThread().assertIsDisplayed().performTouchInput{click(Offset(width*.4f,height*.55f))}
        compose.waitForIdle();shot("02-piano-roll")
        click("Close panel");click("Undo");click("Redo")
        click("Arrangement");compose.onNodeWithTag("Arrangement timeline").assertIsDisplayed();shot("03-arrangement-portrait")
        compose.onNodeWithTag("Region").assertIsNotEnabled()
        compose.onNodeWithTag("Timeline canvas").performTouchInput{click(Offset(width*.6f,35f))}
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Region") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle();shot("03b-region-selection")
        compose.onNodeWithTag("Region").assertIsEnabled()
        click("Region")
        compose.waitUntil(10000){compose.onAllNodesWithText("Arrangement region").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Arrangement region").assertIsDisplayed();compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000){compose.onAllNodesWithTag("Arrangement timeline").fetchSemanticsNodes().isNotEmpty()}
        compose.waitForIdle();shot("04-arrangement-landscape")
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
    }
    @Test fun portableProjectAndNativeExportRoundTrip(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bridge=AudioBridge()
        try {
            val d=StarterProject.create(SynthSchema.presets.indices.map(bridge::preset))
            val io=ProjectFiles(context);val cancel=AtomicBoolean(false)
            val archive=File(context.cacheDir,"test-project.astra")
            io.saveArchive(d,Uri.fromFile(archive),cancel);val restored=io.openArchive(Uri.fromFile(archive),cancel);assertEquals(d,restored)
            val wave=File(context.cacheDir,"test-render.wav")
            // Short native fixture exercises the same exported code used by Oboe.
            val short=d.copy(project=d.project.copy(regions=d.project.regions.filter{it.slot==0}.take(1).map{it.copy(length=2.0)}))
            assertEquals(0,bridge.export(wave,short,48000,.1f));assertTrue(wave.length()>100000)
            val head=wave.inputStream().use{val b=ByteArray(44);assertEquals(44,it.read(b));b}
            assertEquals("RIFF",String(head,0,4));assertEquals(24,ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getShort(34).toInt())
            val imported=io.importAudio(Uri.fromFile(wave),cancel)
            assertTrue(bridge.loadAssets(listOf(0 to imported.second)));val info=bridge.assetInfo(0)!!;assertEquals(48000.0,info[0],0.0);assertEquals(1026,info.size)
            val asset=AudioAsset(0,"Rendered kick",imported.second.name,48000,info[1].toLong(),info.drop(2).map{it.toFloat()})
            val audioDoc=short.copy(project=Project(name="Audio round trip",assets=listOf(asset),clips=listOf(Clip(0,asset=0,last=asset.frames,length=2.0))).place(0,0.0))
            io.saveArchive(audioDoc,Uri.fromFile(archive),cancel);val reopened=io.openArchive(Uri.fromFile(archive),cancel)
            assertEquals(audioDoc.project.clips,reopened.project.clips);assertTrue(io.audioFile(reopened.project.assets.single()).readBytes().contentEquals(imported.second.readBytes()))
            val autosave=ProjectFiles(context);autosave.save(d);assertEquals(d,autosave.load())
            archive.delete();wave.delete();imported.second.delete();reopened.project.assets.forEach{io.audioFile(it).delete()}
        }finally{bridge.close()}
    }
    @Test fun astraPreviewApplyUndoAndRejectStale(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("Astra")
        compose.onNodeWithTag("Astra prompt").scrollOnUiThread().performTextReplacement("Create a rolling 138 BPM Psytrance bassline in F minor on track 6")
        click("Astra offline")
        compose.waitUntil(15000){compose.onAllNodesWithTag("Astra apply").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("Astra apply").scrollOnUiThread().assertIsEnabled()
        shot("05-astra-preview")
        click("Astra apply")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Astra status") and hasText("Applied. One Undo restores the whole proposal.")).fetchSemanticsNodes().isNotEmpty()}
        click("Astra undo")
        click("Astra offline")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Astra apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        click("Close panel");click("Redo");click("Astra")
        compose.onNodeWithTag("Astra stale").scrollOnUiThread().assertIsDisplayed()
        compose.onNodeWithTag("Astra apply").assertIsNotEnabled()
        click("Astra dismiss")
        click("Astra settings")
        compose.onNodeWithTag("Astra key status").scrollOnUiThread().assertTextContains("No key saved",substring=true)
        shot("06-astra-settings")
        click("Astra settings")
        compose.onNodeWithTag("Astra prompt").scrollOnUiThread().performTextReplacement("Listen to the bass")
        click("Astra Gemini")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Astra status") and hasText("Open Gemini settings and add your Google AI Studio free-tier key first.")).fetchSemanticsNodes().isNotEmpty()}
        click("Close panel")
    }
    @Test fun astraNativeExportAndEncryptedCredential(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=GeminiKeyStore(context)
        // A deliberately invalid provider credential fixture; no network call is made.
        val fixture="astra-test-credential-0123456789"
        try{store.save(fixture);assertTrue(store.present());assertEquals(fixture,GeminiKeyStore(context).read());assertFalse(File(context.noBackupFilesDir,"astra-gemini-key").readText().contains(fixture))}finally{store.clear()}
        assertFalse(store.present())
        val bridge=AudioBridge()
        try{
            val presets=SynthSchema.presets.indices.map(bridge::preset)
            val base=StarterProject.create(presets).let{it.copy(project=Project(),patches=List(ProjectLimits.TRACKS){presets[0]})}
            val json=AstraCommands.envelope("Native command render",listOf(cmd("tempo","bpm" to 145),cmd("preset","track" to 1,"preset" to "FM Alloy"),cmd("pattern","track" to 1,"scene" to 1,"style" to "riddim","root" to "F","bars" to 1),cmd("place","track" to 1,"scene" to 1,"beat" to 0)))
            val result=AstraCommands.compile(json,base,presets)
            val wave=File(context.cacheDir,"astra-command-render.wav")
            try{assertEquals(0,bridge.export(wave,result.document,48000,.1f));val bytes=wave.readBytes();assertTrue(bytes.size>100000)
                var peak=0
                for(i in 44 until bytes.size-2 step 3){var sample=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8) or ((bytes[i+2].toInt() and 255) shl 16);if(sample and 0x800000!=0)sample-=0x1000000;peak=maxOf(peak,kotlin.math.abs(sample))}
                assertTrue("Rendered signal must exceed the dither floor",peak>10000)
            }finally{wave.delete()}
        }finally{bridge.close()}
    }
    private fun back(){compose.waitForIdle();InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);compose.waitForIdle()}
    @Test fun tempoStretchFloatExportAndBack(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("New project");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithTag("Empty 0").fetchSemanticsNodes().isNotEmpty()}
        click("Tempo map")
        compose.onNodeWithTag("Tempo beat").scrollOnUiThread().performTextReplacement("4")
        compose.onNodeWithTag("Tempo bpm").scrollOnUiThread().performTextReplacement("90")
        click("Save tempo point")
        compose.waitUntil(10000){compose.onAllNodesWithTag("Tempo point 1").fetchSemanticsNodes().isNotEmpty()}
        click("Tempo point 0");click("Tempo ramp");click("Save tempo point")
        compose.onNodeWithTag("Tempo map enabled").scrollOnUiThread().assertIsSelected()
        shot("12-tempo-map")
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        compose.onNodeWithTag("Tempo map enabled").scrollOnUiThread().assertIsSelected()
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
        compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT}
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Sample instrument");click("Instrument 2");click("Starter kit")
        compose.waitUntil(30000){compose.onAllNodesWithText("Sub kick").fetchSemanticsNodes().isNotEmpty()}
        back();click("Samples");click("Sample 0");click("Add audio clip");back();back()
        compose.onNodeWithTag("Launch 0").scrollOnUiThread().assertExists()
        click("Stretch")
        compose.onNodeWithTag("Stretch pitch").scrollOnUiThread().performTextReplacement("7")
        click("Render stretch")
        compose.waitUntil(30000){compose.onAllNodesWithText("Stretched audio added as a new clip and in Samples → Stretched. Original preserved; Undo removes the new version.").fetchSemanticsNodes().isNotEmpty()}
        shot("13-stretch");back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        compose.onNodeWithTag("Launch ${ProjectLimits.TRACKS}").scrollOnUiThread().assertExists()
        click("Undo");compose.onNodeWithTag("Empty ${ProjectLimits.TRACKS}").scrollOnUiThread().assertExists()
        compose.onNodeWithTag("Launch 0").assertExists()
        click("Redo");compose.onNodeWithTag("Launch ${ProjectLimits.TRACKS}").scrollOnUiThread().assertExists()
        click("File");click("Export arrangement")
        compose.onNodeWithTag("Export float").assertIsSelected();shot("14-float-export")
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
    }
    @Test fun tempoMapAndStretchThroughNativeBridge(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val bridge=AudioBridge();val io=ProjectFiles(context)
        val input=File(context.cacheDir,"stretch-source.wav");val stretched=File(context.cacheDir,"stretch-result.wav");val export=File(context.cacheDir,"tempo-float.wav");val archive=File(context.cacheDir,"tempo.astra")
        try{
            FactorySamples.write(0,input,AtomicBoolean(false));assertTrue(bridge.loadAssets(listOf(0 to input)))
            val frames=bridge.assetInfo(0)!![1].toLong();val target=frames*3/2
            assertEquals(0,bridge.stretch(stretched,0,0,frames,target,7.0))
            val h=ByteBuffer.wrap(stretched.readBytes(),0,44).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(3,h.getShort(20).toInt());assertEquals(32,h.getShort(34).toInt());assertEquals(target*8+44,stretched.length())
            val base=StarterProject.create(SynthSchema.presets.indices.map(bridge::preset))
            val project=Project(mode=1,clips=listOf(Clip(0,length=8.0).add(0.0,60,1.0)),tempoPoints=listOf(TempoPoint(0.0,120f),TempoPoint(4.0,60f)),tempoEnabled=true).place(0,0.0)
            val doc=base.copy(project=project)
            io.saveArchive(doc,Uri.fromFile(archive),AtomicBoolean(false));assertEquals(doc,io.openArchive(Uri.fromFile(archive),AtomicBoolean(false)))
            assertEquals(0,bridge.export(export,doc,48000,0f,true));assertEquals(288000L*8+44,export.length())
            assertEquals(3,ByteBuffer.wrap(export.readBytes(),0,44).order(ByteOrder.LITTLE_ENDIAN).getShort(20).toInt())
        }finally{bridge.close();listOf(input,stretched,export,archive).forEach{it.delete()}}
    }
    @Test fun nestedBackDrumRackSamplerBrowserAndBounce(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("Load starter song");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithText("Kick 1").fetchSemanticsNodes().isNotEmpty()}
        click("Sample instrument");click("Instrument 2");click("Starter kit")
        compose.waitUntil(30000){compose.onAllNodesWithText("Sub kick").fetchSemanticsNodes().isNotEmpty()}
        click("Sample audio")
        compose.waitUntil(15000){compose.onAllNodes(hasTestTag("Sample audio") and hasText("Audio on")).fetchSemanticsNodes().isNotEmpty()}
        click("Pad 0");shot("07-drum-rack")
        click("Edit pad");compose.onNodeWithTag("Pad editor").scrollOnUiThread().assertIsDisplayed()
        click("Sample reverse");click("Trim / loop frames")
        compose.onNodeWithTag("Apply sample range").scrollOnUiThread().assertIsDisplayed()
        back();compose.onNodeWithTag("Pad editor").scrollOnUiThread().assertIsDisplayed();compose.onNodeWithTag("Apply sample range").assertDoesNotExist()
        shot("08-pad-editor");back();compose.onNodeWithTag("Pad 0").scrollOnUiThread().assertIsDisplayed()
        // Browser opened from a pad returns to that pad's instrument panel.
        click("Load sample");click("Sample 0");click("Favorite sample");shot("09-sample-browser")
        back();compose.onNodeWithTag("Sample search").scrollOnUiThread().assertIsDisplayed()
        back();compose.onNodeWithTag("Pad 0").scrollOnUiThread().assertIsDisplayed()
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Astra");click("Astra settings");back()
        compose.onNodeWithTag("Astra key input").assertDoesNotExist();compose.onNodeWithTag("Astra prompt").scrollOnUiThread().assertIsDisplayed()
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Edit clip");click("Name / length");back();compose.onNodeWithTag("Piano roll").scrollOnUiThread().assertIsDisplayed()
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Arrangement");back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Resample");click("Render resample")
        compose.waitUntil(30000){compose.onAllNodesWithText("Bounce added to Samples → Resampled. Assign it to a pad, sampler or audio clip.").fetchSemanticsNodes().isNotEmpty()}
        shot("10-resample");click("Open samples");click("Sample 16")
        compose.onNodeWithTag("Sample details").scrollOnUiThread().assertTextEquals("Kick 1 bounce")
        click("Use in sampler");back() // return from browser to resampling, then close
        click("Sample instrument")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Instrument 1") and isSelected()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("Instrument 1").assertIsSelected();shot("11-sampler")
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
        compose.waitUntil(10000){compose.activity.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
        compose.onNodeWithTag("Instrument 1").assertIsSelected();back()
        compose.onNodeWithTag("Session grid").assertIsDisplayed()
        compose.runOnUiThread{compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT}
    }
    @Test fun packImportSamplerArchiveAndNativeRender(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext;val io=ProjectFiles(context);val cancel=AtomicBoolean(false);val bridge=AudioBridge()
        val input=File(context.cacheDir,"kit-input.wav");val pack=File(context.cacheDir,"test-kit.zip");val unsafePack=File(context.cacheDir,"unsafe-kit.zip");val archive=File(context.cacheDir,"sampler.astra");val wave=File(context.cacheDir,"sampler-render.wav")
        var created=emptyList<Pair<String,File>>();var restored: ProjectDocument?=null
        try{
            FactorySamples.write(0,input,cancel)
            java.util.zip.ZipOutputStream(pack.outputStream()).use{zip->
                for(name in listOf("drums/kick.wav","extras/unused.wav")){zip.putNextEntry(java.util.zip.ZipEntry(name));input.inputStream().use{it.copyTo(zip)};zip.closeEntry()}
                zip.putNextEntry(java.util.zip.ZipEntry("README.txt"));zip.write("Original test samples".toByteArray());zip.closeEntry()
            }
            val before=io.audioDir.list()!!.toSet()
            java.util.zip.ZipOutputStream(unsafePack.outputStream()).use{zip->
                for(name in listOf("drums/kick.wav","../unused.wav")){zip.putNextEntry(java.util.zip.ZipEntry(name));input.inputStream().use{it.copyTo(zip)};zip.closeEntry()}
            }
            assertTrue(runCatching{io.importPack(Uri.fromFile(unsafePack),2,cancel)}.exceptionOrNull() is java.util.zip.ZipException)
            assertEquals("Unsafe pack must roll back the earlier valid sample",before,io.audioDir.list()!!.toSet())
            assertTrue(runCatching{io.importPack(Uri.fromFile(pack),1,cancel)}.isFailure)
            assertEquals(before,io.audioDir.list()!!.toSet())
            created=io.importPack(Uri.fromFile(pack),2,cancel);assertEquals(2,created.size);assertFalse(File(context.filesDir,"unused.wav").exists())
            assertTrue(bridge.loadAssets(created.mapIndexed{i,x->i to x.second}))
            val assets=created.mapIndexed{i,x->val info=bridge.assetInfo(i)!!;AudioAsset(i,x.first,x.second.name,info[0].toInt(),info[1].toLong(),info.drop(2).map{it.toFloat()},true,"Test kit")}
            val presets=SynthSchema.presets.indices.map(bridge::preset)
            val base=StarterProject.create(presets)
            val project=Project(assets=assets,clips=listOf(Clip(0,length=1.0).add(0.0,36,.25))).instrument(0){it.copy(mode=2).edit(0){z->z.load(assets[0])}}.place(0,0.0)
            val doc=base.copy(project=project)
            io.saveArchive(doc,Uri.fromFile(archive),cancel);restored=io.openArchive(Uri.fromFile(archive),cancel)
            assertEquals(2,restored.project.assets.size);assertEquals(doc.project.instruments,restored.project.instruments);assertTrue(restored.project.assets.all{it.favorite&&it.group=="Test kit"})
            assertArrayEquals(created[1].second.readBytes(),io.audioFile(restored.project.assets[1]).readBytes())
            assertTrue(bridge.loadAssets(restored.project.assets.map{it.id to io.audioFile(it)}))
            assertEquals(0,bridge.export(wave,restored,48000,.1f))
            val bytes=wave.readBytes();var peak=0
            for(i in 44 until bytes.size-2 step 3){var value=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8) or ((bytes[i+2].toInt() and 255) shl 16);if(value and 0x800000!=0)value-=0x1000000;peak=maxOf(peak,kotlin.math.abs(value))}
            assertTrue("Sampler must render through JNI",peak>10000)
            // Actual Android bridge command validation includes browser preview opcodes.
            assertEquals(0,bridge.start());assertTrue(bridge.project(restored.project));assertTrue(bridge.preview(0));assertTrue(bridge.stopPreview());bridge.stop()
        }finally{bridge.close();created.forEach{it.second.delete()};restored?.project?.assets?.forEach{io.audioFile(it).delete()};listOf(input,pack,unsafePack,archive,wave).forEach{it.delete()}}
    }

}
