package com.weskestis.astrastudio

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.weskestis.astrastudio.audio.AudioBridge
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
    private fun click(tag: String){val node=compose.onNodeWithTag(tag);runCatching{node.performScrollTo()};node.performClick()}
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
        compose.onNodeWithTag("Piano roll").performScrollTo().assertIsDisplayed().performTouchInput{click(Offset(width*.4f,height*.55f))}
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
        compose.onNodeWithTag("Astra prompt").performScrollTo().performTextReplacement("Create a rolling 138 BPM Psytrance bassline in F minor on track 6")
        click("Astra offline")
        compose.waitUntil(15000){compose.onAllNodesWithTag("Astra apply").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("Astra apply").performScrollTo().assertIsEnabled()
        shot("05-astra-preview")
        click("Astra apply")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Astra status") and hasText("Applied. One Undo restores the whole proposal.")).fetchSemanticsNodes().isNotEmpty()}
        click("Astra undo")
        click("Astra offline")
        compose.waitUntil(10000){compose.onAllNodes(hasTestTag("Astra apply") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        click("Close panel");click("Redo");click("Astra")
        compose.onNodeWithTag("Astra stale").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("Astra apply").assertIsNotEnabled()
        click("Astra dismiss")
        click("Astra settings")
        compose.onNodeWithTag("Astra key status").performScrollTo().assertTextContains("No key saved",substring=true)
        shot("06-astra-settings")
        click("Astra settings")
        compose.onNodeWithTag("Astra prompt").performScrollTo().performTextReplacement("Listen to the bass")
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
            val base=StarterProject.create(presets).let{it.copy(project=Project(),patches=List(16){presets[0]})}
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
    @Test fun nestedBackDrumRackSamplerBrowserAndBounce(){
        compose.waitUntil(20000){compose.onAllNodesWithTag("Session grid").fetchSemanticsNodes().isNotEmpty()}
        click("File");click("Load starter song");click("Confirm project")
        compose.waitUntil(10000){compose.onAllNodesWithText("Kick 1").fetchSemanticsNodes().isNotEmpty()}
        click("Sample instrument");click("Instrument 2");click("Starter kit")
        compose.waitUntil(30000){compose.onAllNodesWithText("Sub kick").fetchSemanticsNodes().isNotEmpty()}
        click("Sample audio")
        compose.waitUntil(15000){compose.onAllNodes(hasTestTag("Sample audio") and hasText("Audio on")).fetchSemanticsNodes().isNotEmpty()}
        click("Pad 0");shot("07-drum-rack")
        click("Edit pad");compose.onNodeWithTag("Pad editor").performScrollTo().assertIsDisplayed()
        click("Sample reverse");click("Trim / loop frames")
        compose.onNodeWithTag("Apply sample range").performScrollTo().assertIsDisplayed()
        back();compose.onNodeWithTag("Pad editor").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("Apply sample range").assertDoesNotExist()
        shot("08-pad-editor");back();compose.onNodeWithTag("Pad 0").performScrollTo().assertIsDisplayed()
        // Browser opened from a pad returns to that pad's instrument panel.
        click("Load sample");click("Sample 0");click("Favorite sample");shot("09-sample-browser")
        back();compose.onNodeWithTag("Sample search").performScrollTo().assertIsDisplayed()
        back();compose.onNodeWithTag("Pad 0").performScrollTo().assertIsDisplayed()
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Astra");click("Astra settings");back()
        compose.onNodeWithTag("Astra key input").assertDoesNotExist();compose.onNodeWithTag("Astra prompt").performScrollTo().assertIsDisplayed()
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Edit clip");click("Name / length");back();compose.onNodeWithTag("Piano roll").performScrollTo().assertIsDisplayed()
        back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Arrangement");back();compose.onNodeWithTag("Session grid").assertIsDisplayed()
        click("Resample");click("Render resample")
        compose.waitUntil(30000){compose.onAllNodesWithText("Bounce added to Samples → Resampled. Assign it to a pad, sampler or audio clip.").fetchSemanticsNodes().isNotEmpty()}
        shot("10-resample");click("Open samples");click("Sample 16")
        compose.onNodeWithTag("Sample details").performScrollTo().assertTextEquals("Kick 1 bounce")
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
