package com.weskestis.astrastudio.audio

/** Polled diagnostics, not a claimed device performance certification. */
data class PerformanceSnapshot(val polls: Long=0,val peakCpu: Double=0.0,val peakVoices: Int=0,val xruns: Long=0,
    val javaBytes: Long=0,val nativeBytes: Long=0,val elapsedSeconds: Long=0)
class PerformanceMeter {
    private var value=PerformanceSnapshot();private var lastXruns: Int?=null;private var began: Long?=null
    fun reset(){value=PerformanceSnapshot();lastXruns=null;began=null}
    fun sample(s: AudioStats,nowMs: Long,javaBytes: Long,nativeBytes: Long): PerformanceSnapshot {
        if(s.running){if(began==null)began=nowMs
            val delta=if(s.xruns<0)0 else lastXruns?.let{if(s.xruns>=it)s.xruns-it else s.xruns}?:0
            if(s.xruns>=0)lastXruns=s.xruns
            value=value.copy(polls=value.polls+1,peakCpu=maxOf(value.peakCpu,s.cpuLoad.takeIf{it.isFinite()}?:0.0),peakVoices=maxOf(value.peakVoices,s.activeVoices),xruns=value.xruns+delta)
        }else lastXruns=null
        value=value.copy(javaBytes=javaBytes.coerceAtLeast(0),nativeBytes=nativeBytes.coerceAtLeast(0),elapsedSeconds=began?.let{(nowMs-it).coerceAtLeast(0)/1000}?:0)
        return value
    }
}
