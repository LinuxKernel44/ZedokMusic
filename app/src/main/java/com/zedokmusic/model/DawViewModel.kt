package com.zedokmusic.model

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zedokmusic.engine.NativeEngine
import com.zedokmusic.engine.MidiInput
import com.zedokmusic.engine.PluginHost
import com.zedokmusic.wine.StorageFolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Holds the project (in beats), mirrors it into the native engine, and runs the UI ticker (playhead, meters, plugin state). */
class DawViewModel(app: Application) : AndroidViewModel(app) {
    private val e = NativeEngine
    private val host = PluginHost(app)
    val workspace: File? = StorageFolder.saved(app)
    private val projectFile get() = workspace?.let { File(it, "Projects/Untitled.zedok") }

    val tracks = mutableStateListOf<TrackM>()
    var bpm by mutableFloatStateOf(120f); private set
    var playing by mutableStateOf(false); private set
    var loop by mutableStateOf(false); private set
    var loopBars by mutableIntStateOf(4); private set
    var playhead by mutableFloatStateOf(0f); private set
    var selected by mutableStateOf<TrackM?>(null)
    var masterGain by mutableFloatStateOf(1f); private set
    var masterMeter by mutableFloatStateOf(0f); private set
    var engineInfo by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var engineOk by mutableStateOf(false); private set

    // ---------- recording and MIDI input ----------
    val midi = MidiInput(app) { st, d1, d2 -> onMidi(st, d1, d2) }
    var recording by mutableStateOf(false); private set
    var recLevel by mutableFloatStateOf(0f); private set
    private var recMidi = false
    private var recTrack: TrackM? = null
    private var recPath: File? = null
    private var recStartBeat = 0f
    private val openNotes = HashMap<Int, Pair<Float, Int>>()  // pitch -> (start beat, velocity)

    private fun onMidi(status: Int, d1: Int, d2: Int) {
        val cmd = status and 0xF0
        val t = selected?.takeIf { it.kind == TrackKind.Instrument } ?: tracks.firstOrNull { it.kind == TrackKind.Instrument } ?: return
        if (cmd == 0x90 && d2 > 0) keyEvent(t, true, d1, d2) else if (cmd == 0x80 || cmd == 0x90) keyEvent(t, false, d1, 0)
    }
    /** Single entry point for notes played live (MIDI keyboard or on-screen keys): sounds the plugin and, when recording, captures the note. */
    fun keyEvent(t: TrackM, on: Boolean, pitch: Int, velocity: Int) {
        e.liveNote(t.id, on, pitch, if (velocity > 0) velocity else 100)
        if (!recording || !recMidi || recTrack != t || !e.isPlaying()) return
        if (on) openNotes[pitch] = playhead to velocity.coerceIn(1, 127)
        else openNotes.remove(pitch)?.let { (st, v) -> addNote(t, pitch, st, maxOf(0.0625f, playhead - st), v) }
    }

