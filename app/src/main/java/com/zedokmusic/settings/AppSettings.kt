package com.zedokmusic.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** One persisted integer setting (booleans are 0/1). Reading [value] from composition makes the UI follow changes. */
class Setting(private val key: String, val default: Int) {
    var value by mutableIntStateOf(default); private set
    val on get() = value != 0
    fun set(v: Int) { value = v; AppSettings.save(key, v) }
    fun set(v: Boolean) = set(if (v) 1 else 0)
    internal fun load(sp: SharedPreferences) { value = sp.getInt(key, default) }
    fun reset() = set(default)
}

/** All user settings (Settings screen). Call [init] once before use. */
object AppSettings {
    private var sp: SharedPreferences? = null
    private val all = ArrayList<Setting>()
    private fun s(key: String, def: Int) = Setting(key, def).also { all.add(it) }
    private fun b(key: String, def: Boolean) = s(key, if (def) 1 else 0)

    // Audio
    val outputDevice = s("audio.output", 0)        // AudioDeviceInfo id, 0 = automatic
    val inputDevice = s("audio.input", 0)
    val latency = s("audio.latency", 0)            // 0 lowest, 1 balanced, 2 safe
    val exclusive = b("audio.exclusive", true)
    // Project
    val autosave = b("project.autosave", true)
    val follow = b("project.follow", true)
    val snap = s("roll.snap", 1)                   // index into [SNAP_BEATS]
    val noteLength = s("roll.length", 2)           // index into [NOTE_BEATS]
    val exportFormat = s("export.format", 1)       // same codes as the export dialog
    // Plugins
    val editorFps = s("plugin.fps", 30)
    val wineLog = b("plugin.winelog", false)
    val gpu = s("plugin.gpu", 0)                    // graphics for Direct3D plugin windows: 0 automatic (phone GPU when there is one), 1 phone GPU, 2 software
    val dxvk = b("plugin.dxvk", true)               // Direct3D 9-11 for plugin windows through DXVK (Vulkan)
    // Height of the on-screen keyboard in dp (0 = the default for the screen); set by dragging its handle
    val keysHeight = s("keys.height", 0)
    // Hybrid presets: the note played when a preset is chosen (0 = C4, 1 = C5, 2 = nothing)
    val presetPreviewNote = s("plugin.presetpreview", 0)
    // Sound browser (Tracks view): docked beside the tracks (1) or a drawer over them (0)
    val browserDocked = b("browser.docked", true)
    // MIDI
    val midiAuto = b("midi.auto", true)
    val midiChannel = s("midi.channel", 0)         // 0 = all, 1..16
    // Updates
    val updAuto = b("update.auto", true)           // look for a newer version once a day
    val updPre = b("update.pre", true)             // every release so far is an alpha, so pre-releases count
    // Display
    val keepOn = s("display.keepon", 0)            // 0 never, 1 while playing, 2 always
    val orientation = s("display.orientation", 2)  // 0 automatic (rotates with the phone), 1 portrait, 2 landscape; landscape without rotation by default
    // Defaults since 0.20: landscape, full screen, drawing under the notch. People who already chose something keep their choice.
    val immersive = b("display.immersive", true)
    val blockCapture = b("display.blockcapture", true)   // no system screenshots / screen recording of the app (also stops OnePlus' three-finger screenshot from acting while you play)
    val cornerMargin = s("display.corners", 0)   // side margin for rounded screen corners: 0 automatic (from the phone), 1 none, 2 small, 3 medium, 4 large
    val ignoreCutout = b("display.cutout", true)  // draw under the notch / camera cutout

    val SNAP_BEATS = floatArrayOf(0.125f, 0.25f, 0.5f, 1f)
    val SNAP_LABELS = listOf("1/32", "1/16", "1/8", "1/4")
    val NOTE_BEATS = floatArrayOf(0.25f, 0.5f, 1f, 2f)
    val NOTE_LABELS = listOf("¼ beat", "½ beat", "1 beat", "2 beats")
    /** AAudio buffer size in bursts for each latency choice. */
    val BUFFER_BURSTS = intArrayOf(2, 3, 5)

    val snapBeats get() = SNAP_BEATS[snap.value.coerceIn(0, SNAP_BEATS.size - 1)]
    val noteLengthBeats get() = NOTE_BEATS[noteLength.value.coerceIn(0, NOTE_BEATS.size - 1)]
    val bufferBursts get() = BUFFER_BURSTS[latency.value.coerceIn(0, BUFFER_BURSTS.size - 1)]

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("zedok_settings", Context.MODE_PRIVATE)
        sp = p
        // One-time migration (0.20): the display defaults changed to landscape / full screen / under the notch. Values saved before this version
        // (often just a test) are dropped once so that everybody starts from the new defaults; changes made afterwards are kept.
        if (p.getInt("display.defaults", 0) < 1) p.edit().remove("display.orientation").remove("display.immersive").remove("display.cutout").putInt("display.defaults", 1).apply()
        all.forEach { it.load(p) }
    }
    internal fun save(key: String, v: Int) { sp?.edit()?.putInt(key, v)?.apply() }
    fun resetAll() { all.forEach { it.reset() } }
}
