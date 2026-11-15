package com.weskestis.astrastudio.synth

import org.junit.Assert.*
import org.junit.Test

class SynthPatchTest {
    @Test fun persistenceRoundtripRetainsEveryField() {
        val patch = SynthPatch.initial().parameter(SynthSchema.OscAPosition, .75f)
            .parameter(SynthSchema.Instrument, 1f).route(15, 0, 4f).route(15, 1, 15f)
            .route(15, 2, -.43f).paint(3, 0, -1f, 31, 1f)
        assertEquals(patch, SynthPatch.decode(patch.encode()))
        assertEquals(SynthSchema.WIRE_COUNT, patch.wire.size)
    }
    @Test fun malformedPersistenceIsRejected() {
        val text = SynthPatch.initial().encode()
        assertNull(SynthPatch.decode(text.replaceFirst("1|", "2|")))
        assertNull(SynthPatch.decode("1|Bad|NaN,Infinity"))
        assertNull(SynthPatch.decode(text.substringBeforeLast(',')))
        assertNull(SynthPatch.decode("x".repeat(16001)))
        assertNull(SynthPatch.decode(null))
    }
    @Test fun valuesRespectNativeRangesAndIntegerEnums() {
        val original = SynthPatch.initial()
        assertEquals(original, original.parameter(SynthSchema.Output, Float.NaN))
        val edited = original.parameter(SynthSchema.Filter1Cutoff, 0f).parameter(SynthSchema.Instrument, .9f)
            .route(0, 0, 100f).route(0, 1, -1f).route(0, 2, -100f)
        assertEquals(20f, edited[SynthSchema.Filter1Cutoff])
        assertEquals(1f, edited[SynthSchema.Instrument])
        assertTrue(SynthPatch.valid(edited.wire))
        val corrupt = edited.wire.toMutableList().also { it[SynthSchema.PARAM_COUNT + 128] = 1e30f }
        assertFalse(SynthPatch.valid(corrupt))
    }
    @Test fun drawingInterpolatesSkippedPointsInEitherDirection() {
        val a = SynthPatch.initial().paint(0, 0, -1f, 31, 1f)
        val b = SynthPatch.initial().paint(0, 31, 1f, 0, -1f)
        a.shapePoints(0).forEachIndexed { index, value ->
            assertEquals(-1f + 2f * index / 31, value, .00001f)
            assertEquals(value, b.shapePoints(0)[index], .00001f)
        }
        assertNotEquals(a.shapePoints(0), a.shapePoints(1))
    }
    @Test fun patchEditsDoNotMutateOtherTracks() {
        val original = SynthPatch.initial()
        val edited = original.parameter(SynthSchema.FmIndex, 6f).shape(2, 3)
        assertEquals(2f, original[SynthSchema.FmIndex])
        assertEquals(6f, edited[SynthSchema.FmIndex])
        assertNotEquals(original.shapePoints(2), edited.shapePoints(2))
        assertEquals(original.shapePoints(0), edited.shapePoints(0))
    }
}
