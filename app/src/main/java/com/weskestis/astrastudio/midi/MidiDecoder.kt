package com.weskestis.astrastudio.midi

/** Pure Kotlin, separately testable parser. No Android/UI work in this class.
 * Android's Java MIDI API presents UMP words in big-endian byte order.
 * Phase 1 accepts MIDI 1/2 note messages and CC 120/123; expression, MIDI-CI,
 * clock sync, sustain and controller mapping belong to later instrument work.
 */
class MidiDecoder(private val ump: Boolean, private val note: (group: Int, channel: Int, key: Int, velocity: Float, on: Boolean) -> Unit,
                  private val allOff: (group: Int, channel: Int) -> Unit) {
    private var status = 0
    private var needed = 0
    private var used = 0
    private val data = IntArray(2)
    private var sysex = false
    private val packet = IntArray(16)
    private var packetUsed = 0
    private var packetSize = 4

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
        for (i in offset until offset + count) {
            val byte = bytes[i].toInt() and 255
            if (ump) umpByte(byte) else midiByte(byte)
        }
    }
    private fun midiByte(byte: Int) {
        if (byte >= 0xF8) return // Real-time interleaving never resets running status.
        if (byte >= 0x80) {
            used = 0
            if (byte >= 0xF0) {
                status = 0
                needed = 0
                sysex = byte == 0xF0
                return
            }
            sysex = false
            status = byte
            needed = if ((byte and 0xF0) == 0xC0 || (byte and 0xF0) == 0xD0) 1 else 2
            return
        }
        if (sysex || status == 0) return
        data[used++] = byte
        if (used == needed) {
            channelMessage(0, status, data[0], if (needed == 2) data[1] else 0)
            used = 0
        }
    }
    private fun channelMessage(group: Int, status: Int, key: Int, value: Int) {
        val channel = status and 15
        when (status and 0xF0) {
            0x80 -> note(group, channel, key and 127, 0f, false)
            0x90 -> note(group, channel, key and 127, value / 127f, value != 0)
            0xB0 -> if (key == 120 || key == 123) allOff(group, channel)
        }
    }
    private fun umpByte(byte: Int) {
        if (packetUsed == 0) {
            packetSize = when (byte ushr 4) {
                0x0, 0x1, 0x2, 0x6, 0x7 -> 4
                0x3, 0x4, 0x8, 0x9, 0xA -> 8
                0xB, 0xC -> 12
                else -> 16
            }
        }
        packet[packetUsed++] = byte
        if (packetUsed != packetSize) return
        packetUsed = 0
        val group = packet[0] and 15
        when (packet[0] ushr 4) {
            2 -> channelMessage(group, packet[1], packet[2], packet[3])
            4 -> {
                val channel = packet[1] and 15
                val key = packet[2] and 127
                when (packet[1] and 0xF0) {
                    0x80 -> note(group, channel, key, 0f, false)
                    // Unlike MIDI 1, MIDI 2 Note On with zero velocity is NOT Note Off.
                    0x90 -> note(group, channel, key, ((packet[4] shl 8) or packet[5]) / 65535f, true)
                    0xB0 -> if (key == 120 || key == 123) allOff(group, channel)
                }
            }
        }
    }
}
