package com.weskestis.astrastudio.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weskestis.astrastudio.project.ExportFormats

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ExportFormatControls(rate: Int,floating: Boolean,setRate: (Int)->Unit,setFloating: (Boolean)->Unit,prefix: String){
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        FilterChip(!floating,{setFloating(false)},label={Text("24-bit PCM")},modifier=Modifier.testTag("$prefix PCM24"))
        FilterChip(floating,{setFloating(true)},label={Text("32-bit float")},modifier=Modifier.testTag("$prefix float"))
    }
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
        ExportFormats.sampleRates.forEach{r->FilterChip(rate==r,{setRate(r)},label={Text(ExportFormats.label(r))},modifier=Modifier.testTag("$prefix rate $r"))}
    }
    FilterChip(rate==ExportFormats.MASTER_RATE&&!floating,{setRate(ExportFormats.MASTER_RATE);setFloating(false)},
        label={Text("192 kHz / 24-bit master")},modifier=Modifier.testTag("$prefix 192 master"))
    Text("${ExportFormats.label(rate)} stereo · ${number(ExportFormats.bytesPerMinute(rate,floating)/1000000.0)} MB/minute. "+
        if(floating)"Float samples for further editing; no dither." else "Dithered once to 24-bit PCM.",fontSize=12.sp,color=Muted)
    if(rate>48000)Text("Offline master rendering uses the selected rate. Existing recordings retain their source detail. Higher rates take more time and storage.",fontSize=12.sp,color=Muted)
}
