package com.weskestis.astrastudio.fx

import org.junit.Assert.*
import org.junit.Test

class FxConfigTest {
    @Test fun persistenceRoundTripPreservesRoutingAndMacros() {
        val config=FxConfig.initial().parameter(FxSchema.ShaperOn,1f).move(2,-1).lane(2,1)
            .parameter(FxSchema.RackMode,1f).macro(0,0,1f).macro(0,1,.3f).macro(0,2,.4f)
        assertEquals(config,FxConfig.decode(config.encode()))
        assertEquals(listOf(0,2,1,3,4,5,6,7),config.order)
        assertEquals(1,config.lane(2))
    }
    @Test fun corruptWireRejected() {
        val base=FxConfig.initial()
        assertNull(FxConfig.decode("1|x|NaN"))
        assertFalse(FxConfig.valid(base.wire.dropLast(1)))
        assertFalse(FxConfig.valid(base.wire.toMutableList().also{it[FxSchema.PARAM_COUNT]=1f}))
        assertFalse(FxConfig.valid(base.wire.toMutableList().also{it[FxSchema.ScSource]=.5f}))
        assertFalse(FxConfig.valid(base.wire.toMutableList().also{it[FxSchema.Ott1Lower]=it[FxSchema.Ott1Upper]}))
        assertFalse(FxConfig.valid(base.wire.toMutableList().also{it[FxSchema.DelayMix]=Float.POSITIVE_INFINITY}))
    }
    @Test fun controlsKeepThresholdsOrderedAndEnumsIntegral() {
        val a=FxConfig.initial().parameter(FxSchema.Ott1Lower,0f).parameter(FxSchema.ScSource,2.6f)
        assertTrue(a[FxSchema.Ott1Lower]<a[FxSchema.Ott1Upper])
        assertEquals(3f,a[FxSchema.ScSource],0f)
        assertEquals(a,a.parameter(FxSchema.DelayMix,Float.NaN))
        assertEquals(a,a.parameter(-1,.3f))
    }
    @Test fun macroIsBoundedAndDoesNotOverwriteBasePatch() {
        val a=FxConfig.initial().macro(0,0,1f).macro(0,1,1f).macro(0,2,1f)
        assertEquals(6f,a[FxSchema.ShaperPre],0f)
        assertEquals(36f,a.effective()[FxSchema.ShaperPre],0f)
        val reset=a.macro(0,2,0f)
        assertEquals(6f,reset.effective()[FxSchema.ShaperPre],0f)
    }
    @Test fun eqResponseTracksCenterGainByChannel() {
        val c=FxConfig.initial().parameter(FxSchema.EqOn,1f).parameter(FxSchema.Eq1Frequency,1000f)
            .parameter(FxSchema.Eq1Gain,6f).parameter(FxSchema.EqMs,1f).parameter(FxSchema.Eq1Channel,2f)
        assertEquals(0f,EqResponse.db(c,1000.0,48000.0,1),.001f)
        assertEquals(6f,EqResponse.db(c,1000.0,48000.0,2),.001f)
        assertEquals(0f,EqResponse.db(c.parameter(FxSchema.EqOn,0f),1000.0,48000.0,2),0f)
        assertEquals(-6f,EqResponse.db(c.parameter(FxSchema.Eq1Gain,-6f),1000.0,48000.0,2),.001f)
    }
    @Test fun movesStayPermutationAtBothEnds() {
        var c=FxConfig.initial()
        repeat(30){c=c.move(3,-1)}
        assertEquals(3,c.order.first())
        repeat(30){c=c.move(3,1)}
        assertEquals(3,c.order.last())
        assertEquals((0..7).toSet(),c.order.toSet())
        assertEquals(8,c.order.size)
    }
}
