package com.weskestis.astrastudio.audio

import com.weskestis.astrastudio.fx.FxStats
import com.weskestis.astrastudio.project.SequenceStats

private val idleSequence=SequenceStats()
private val idleEffects=FxStats()

/** Editing panels do not consume continuously changing audio diagnostics.
 * Keep their inputs equal between edits so Compose can skip their entire trees.
 * The service and meter/timeline panels still receive the complete live state.
 */
internal fun StudioState.editorSnapshot()=copy(
    stats=AudioStats(running=stats.running),performance=PerformanceSnapshot(),sequence=idleSequence,fxStats=idleEffects,progress=0f,storage=storage.copy(missedFrames=0,diskReads=0)
)
internal fun StudioState.sessionSnapshot()=StudioState(
    project=project,tracks=tracks,patches=patches,sequence=sequence.copy(phase=idleSequence.phase)
)
