package com.zedokmusic.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.UUID

/** Splits a raw MIDI byte stream into 3-byte channel messages (handles running status, ignores realtime and SysEx). */
class MidiParser(private val onMessage: (status: Int, d1: Int, d2: Int) -> Unit) {
    private var status = 0
    private val data = IntArray(2)
    private var n = 0
    private var inSysEx = false

    fun feed(b: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val v = b[i].toInt() and 0xFF
            when {
                v >= 0xF8 -> {}                                   // realtime (clock, active sensing…): never interrupts a message
                v == 0xF0 -> { inSysEx = true; status = 0 }
                v == 0xF7 -> { inSysEx = false; status = 0 }
                v >= 0x80 -> { if (!inSysEx) { status = if (v >= 0xF0) 0 else v; n = 0 } }
                inSysEx || status == 0 -> {}
                else -> {
                    data[n++] = v
                    val need = if ((status and 0xF0) == 0xC0 || (status and 0xF0) == 0xD0) 1 else 2
                    if (n >= need) { onMessage(status, data[0], if (need == 2) data[1] else 0); n = 0 }  // running status keeps `status`
                }
            }
        }
    }
}

/** USB and Bluetooth LE MIDI input. Messages are delivered on the main thread. */
class MidiInput(private val ctx: Context, private val onMessage: (status: Int, d1: Int, d2: Int) -> Unit) {
    class Dev(val name: String, val info: MidiDeviceInfo?, val ble: BluetoothDevice?) { var connected by mutableStateOf(false) }

    private val handler = Handler(Looper.getMainLooper())
    private val mm = ctx.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val open = HashMap<Int, MidiDevice>()
    val devices = mutableStateListOf<Dev>()      // wired / already-paired devices
    val bleFound = mutableStateListOf<Dev>()     // devices seen while scanning
    var scanning by mutableStateOf(false); private set
    var status by mutableStateOf("")
    /** Whether newly plugged (and already present) devices are opened automatically. */
    var autoConnect: () -> Boolean = { true }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { refresh(); if (autoConnect()) connect(info) }
        override fun onDeviceRemoved(info: MidiDeviceInfo) { open.remove(info.id)?.close(); refresh() }
    }

    fun start() {
        val m = mm ?: run { status = "This device has no MIDI support."; return }
        m.registerDeviceCallback(callback, handler)
        refresh(); if (autoConnect()) m.devices.forEach { connect(it) }
    }

    fun stop() { mm?.unregisterDeviceCallback(callback); stopScan(); open.values.forEach { it.close() }; open.clear() }

    private fun nameOf(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device ${info.id}"

    private fun refresh() {
        val m = mm ?: return
        devices.clear()
        for (info in m.devices) if (info.outputPortCount > 0) devices.add(Dev(nameOf(info), info, null).also { it.connected = open.containsKey(info.id) })
    }

    /** Opens the first output port of [info] (the port that sends data from the instrument to us). */
    fun connect(info: MidiDeviceInfo) {
        val m = mm ?: return
        if (info.outputPortCount == 0 || open.containsKey(info.id)) return
        m.openDevice(info, { dev ->
            if (dev == null) { status = "Could not open ${nameOf(info)}"; return@openDevice }
            val port = dev.openOutputPort(0)
            if (port == null) { dev.close(); status = "${nameOf(info)} has no readable port"; return@openDevice }
            val parser = MidiParser { s, a, b -> handler.post { onMessage(s, a, b) } }
            port.connect(object : MidiReceiver() { override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) { parser.feed(msg, offset, count) } })
            open[info.id] = dev; status = "Connected: ${nameOf(info)}"; refresh()
        }, handler)
    }

    // ---- Bluetooth LE MIDI ----
    private val bleMidiService = ParcelUuid(UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700"))
    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val d = r.device
            if (bleFound.none { it.ble?.address == d.address }) bleFound.add(Dev(runCatching { @SuppressLint("MissingPermission") d.name }.getOrNull() ?: d.address, null, d))
        }
        override fun onScanFailed(errorCode: Int) { status = "Bluetooth scan failed ($errorCode)"; scanning = false }
    }

    /** Needs the location permission (legacy Bluetooth scanning) and Bluetooth turned on. */
    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { status = "Turn Bluetooth on to scan for MIDI devices."; return }
        bleFound.clear(); scanning = true; status = "Scanning for Bluetooth MIDI…"
        scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(bleMidiService).build()), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        handler.postDelayed({ stopScan() }, 15_000)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter?.bluetoothLeScanner?.stopScan(scanCb) }
    }

    fun connectBle(d: Dev) {
        val m = mm ?: return; val bt = d.ble ?: return
        m.openBluetoothDevice(bt, { dev ->
            if (dev == null) { status = "Could not connect to ${d.name}"; return@openBluetoothDevice }
            val info = dev.info
            if (info.outputPortCount == 0) { dev.close(); status = "${d.name} has no readable port"; return@openBluetoothDevice }
            val port = dev.openOutputPort(0)
            val parser = MidiParser { s, a, b -> handler.post { onMessage(s, a, b) } }
            port?.connect(object : MidiReceiver() { override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) { parser.feed(msg, offset, count) } })
            open[info.id] = dev; d.connected = true; status = "Connected: ${d.name}"
        }, handler)
    }
}