    /** Starts recording into the selected track (audio track = microphone/interface input, instrument track = MIDI). Returns an error message or null. */
    fun startRecording(): String? {
        val t = selected ?: return "Select a track to record into."
        recTrack = t; recStartBeat = playhead; openNotes.clear()
        if (t.kind == TrackKind.Instrument) { recMidi = true }
        else {
            recMidi = false
            val dir = workspace?.let { File(it, "Samples/Recordings") } ?: return "No workspace folder."
            dir.mkdirs()
            val f = File(dir, "Take_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".wav")
            val err = e.recStart(f.absolutePath, 0)
            if (err.isNotEmpty()) return err
            recPath = f
        }
        recording = true
        if (!e.isPlaying()) e.play()
        return null
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        val t = recTrack; e.pause()
        if (recMidi) {
            if (t != null) for ((p, sv) in openNotes) addNote(t, p, sv.first, maxOf(0.0625f, playhead - sv.first), sv.second)
            openNotes.clear()
        } else {
            val r = e.recStop(); val f = recPath
            if (t != null && f != null && r[4] == 1L && r[0] > 0) {
                // The performer heard the output one output-buffer late and the input arrives one input-buffer late: shift the take earlier.
                val comp = e.stats()[2].toDouble() + r[2].toDouble()
                importClip(t, f, maxOf(0f, recStartBeat - (comp / fpb).toFloat()))
                if (r[1] > 0) message = "Recorded with ${r[1]} dropped frames (storage too slow)."
            } else message = "Nothing was recorded."
        }
        recLevel = 0f
    }

    private val fpb get() = 48000.0 * 60.0 / bpm  // frames per beat
    private var dirty = false

    init {
        engineOk = e.start()
        midi.start()
        if (!engineOk) message = "Audio output could not be started."
        load()
        viewModelScope.launch { ticker() }
    }

    // ---------- ticker ----------
    private suspend fun ticker() {
        var n = 0
        while (true) {
            delay(33)
            playing = e.isPlaying()
            playhead = (e.position() / fpb).toFloat()
            for (t in tracks) { t.meter = maxOf(e.peak(t.id), t.meter * 0.86f) }
            masterMeter = maxOf(e.peak(-1), masterMeter * 0.86f)
            if (recording && !recMidi) recLevel = maxOf(e.recLevel(), recLevel * 0.86f)
            if (++n % 15 == 0) {
                for (t in tracks) if (t.pluginPath != null && t.pluginState < 2) {
                    t.pluginState = e.pluginState(t.id)
                    if (t.pluginState == 2) { t.pluginName = e.pluginName(t.id); applyPending(t) }
                }
                val s = e.stats()
                if (s[0] > 0) engineInfo = "%d Hz · %.1f ms · %d xruns".format(s[0].toInt(), s[6], s[3].toInt())
                if (dirty && !playing) { save(); dirty = false }
            }
        }
    }

    // ---------- transport ----------
    fun togglePlay() { if (e.isPlaying()) e.pause() else e.play(); playing = e.isPlaying() }
    fun stop() { e.pause(); e.seek(0); playing = false; playhead = 0f }
    fun seekBeat(beat: Float) { e.seek((beat.coerceAtLeast(0f) * fpb).toLong()); playhead = beat.coerceAtLeast(0f) }
    fun toggleLoop() { loop = !loop; applyLoop() }
    fun changeLoopBars(b: Int) { loopBars = b.coerceIn(1, 64); applyLoop() }
    private fun applyLoop() = e.setLoop(loop, 0, (loopBars * 4 * fpb).toLong())
    fun changeBpm(v: Float) {
        val old = bpm; bpm = v.coerceIn(30f, 300f)
        if (old == bpm) return
        val beat = playhead
        tracks.forEach { syncNotes(it); it.clips.forEach { c -> e.moveClip(c.engineId, (c.start * fpb).toLong()) } }
        applyLoop(); e.seek((beat * fpb).toLong()); dirty = true
    }

    // ---------- tracks ----------
    private fun newColor() = TrackColors[tracks.size % TrackColors.size]
    fun addInstrument(plugin: PluginInfo) {
        val id = e.addTrack(); if (id == 0) { message = "Track limit reached."; return }
        val t = TrackM(id, plugin.name, TrackKind.Instrument, newColor(), plugin.path)
        tracks.add(t); selected = t
        t.pluginState = 1
        if (!host.launch(id, plugin.path)) { t.pluginState = 3; message = "Could not start the plugin host." }
        dirty = true
    }
    fun addAudioTrack(): TrackM? {
        val id = e.addTrack(); if (id == 0) { message = "Track limit reached."; return null }
        val t = TrackM(id, "Audio ${tracks.count { it.kind == TrackKind.Audio } + 1}", TrackKind.Audio, newColor(), null)
        tracks.add(t); selected = t; dirty = true; return t
    }
    fun removeTrack(t: TrackM) {
        if (t.pluginPath != null) host.stop(t.id)
        e.removeTrack(t.id); tracks.remove(t); if (selected == t) selected = tracks.firstOrNull(); dirty = true
    }
    fun importClip(t: TrackM, wav: File, startBeat: Float = 0f) {
        val r = e.loadClip(t.id, wav.absolutePath, (startBeat * fpb).toLong())
        if (r == null) { message = "Could not read ${wav.name} (WAV only)."; return }
        t.clips.add(ClipM(r[0].toInt(), wav.absolutePath, wav.nameWithoutExtension, startBeat, r[1])); dirty = true
    }
    fun moveClip(c: ClipM, startBeat: Float) {
        c.start = startBeat.coerceAtLeast(0f); e.moveClip(c.engineId, (c.start * fpb).toLong()); dirty = true
    }
    fun removeClip(t: TrackM, c: ClipM) { e.removeClip(c.engineId); t.clips.remove(c); dirty = true }

    fun setGain(t: TrackM, g: Float) { t.gain = g; e.setTrackGain(t.id, g); dirty = true }
    fun setPan(t: TrackM, p: Float) { t.pan = p; e.setTrackPan(t.id, p); dirty = true }
    fun setMute(t: TrackM, m: Boolean) { t.mute = m; e.setTrackMute(t.id, m); dirty = true }
    fun setSolo(t: TrackM, s: Boolean) { t.solo = s; e.setTrackSolo(t.id, s); dirty = true }
    fun setMaster(g: Float) { masterGain = g; e.setMasterGain(g); dirty = true }

    // ---------- notes ----------
    fun addNote(t: TrackM, pitch: Int, start: Float, length: Float, velocity: Int = 100) { t.notes.add(NoteM(pitch, start, length, velocity)); syncNotes(t); dirty = true }
    fun removeNote(t: TrackM, n: NoteM) { t.notes.remove(n); syncNotes(t); dirty = true }
    fun replaceNote(t: TrackM, old: NoteM, new: NoteM) { val i = t.notes.indexOf(old); if (i >= 0) { t.notes[i] = new; syncNotes(t); dirty = true } }
    private fun syncNotes(t: TrackM) {
        val d = LongArray(t.notes.size * 4)
        t.notes.forEachIndexed { i, n ->
            d[i * 4] = (n.start * fpb).toLong(); d[i * 4 + 1] = (n.length * fpb).toLong(); d[i * 4 + 2] = n.pitch.toLong(); d[i * 4 + 3] = n.velocity.toLong()
        }
        e.setNotes(t.id, d)
    }
    fun liveNote(t: TrackM, on: Boolean, pitch: Int) = keyEvent(t, on, pitch, 105)

    // ---------- plugin parameters ----------
    fun loadParams(t: TrackM): List<ParamM> {
        val n = e.paramCount(t.id); val out = FloatArray(2); val list = ArrayList<ParamM>(n)
        for (i in 0 until n) e.paramInfo(t.id, i, out)?.let { list.add(ParamM(i, it[0], it[1], out[0], out[1].toInt())) }
        t.paramNames = list.associate { it.index to it.name }
        return list
    }
    fun paramValue(t: TrackM, i: Int) = e.paramValue(t.id, i)
    fun paramText(t: TrackM, i: Int) = e.paramText(t.id, i)
    fun setParam(t: TrackM, i: Int, v: Float) { e.setParam(t.id, i, v); t.paramOverrides[i] = v; dirty = true }
    fun resetParam(t: TrackM, p: ParamM) { e.setParam(t.id, p.index, p.default); t.paramOverrides.remove(p.index); dirty = true }
    /** Re-applies saved values once the plugin is up (matching by name, so a plugin update that shifts indices stays safe). */
    private fun applyPending(t: TrackM) {
        val pending = pendingParams.remove(t.id) ?: return
        val byName = loadParams(t).associateBy { it.name }
        for ((name, v) in pending) byName[name]?.let { e.setParam(t.id, it.index, v); t.paramOverrides[it.index] = v }
    }
    private val pendingParams = HashMap<Int, List<Pair<String, Float>>>()

    // ---------- plugins ----------
    fun pluginDir(): File? = workspace?.let { File(it, "Plugins/VST3") }
    fun scanPlugins(): List<PluginInfo> {
        val dir = pluginDir() ?: return emptyList()
        val out = ArrayList<PluginInfo>()
        dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
            if (!f.name.endsWith(".vst3", true)) return@forEach
            val dll = if (f.isFile) f else f.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".vst3", true) && it.path.contains("x86_64-win") }
            if (dll != null) out.add(PluginInfo(f.nameWithoutExtension, dll.path))
        }
        return out
    }
    fun samples(): List<File> = workspace?.let { File(it, "Samples").walkTopDown().filter { f -> f.isFile && f.extension.equals("wav", true) }.sortedBy { f -> f.name.lowercase() }.toList() } ?: emptyList()

    // ---------- persistence ----------
    fun save() {
        val f = projectFile ?: return
        val root = JSONObject().put("version", 1).put("bpm", bpm.toDouble()).put("master", masterGain.toDouble()).put("loopBars", loopBars)
        val arr = JSONArray()
        for (t in tracks) arr.put(JSONObject().put("name", t.name).put("kind", t.kind.name).put("gain", t.gain.toDouble()).put("pan", t.pan.toDouble())
            .put("mute", t.mute).put("solo", t.solo).put("plugin", t.pluginPath ?: JSONObject.NULL)
            .put("notes", JSONArray().also { a -> t.notes.forEach { n -> a.put(JSONArray().put(n.pitch).put(n.start.toDouble()).put(n.length.toDouble()).put(n.velocity)) } })
            .put("params", JSONArray().also { a -> t.paramOverrides.forEach { (i, v) -> t.paramNames[i]?.let { nm -> a.put(JSONArray().put(nm).put(v.toDouble())) } } })
            .put("clips", JSONArray().also { a -> t.clips.forEach { c -> a.put(JSONObject().put("path", c.path).put("start", c.start.toDouble())) } }))
        root.put("tracks", arr)
        try { f.parentFile?.mkdirs(); f.writeText(root.toString(1)) } catch (ex: Exception) { message = "Could not save the project: ${ex.message}" }
    }
    private fun load() {
        val f = projectFile ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            bpm = root.optDouble("bpm", 120.0).toFloat(); masterGain = root.optDouble("master", 1.0).toFloat(); e.setMasterGain(masterGain)
            loopBars = root.optInt("loopBars", 4)
            val arr = root.getJSONArray("tracks")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plugin = if (o.isNull("plugin")) null else o.getString("plugin")
                val t: TrackM
                if (plugin != null) {
                    val id = e.addTrack(); t = TrackM(id, o.getString("name"), TrackKind.Instrument, newColor(), plugin); tracks.add(t)
                    t.pluginState = 1; if (!host.launch(id, plugin)) t.pluginState = 3
                } else { val id = e.addTrack(); t = TrackM(id, o.getString("name"), TrackKind.Audio, newColor(), null); tracks.add(t) }
                t.gain = o.optDouble("gain", 1.0).toFloat(); t.pan = o.optDouble("pan", 0.0).toFloat()
                t.mute = o.optBoolean("mute"); t.solo = o.optBoolean("solo")
                e.setTrackGain(t.id, t.gain); e.setTrackPan(t.id, t.pan); e.setTrackMute(t.id, t.mute); e.setTrackSolo(t.id, t.solo)
                val na = o.getJSONArray("notes")
                for (k in 0 until na.length()) { val n = na.getJSONArray(k); t.notes.add(NoteM(n.getInt(0), n.getDouble(1).toFloat(), n.getDouble(2).toFloat(), n.getInt(3))) }
                syncNotes(t)
                o.optJSONArray("params")?.let { pa -> pendingParams[t.id] = (0 until pa.length()).map { k -> pa.getJSONArray(k).let { x -> x.getString(0) to x.getDouble(1).toFloat() } } }
                val ca = o.getJSONArray("clips")
                for (k in 0 until ca.length()) { val c = ca.getJSONObject(k); val w = File(c.getString("path")); if (w.exists()) importClip(t, w, c.getDouble("start").toFloat()) }
            }
            selected = tracks.firstOrNull()
        } catch (ex: Exception) { message = "Could not open the saved project: ${ex.message}" }
        dirty = false
    }

    override fun onCleared() { stopRecording(); midi.stop(); save(); host.stopAll(); e.pause(); e.stop() }
}
