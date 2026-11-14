package com.weskestis.astrastudio.midi

import android.content.Context
import android.hardware.usb.UsbDevice
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import com.weskestis.astrastudio.audio.AudioBridge
import java.io.IOException
import java.util.concurrent.Executor

/** Owns hot-plug connections on the service control Handler. Only USB inputs are
 * automatically opened. MIDI timestamps are not yet aligned to the device clock;
 * live notes enter the next available audio block, while scheduled engine events
 * use exact frame positions. Per-note MIDI 2 expression is not implemented here.
 */
class MidiInputManager(context: Context, private val control: Handler, private val bridge: AudioBridge,
                       private val countChanged: (Int) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(MidiManager::class.java)
    private data class Connection(val device: MidiDevice, val ports: List<MidiOutputPort>, val gates: List<Gate>)
    private val connections = mutableMapOf<Int, Connection>()
    private val opening = mutableSetOf<Int>()
    private val present = mutableSetOf<Int>()
    private val known = mutableMapOf<Int, Pair<MidiDeviceInfo, Int>>()
    private val callbacks = mutableListOf<MidiManager.DeviceCallback>()
    private var active = false
    private var nextNoteId = 1_000_000

    fun start() {
        if (manager == null || active) return
        active = true
        for (transport in listOf(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS)) {
            val callback = object : MidiManager.DeviceCallback() {
                override fun onDeviceAdded(info: MidiDeviceInfo) { add(info, transport) }
                override fun onDeviceRemoved(info: MidiDeviceInfo) {
                    present.remove(info.id)
                    known.remove(info.id)
                    remove(info.id)
                }
            }
            manager.getDevicesForTransport(transport).forEach { add(it, transport) }
            manager.registerDeviceCallback(transport, Executor { control.post(it) }, callback)
            callbacks += callback
        }
    }

    private fun add(info: MidiDeviceInfo, transport: Int) {
        if (!active || info.type != MidiDeviceInfo.TYPE_USB || info.outputPortCount == 0) return
        present += info.id
        known[info.id] = info to transport
        val physicalId = info.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)?.deviceId
        if (physicalId != null) {
            val siblings = known.values.filter { (candidate, _) ->
                candidate.id != info.id && candidate.properties.getParcelable(MidiDeviceInfo.PROPERTY_USB_DEVICE, UsbDevice::class.java)?.deviceId == physicalId
            }
            if (transport == MidiManager.TRANSPORT_MIDI_BYTE_STREAM && siblings.any { it.second == MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS }) return
            if (transport == MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS) {
                siblings.filter { it.second == MidiManager.TRANSPORT_MIDI_BYTE_STREAM }.forEach { (legacy, _) ->
                    present.remove(legacy.id)
                    remove(legacy.id)
                }
            }
        }
        if (connections.containsKey(info.id) || !opening.add(info.id)) return
        try {
            manager?.openDevice(info, { device ->
                opening.remove(info.id)
                if (device == null) return@openDevice
                if (!active || info.id !in present) { runCatching { device.close() }; return@openDevice }
                val ports = mutableListOf<MidiOutputPort>()
                val gates = mutableListOf<Gate>()
                try {
                    for (index in 0 until info.outputPortCount) {
                        val port = device.openOutputPort(index) ?: continue
                        val gate = Gate()
                        ports += port
                        gates += gate
                        val decoder = MidiDecoder(transport == MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS,
                            { group, channel, key, velocity, on ->
                                control.post { if (active && gate.connected) gate.event(group, channel, key, velocity, on) }
                            }, { group, channel ->
                                control.post { if (gate.connected) gate.releaseChannel(group, channel) }
                            })
                        port.connect(object : MidiReceiver() {
                            @Synchronized override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                                decoder.feed(msg, offset, count)
                            }
                            override fun onFlush() { control.post { gate.releaseAll() } }
                        })
                    }
                    connections[info.id] = Connection(device, ports, gates)
                    countChanged(connections.values.sumOf { it.ports.size })
                } catch (_: IOException) {
                    gates.forEach { it.releaseAll() }
                    ports.forEach { runCatching { it.close() } }
                    runCatching { device.close() }
                }
            }, control)
        } catch (_: SecurityException) { opening.remove(info.id) }
    }

    private inner class Gate {
        var connected = true
        private val held = mutableMapOf<Int, Int>()
        fun event(group: Int, channel: Int, key: Int, velocity: Float, on: Boolean) {
            val address = group * 2048 + channel * 128 + key
            held.remove(address)?.let { bridge.noteOff(channel, it) }
            if (on) {
                // IDs occupy a separate range from touch notes and the demo.
                if (nextNoteId == Int.MAX_VALUE) nextNoteId = 1_000_000
                val id = nextNoteId++
                if (bridge.noteOn(channel, key, velocity.coerceIn(0f, 1f), id)) held[address] = id
            }
        }
        fun releaseChannel(group: Int, channel: Int) {
            val prefix = group * 2048 + channel * 128
            val keys = held.keys.filter { it in prefix until prefix + 128 }
            keys.forEach { held.remove(it)?.let { id -> bridge.noteOff(channel, id) } }
        }
        fun releaseAll() {
            held.forEach { (address, id) -> bridge.noteOff((address / 128) % 16, id) }
            held.clear()
        }
    }

    private fun remove(id: Int) {
        connections.remove(id)?.let { connection ->
            connection.gates.forEach { it.connected = false; it.releaseAll() }
            connection.ports.forEach { runCatching { it.close() } }
            runCatching { connection.device.close() }
        }
        countChanged(connections.values.sumOf { it.ports.size })
    }
    override fun close() {
        active = false
        callbacks.forEach { manager?.unregisterDeviceCallback(it) }
        callbacks.clear()
        present.clear()
        known.clear()
        connections.keys.toList().forEach(::remove)
    }
}
