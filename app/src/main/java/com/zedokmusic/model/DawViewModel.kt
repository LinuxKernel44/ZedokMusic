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
import com.zedokmusic.engine.AacEncoder
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
    /** The timelines scroll along with the play cursor. */
    var followPlayhead by mutableStateOf(true)
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

    // Declared before init: load() runs from init and fills them.
    private val pendingLanes = HashMap<Int, List<Pair<String, List<AutoPoint>>>>()
    private val pendingParams = HashMap<Int, List<Pair<String, Float>>>()
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
            for (t in tracks) if (t.pluginState == 2) pollEditorEdits(t)
            if (!playing) for (t in tracks) for (l in t.lanes) l.lastWrite = -1f
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
        tracks.forEach { syncNotes(it); syncLanes(it); it.clips.forEach { c -> e.moveClip(c.engineId, (c.start * fpb).toLong()) } }
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
        if (t.pluginPath != null) { closeEditor(t); host.stop(t.id) }
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

    // ---------- plugin editor (GUI) ----------
    /** Wide screens: the plugin panel takes the whole area under the transport (the arrangement is hidden). */
    var panelExpanded by mutableStateOf(false)
    val guiAvailable get() = host.gui.available
    private val editorSlots = LinkedHashMap<Int, Int>()  // track id -> slot (vertical band of the shared X screen), oldest first
    /** Opens the plugin's editor; up to three stay open at once (the oldest is closed to make room). */
    fun openEditor(t: TrackM) {
        if (editorSlots.containsKey(t.id)) { t.editorOpen = true; return }
        if (editorSlots.size >= 3) tracks.firstOrNull { it.id == editorSlots.keys.first() }?.let { closeEditor(it) }
        val slot = (0..2).first { it !in editorSlots.values }
        editorSlots[t.id] = slot
        e.guiCommand(t.id, 1, 0, slot * com.zedokmusic.wine.GuiServer.SLOT_HEIGHT)
        t.editorOpen = true
    }
    fun closeEditor(t: TrackM) { if (editorSlots.remove(t.id) != null) e.guiCommand(t.id, 2, 0, 0); t.editorOpen = false }
    fun editorState(t: TrackM): IntArray = e.guiState(t.id)

    // ---------- plugin parameters ----------
    fun loadParams(t: TrackM): List<ParamM> {
        val n = e.paramCount(t.id); val out = FloatArray(2); val list = ArrayList<ParamM>(n)
        for (i in 0 until n) e.paramInfo(t.id, i, out)?.let { list.add(ParamM(i, it[0], it[1], out[0], out[1].toInt())) }
        t.paramNames = list.associate { it.index to it.name }
        return list
    }
    fun paramValue(t: TrackM, i: Int) = e.paramValue(t.id, i)
    fun paramText(t: TrackM, i: Int) = e.paramText(t.id, i)
    fun setParam(t: TrackM, i: Int, v: Float) { e.setParam(t.id, i, v); t.paramOverrides[i] = v; dirty = true; recordAutomation(t, i, v) }
    fun resetParam(t: TrackM, p: ParamM) { e.setParam(t.id, p.index, p.default); t.paramOverrides.remove(p.index); dirty = true }
    // ---------- parameter automation ----------
    var laneSelected by mutableStateOf<AutoLane?>(null)
    private fun paramNameOf(t: TrackM, index: Int): String? {
        if (t.paramNames.isEmpty()) loadParams(t)
        return t.paramNames[index]
    }
    /** Creates the lane (seeded with the parameter's current value at beat 0) or returns the existing one. */
    fun addLane(t: TrackM, index: Int): AutoLane? {
        val name = paramNameOf(t, index) ?: return null
        t.lanes.firstOrNull { it.paramIndex == index }?.let { return it }
        if (t.lanes.size >= 32) { message = "At most 32 automation lanes per track."; return null }
        val l = AutoLane(name, index); l.points.add(AutoPoint(0f, e.paramValue(t.id, index)))
        t.lanes.add(l); syncLanes(t); dirty = true; return l
    }
    fun removeLane(t: TrackM, l: AutoLane) { t.lanes.remove(l); if (laneSelected == l) laneSelected = null; syncLanes(t); dirty = true }
    fun setLanePoints(t: TrackM, l: AutoLane, pts: List<AutoPoint>) {
        val sorted = pts.sortedBy { it.beat }
        l.points.clear(); l.points.addAll(sorted); syncLanes(t); dirty = true
    }
    private fun syncLanes(t: TrackM) {
        val lanes = t.lanes.filter { it.points.isNotEmpty() }
        val meta = LongArray(lanes.size * 2); val frames = ArrayList<Long>(); val values = ArrayList<Float>(); val shapes = ArrayList<Byte>()
        lanes.forEachIndexed { i, l -> meta[i * 2] = l.paramIndex.toLong(); meta[i * 2 + 1] = l.points.size.toLong()
            l.points.forEach { frames.add((it.beat * fpb).toLong()); values.add(it.value); shapes.add(it.shape.toByte()) } }
        e.setAutomation(t.id, meta, frames.toLongArray(), values.toFloatArray(), shapes.toByteArray())
    }
    /** While recording (transport running), writes the touched parameter into its lane at the playhead. */
    private fun recordAutomation(t: TrackM, index: Int, v: Float) {
        if (!recording || !e.isPlaying()) return
        val l = addLane(t, index) ?: return
        val b = playhead
        val last = l.points.lastOrNull { it.beat <= b }
        if (last != null && b - last.beat < 0.06f && kotlin.math.abs(last.value - v) < 0.004f) return
        // Overwrite what lies just ahead of the playhead (older points are eaten as the pass advances); keep everything behind it.
        val kept = l.points.filter { !(it.beat > b - 0.001f && it.beat <= b + 0.25f) }.toMutableList()
        kept.add(AutoPoint(b, v)); l.lastWrite = b
        setLanePoints(t, l, kept.distinctBy { it.beat })
    }
    /** Drains the edits made inside the plugin's own editor: they count as changed parameters and are recorded when armed. */
    private fun pollEditorEdits(t: TrackM) {
        val ed = e.pollEdits(t.id)
        var i = 0
        while (i + 1 < ed.size) {
            val idx = ed[i].toInt(); val v = ed[i + 1]; i += 2
            t.paramOverrides[idx] = v; dirty = true
            recordAutomation(t, idx, v)
        }
    }
    /** Re-applies saved values once the plugin is up (matching by name, so a plugin update that shifts indices stays safe). */
    private fun applyPending(t: TrackM) {
        val lanes = pendingLanes.remove(t.id)
        val pending = pendingParams.remove(t.id)
        if (lanes == null && pending == null) return
        val byName = loadParams(t).associateBy { it.name }
        lanes?.forEach { (name, pts) -> byName[name]?.let { p -> val l = AutoLane(name, p.index); l.points.addAll(pts); t.lanes.add(l) } }
        if (lanes != null) syncLanes(t)
        if (pending == null) return
        for ((name, v) in pending) byName[name]?.let { e.setParam(t.id, it.index, v); t.paramOverrides[it.index] = v }
    }

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

    // ---------- export ----------
    var exporting by mutableStateOf(false); private set
    /**
     * Renders to Exports/. [range] 0 = whole project (+2 s tail), 1 = loop region. [format] 0 = WAV 16-bit, 1 = WAV 24-bit, 2 = WAV float,
     * 3 = AAC (.m4a, 256 kbps), 4/5 = FLAC 16/24-bit, 6/7 = MP3 320/192 kbps. [stems]: one file per track instead of the mix.
     */
    fun exportAudio(range: Int, format: Int, name: String, stems: Boolean) {
        val dir = workspace?.let { File(it, "Exports") } ?: run { message = "No workspace folder."; return }
        if (exporting) return
        val to = if (range == 1) (loopBars * 4 * fpb).toLong()
            else (tracks.maxOfOrNull { it.lengthBeats(bpm) }?.let { (it * fpb).toLong() + 2 * 48000L } ?: 0L)
        if (to <= 0 || tracks.all { it.notes.isEmpty() && it.clips.isEmpty() }) { message = "Nothing to export: add notes or clips first."; return }
        e.pause(); playing = false
        dir.mkdirs()
        val base = name.trim().ifEmpty { "Mix" }.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        val ext = when (format) { 3 -> "m4a"; 4, 5 -> "flac"; 6, 7 -> "mp3"; else -> "wav" }
        fun unique(stem: String): File { var f = File(dir, "$stem.$ext"); var n = 2; while (f.exists()) f = File(dir, "$stem ($n++).$ext"); return f }
        val jobs: List<Pair<File, Int>> = if (stems) tracks.filter { it.notes.isNotEmpty() || it.clips.isNotEmpty() }
            .map { unique("$base - " + it.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_")) to it.id } else listOf(unique(base) to 0)
        if (jobs.isEmpty()) { message = "No track has anything to export."; return }
        exporting = true; message = null
        Thread {
            var done = 0; var err: String? = null; var bytes = 0L
            for ((out, track) in jobs) {
                try {
                    val wav = if (format == 3) File(getApplication<Application>().cacheDir, "export_tmp.wav") else out
                    val wavFmt = if (format == 3) 0 else format
                    val ok = e.renderEncoded(wav.absolutePath, 0L, to, wavFmt, track)  // track 0 = the full mix
                    if (!ok) { err = "Render failed."; break }
                    if (format == 3) { err = AacEncoder.encode(wav, out); wav.delete(); if (err != null) break }
                    done++; bytes += out.length()
                } catch (ex: Throwable) { err = "Export failed: ${ex.message}"; break }
            }
            exporting = false
            message = if (err == null) (if (done == 1) "Exported ${jobs[0].first.name}" else "Exported $done files") + " (%.1f MB) to Exports".format(bytes / 1048576.0) else err
        }.start()
    }

    /** Files in the workspace's Exports folder, newest first. */
    fun exportedFiles(): List<File> = workspace?.let { File(it, "Exports") }?.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("wav", "flac", "mp3", "m4a") }
        ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ---------- persistence ----------
    fun save() {
        val f = projectFile ?: return
        val root = JSONObject().put("version", 1).put("bpm", bpm.toDouble()).put("master", masterGain.toDouble()).put("loopBars", loopBars)
        val arr = JSONArray()
        for (t in tracks) arr.put(JSONObject().put("name", t.name).put("kind", t.kind.name).put("gain", t.gain.toDouble()).put("pan", t.pan.toDouble())
            .put("mute", t.mute).put("solo", t.solo).put("plugin", t.pluginPath ?: JSONObject.NULL)
            .put("notes", JSONArray().also { a -> t.notes.forEach { n -> a.put(JSONArray().put(n.pitch).put(n.start.toDouble()).put(n.length.toDouble()).put(n.velocity)) } })
            .put("params", JSONArray().also { a -> t.paramOverrides.forEach { (i, v) -> t.paramNames[i]?.let { nm -> a.put(JSONArray().put(nm).put(v.toDouble())) } } })
            .put("lanes", JSONArray().also { a -> t.lanes.forEach { l -> a.put(JSONObject().put("param", l.paramName).put("points", JSONArray().also { pa -> l.points.forEach { p -> pa.put(JSONArray().put(p.beat.toDouble()).put(p.value.toDouble()).put(p.shape)) } })) } })
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
                o.optJSONArray("lanes")?.let { la -> pendingLanes[t.id] = (0 until la.length()).map { k -> val lo = la.getJSONObject(k); val pa = lo.getJSONArray("points")
                    lo.getString("param") to (0 until pa.length()).map { q -> pa.getJSONArray(q).let { x -> AutoPoint(x.getDouble(0).toFloat(), x.getDouble(1).toFloat(), x.optInt(2, 0)) } } } }
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
