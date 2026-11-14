package com.weskestis.astrastudio.midi

import org.junit.Assert.*
import org.junit.Test

class MidiDecoderTest {
    private data class Event(val group: Int, val channel: Int, val key: Int, val velocity: Float, val on: Boolean)
    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()

    @Test fun runningStatusRealtimeAndFragmentedMessages() {
        val events = mutableListOf<Event>()
        val decoder = MidiDecoder(false, { g, c, k, v, on -> events += Event(g, c, k, v, on) }, { _, _ -> })
        decoder.feed(bytes(0x92, 60), 0, 2)
        decoder.feed(bytes(0xF8, 127, 61, 90, 60, 0), 0, 6)
        assertEquals(3, events.size)
        assertEquals(Event(0, 2, 60, 1f, true), events[0])
        assertEquals(61, events[1].key)
        assertFalse(events[2].on)
    }
    @Test fun systemCommonAndSysexCancelRunningStatus() {
        val events = mutableListOf<Event>()
        val decoder = MidiDecoder(false, { g, c, k, v, on -> events += Event(g, c, k, v, on) }, { _, _ -> })
        val data = bytes(0x90, 60, 80, 0xF0, 1, 2, 3, 0xF8, 0xF7, 61, 90, 0x90, 62, 100)
        decoder.feed(data, 0, data.size)
        assertEquals(listOf(60, 62), events.map { it.key })
    }
    @Test fun umpMidi2VelocityGroupsAndFragmentation() {
        val events = mutableListOf<Event>()
        val decoder = MidiDecoder(true, { g, c, k, v, on -> events += Event(g, c, k, v, on) }, { _, _ -> })
        val packet = bytes(0x43, 0x92, 60, 0, 0x80, 0, 0, 0)
        decoder.feed(packet, 0, 3)
        assertTrue(events.isEmpty())
        decoder.feed(packet, 3, 5)
        assertEquals(3, events[0].group)
        assertEquals(2, events[0].channel)
        assertEquals(32768f / 65535f, events[0].velocity, 0.000001f)
        decoder.feed(bytes(0x43, 0x82, 60, 0, 0, 0, 0, 0), 0, 8)
        assertFalse(events[1].on)
    }
    @Test fun umpZeroVelocityNoteOnIsNotNoteOff() {
        var on = false
        var velocity = -1f
        val decoder = MidiDecoder(true, { _, _, _, v, flag -> on = flag; velocity = v }, { _, _ -> })
        decoder.feed(bytes(0x40, 0x90, 60, 0, 0, 0, 0, 0), 0, 8)
        assertTrue(on)
        assertEquals(0f, velocity, 0f)
    }
    @Test fun unsupportedPacketIsSkippedWithoutLosingAlignment() {
        val events = mutableListOf<Event>()
        val decoder = MidiDecoder(true, { g, c, k, v, on -> events += Event(g, c, k, v, on) }, { _, _ -> })
        val data = bytes(0x50, 0, 0x90, 60, 127, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x20, 0x90, 65, 127)
        decoder.feed(data, 0, data.size)
        assertEquals(1, events.size)
        assertEquals(65, events[0].key)
    }
    @Test fun channelAllNotesOffIsScoped() {
        val released = mutableListOf<Pair<Int, Int>>()
        val decoder = MidiDecoder(true, { _, _, _, _, _ -> }, { g, c -> released += g to c })
        decoder.feed(bytes(0x24, 0xB5, 123, 0), 0, 4)
        assertEquals(listOf(4 to 5), released)
    }
}
