package com.zedokmusic.model

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zedokmusic.settings.AppSettings
import com.zedokmusic.wine.deleteTree
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
    /** Project folders (FL Studio style: one folder per project, see [ProjectStore]). */
    val store: ProjectStore? = workspace?.let { ProjectStore(it).also { s -> s.root.mkdirs(); s.migrateLegacy() } }
    private val prefs = app.getSharedPreferences("zedok", 0)
    var projectName by mutableStateOf(store?.let { s -> prefs.getString("project", null)?.takeIf { s.exists(it) } ?: s.list().firstOrNull()?.name } ?: "Untitled"); private set
    /** True while another project is being opened. */
    var projectBusy by mutableStateOf(false); private set
    private val projectFile get() = store?.file(projectName)
    fun projectDir(): File? = store?.dir(projectName)
    /** Samples that belong to this project: imports and recordings are stored here, not in a shared library. */
    fun projectSamplesDir(): File? = store?.samples(projectName)
    fun projectExportsDir(): File? = store?.exports(projectName)
    fun projectPresetsDir(): File? = store?.presets(projectName)

    val tracks = mutableStateListOf<TrackM>()
    var bpm by mutableFloatStateOf(120f); private set
    var playing by mutableStateOf(false); private set
    var loop by mutableStateOf(false); private set
    /** The timelines scroll along with the play cursor. */
    var followPlayhead: Boolean get() = AppSettings.follow.on; set(v) { AppSettings.follow.set(v) }
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
        val only = AppSettings.midiChannel.value
        if (only > 0 && cmd < 0xF0 && (status and 0x0F) != only - 1) return  // channel filter
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
            val dir = projectSamplesDir()?.let { File(it, "Recordings") } ?: return "No workspace folder."
            dir.mkdirs()
            val f = File(dir, "Take_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".wav")
            val err = e.recStart(f.absolutePath, AppSettings.inputDevice.value)
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
    private val pendingStates = HashMap<Int, Pair<String, Map<String, String>>>()   // preset name + extra state (sample path...) of built-in plugins
    // ---- view state, saved with the project: zoom (pixels per beat) and where each timeline is scrolled to ----
    /** Height factor of the arrangement's track rows (pinch vertically to change it). */
    var rowScale by mutableFloatStateOf(1f)
    /** Height factor of the piano roll's note rows (pinch vertically to change it). */
    var rollRowScale by mutableFloatStateOf(1f)
    private var ppbValue by mutableFloatStateOf(44f)
    var ppb: Float get() = ppbValue; set(v) { if (v != ppbValue) { ppbValue = v; viewDirty = true } }
    var arrangeX = 0; var arrangeY = 0; var rollX = 0; var rollY = -1   // -1: the piano roll's own default (around C5)
    /** Bumped when another project's view was loaded, so the open timelines scroll to it. */
    var viewRevision by mutableIntStateOf(0); private set
    @Volatile var viewDirty = false
    fun viewScrolled(ax: Int, ay: Int) { if (ax != arrangeX || ay != arrangeY) { arrangeX = ax; arrangeY = ay; viewDirty = true } }
    fun rollScrolled(x: Int, y: Int) { if (x != rollX || y != rollY) { rollX = x; rollY = y; viewDirty = true } }
    /** The master's insert effects (a track's own are in [TrackM.fx]). Declared before init: load() fills it. */
    val masterFx = mutableStateListOf<TrackM>()
    /** The plugin the Plugin and Auto panels show: an effect picked in the mixer, otherwise the selected instrument. */
    var pluginTarget by mutableStateOf<TrackM?>(null)
    init {
        current = this
        store?.create(projectName)
        e.configure(AppSettings.outputDevice.value, AppSettings.bufferBursts, AppSettings.exclusive.on)
        engineOk = e.start()
        midi.autoConnect = { AppSettings.midiAuto.on }
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
            val plugins = allPlugins()
            for (t in plugins) if (t.pluginState == 2) pollEditorEdits(t)
            if (!playing) for (t in plugins) for (l in t.lanes) l.lastWrite = -1f
            if (recording && !recMidi) recLevel = maxOf(e.recLevel(), recLevel * 0.86f)
            if (++n % 15 == 0) {
                for (t in plugins) if (t.pluginPath != null && t.pluginState < 2) {
                    t.pluginState = e.pluginState(t.id)
                    if (t.pluginState == 2) { t.pluginName = e.pluginName(t.id); applyPending(t); rememberKind(t) }
                }
                // A plugin that was running and whose Wine process is gone has crashed: show it (red dot) instead of a stuck editor.
                for (t in plugins) if (t.pluginPath != null && t.pluginState == 2) {
                    if (host.isRunning(t.id)) t.missed = 0 else if (++t.missed >= 3) { t.pluginState = 3; t.editorOpen = false; message = "${t.pluginName.ifBlank { t.name }} stopped unexpectedly. See Logs in your ZedokMusic folder." }
                }
                val s = e.stats()
                if (s[0] > 0) engineInfo = "%d Hz · %.1f ms · %d xruns".format(s[0].toInt(), s[6], s[3].toInt())
                if ((dirty || viewDirty) && !playing && AppSettings.autosave.on) { save(); dirty = false }
            }
        }
    }

    // ---------- audio settings ----------
    /** Re-opens the audio output with the current settings (device, buffer size, exclusive mode). Stops playback first. */
    fun restartAudio() {
        if (recording) stopRecording()
        e.pause(); playing = false
        e.stop()
        e.configure(AppSettings.outputDevice.value, AppSettings.bufferBursts, AppSettings.exclusive.on)
        engineOk = e.start()
        message = if (engineOk) null else "Audio output could not be restarted."
    }
    /** Short description of the running audio stream for the Settings screen. */
    fun audioStatus(): String {
        val s = e.stats()
        if (s[0] <= 0f) return "Audio is not running."
        return "%d Hz · burst %d · buffer %d frames (%.1f ms) · %s · %d xruns · callback avg %.0f µs, max %.0f µs"
            .format(s[0].toInt(), s[1].toInt(), s[2].toInt(), s[6], if (s[7] > 0f) "exclusive" else "shared", s[3].toInt(), s[4], s[5])
    }

    // ---------- transport ----------
    fun togglePlay() { if (e.isPlaying()) e.pause() else e.play(); playing = e.isPlaying() }
    fun stop() { e.pause(); e.seek(0); playing = false; playhead = 0f }
    fun seekBeat(beat: Float) { e.seek((beat.coerceAtLeast(0f) * fpb).toLong()); playhead = beat.coerceAtLeast(0f) }
    fun toggleLoop() { loop = !loop; applyLoop() }
    fun changeLoopBars(b: Int) { loopBars = b.coerceIn(1, 512); applyLoop() }
    /** Makes the loop region cover everything, from the start to the end of the last note or clip (whole bars), and turns looping on. */
    fun fitLoopToContent() {
        val filled = tracks.filter { it.notes.isNotEmpty() || it.clips.isNotEmpty() }
        if (filled.isEmpty()) { message = "Nothing to loop yet: add notes or clips first."; return }
        val bars = Math.round(filled.maxOf { it.lengthBeats(bpm) } / 4f).coerceAtLeast(1)
        loop = true; changeLoopBars(bars)
        message = "Loop set to $bars bar${if (bars > 1) "s" else ""}, the length of your content."
        dirty = true
    }
    private fun applyLoop() = e.setLoop(loop, 0, (loopBars * 4 * fpb).toLong())
    fun changeBpm(v: Float) {
        val old = bpm; bpm = v.coerceIn(30f, 300f)
        e.setTempo(bpm.toDouble())
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
        t.fx.toList().forEach { removeEffect(t, it) }
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

    // ---------- insert effects ----------
    /** Every plugin of the project: instruments, then each track's effects, then the master's. */
    fun allPlugins(): List<TrackM> = tracks.flatMap { t -> (if (t.pluginPath != null) listOf(t) else emptyList()) + t.fx } + masterFx
    fun fxOf(owner: TrackM?): MutableList<TrackM> = owner?.fx ?: masterFx
    /** The plugin the Plugin and Auto panels work on. */
    fun panelPlugin(): TrackM? {
        val tg = pluginTarget; val sel = selected
        if (tg != null && (tg === sel || sel?.fx?.contains(tg) == true || masterFx.contains(tg))) return tg
        return sel?.takeIf { it.kind == TrackKind.Instrument } ?: sel?.fx?.firstOrNull() ?: tracks.firstOrNull { it.kind == TrackKind.Instrument } ?: masterFx.firstOrNull()
    }

    /** Adds [plugin] at the end of the chain of [owner] (null = the master) and starts its Wine host. */
    fun addEffect(owner: TrackM?, plugin: PluginInfo): TrackM? = addEffectInternal(owner, plugin.name, plugin.path).also { if (it != null) { pluginTarget = it; dirty = true } }
    private fun addEffectInternal(owner: TrackM?, name: String, path: String): TrackM? {
        val list = fxOf(owner)
        if (list.size >= 10) { message = "At most 10 effects per mixer track."; return null }
        val id = e.addFx(owner?.id ?: 0, list.size)
        if (id == 0) { message = "Could not add the effect."; return null }
        val t = TrackM(id, name, TrackKind.Effect, owner?.color ?: Color(0xFFE6E8EE), path)
        list.add(t)
        t.pluginState = 1
        if (!host.launch(id, path)) { t.pluginState = 3; message = "Could not start the plugin host." }
        return t
    }
    fun removeEffect(owner: TrackM?, fx: TrackM) {
        closeEditor(fx); host.stop(fx.id)
        e.removeFx(fx.id); fxOf(owner).remove(fx)
        if (pluginTarget === fx) pluginTarget = null
        dirty = true
    }
    /** Moves an effect one place earlier (delta -1) or later (+1) in its chain. */
    fun moveEffect(owner: TrackM?, fx: TrackM, delta: Int) {
        val list = fxOf(owner); val i = list.indexOf(fx); val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        if (e.moveFx(fx.id, j)) { list.removeAt(i); list.add(j, fx); dirty = true }
    }
    fun setBypass(fx: TrackM, bypass: Boolean) { fx.bypass = bypass; e.setFxBypass(fx.id, bypass); dirty = true }

    // ---------- notes ----------
    fun addNote(t: TrackM, pitch: Int, start: Float, length: Float, velocity: Int = 100) { t.notes.add(NoteM(pitch, start, length, velocity)); syncNotes(t); dirty = true }
    /** A note that is sounding right now loses its Note Off when it is deleted or changed: release the track's notes so it cannot hang. */
    private fun releaseIfSounding(t: TrackM, n: NoteM) { if (e.isPlaying() && playhead >= n.start && playhead <= n.start + n.length) e.panic(t.id) }
    fun removeNote(t: TrackM, n: NoteM) { releaseIfSounding(t, n); t.notes.remove(n); syncNotes(t); dirty = true }
    fun replaceNote(t: TrackM, old: NoteM, new: NoteM) { val i = t.notes.indexOf(old); if (i >= 0) { releaseIfSounding(t, old); t.notes[i] = new; syncNotes(t); dirty = true } }
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
        if (editorSlots.size >= 3) allPlugins().firstOrNull { it.id == editorSlots.keys.first() }?.let { closeEditor(it) }
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
        val nat = t.pluginPath?.let { PluginHost.isNative(it) } == true
        for (i in 0 until n) e.paramInfo(t.id, i, out)?.let { list.add(ParamM(i, it[0], it[1], out[0], out[1].toInt()).also { p -> if (nat) p.group = e.paramGroup(t.id, i) }) }
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
        pendingStates.remove(t.id)?.let { (preset, st) -> t.presetName = preset; st.forEach { (k, x) -> t.states[k] = x; e.setPluginState(t.id, k, x) } }
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
    /** Where Windows installers put VST3 plugins: C:\Program Files\Common Files\VST3. */
    private fun installedPluginDir(): File? = workspace?.let { File(it, "C/Program Files/Common Files/VST3") }
    /** The built-in Zedok plugins (path "zedok:id"). */
    fun nativePlugins(): List<PluginInfo> = e.nativePlugins().mapNotNull { s -> s.split('|').takeIf { it.size >= 4 }?.let { PluginInfo(it[1], PluginHost.NATIVE_PREFIX + it[0], it[2].toInt(), it[3]) } }
    fun scanPlugins(): List<PluginInfo> {
        val out = ArrayList<PluginInfo>(nativePlugins())
        for (dir in listOfNotNull(pluginDir(), installedPluginDir())) {
            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                if (!f.name.endsWith(".vst3", true)) return@forEach
                val dll = if (f.isFile) f else f.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".vst3", true) && it.path.contains("x86_64-win") }
                if (dll != null && out.none { it.path == dll.path }) out.add(PluginInfo(f.nameWithoutExtension.replace(Regex("\\s*\\((x64|64-?bit)\\)$", RegexOption.IGNORE_CASE), ""), dll.path, kindOfPath(dll.path)))  // u-he installers name the 64-bit file "Name(x64).vst3"
            }
        }
        return out
    }

    // ---------- plugin kinds: instrument or effect ----------
    private val kindFile get() = File(getApplication<Application>().filesDir, "plugin_kinds.json")
    private var kindCache: JSONObject? = null
    private fun kinds(): JSONObject = kindCache ?: (runCatching { JSONObject(kindFile.readText()) }.getOrNull() ?: JSONObject()).also { kindCache = it }
    private fun stamp(f: File) = "${f.length()}:${f.lastModified()}"
    /** 0 = not checked yet, 1 instrument, 2 effect, 3 checked but the plugin does not say. Stale when the file changed. */
    fun kindOfPath(path: String): Int { val a = kinds().optJSONArray(path) ?: return 0; return if (a.optString(0) == stamp(File(path))) a.optInt(1) else 0 }
    private fun setKind(path: String, kind: Int) { kinds().put(path, JSONArray().put(stamp(File(path))).put(kind)); runCatching { kindFile.writeText(kinds().toString()) } }
    /** Bumped when plugin kinds become known, so open pickers re-list. */
    var pluginsVersion by mutableIntStateOf(0); private set
    private fun rememberKind(t: TrackM) {
        val p = t.pluginPath ?: return
        if (PluginHost.isNative(p)) return
        val k = e.pluginKind(t.id)
        if (k in 1..2 && kindOfPath(p) != k) { setKind(p, k); pluginsVersion++ }
    }
    var probing by mutableStateOf(false); private set
    /** Reads the type (instrument / effect) of plugins not seen before, one by one, without starting them. */
    fun probeUnknown(plugins: List<PluginInfo>) {
        val todo = plugins.filter { it.kind == 0 }
        if (probing || todo.isEmpty()) return
        probing = true
        Thread {
            try { for (p in todo) { val k = host.probe(p.path); setKind(p.path, if (k in 1..2) k else 3); pluginsVersion++ } }
            finally { probing = false }
        }.start()
    }

    // ---------- full Windows desktop ----------
    var desktopOpen by mutableStateOf(false); private set
    var desktopStarting by mutableStateOf(false); private set
    var desktopW by mutableIntStateOf(0); private set
    var desktopH by mutableIntStateOf(0); private set

    /** Opens a complete Windows desktop (taskbar, file manager, registry...). The project is saved and its plugins are stopped: the desktop needs the whole X screen, and they come back when it closes. */
    fun openDesktop(w: Int, h: Int, pluginPath: String? = null) {
        if (desktopOpen || desktopStarting || projectBusy) return
        if (recording) stopRecording()
        e.pause(); playing = false
        save()
        desktopStarting = true; desktopW = w; desktopH = h
        closeCurrent()
        Thread {
            Thread.sleep(2500)  // let the stopped plugins' Wine processes end
            val ok = host.startDesktop(w, h, pluginPath)
            if (ok) { desktopOpen = true } else { message = "Could not start the Windows desktop."; host.stopDesktop(); reloadCurrent() }
            desktopStarting = false
        }.start()
    }
    fun closeDesktop() {
        if (!desktopOpen) return
        desktopOpen = false; desktopStarting = true
        Thread { host.stopDesktop(); desktopStarting = false; viewModelScope.launch { reloadCurrent() } }.start()
    }

    // ---------- Windows plugin installers ----------
    /** Name of the installer whose screen is shown (null = none). */
    var installerName by mutableStateOf<String?>(null); private set
    var installerRunning by mutableStateOf(false); private set
    var installerCopying by mutableStateOf(false); private set
    fun installersDir(): File? = workspace?.let { File(it, "Installers") }
    fun installerFiles(): List<File> = installersDir()?.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("exe", "msi") }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Copies a file chosen with the system picker into the Installers folder (Wine can only reach the workspace). */
    fun importInstaller(uri: android.net.Uri) {
        val dir = installersDir() ?: run { message = "No workspace folder."; return }
        if (installerCopying) return
        installerCopying = true
        Thread {
            try {
                dir.mkdirs()
                val cr = getApplication<android.app.Application>().contentResolver
                val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: uri.lastPathSegment?.substringAfterLast('/') ?: "installer.exe"
                val safe = name.replace(Regex("[^A-Za-z0-9 _.()+-]"), "_")
                if (safe.substringAfterLast('.', "").lowercase() !in setOf("exe", "msi")) { message = "Choose a Windows installer (.exe or .msi)."; return@Thread }
                val out = File(dir, safe)
                cr.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 20) } } ?: run { message = "Cannot read that file."; return@Thread }
                message = "Copied $safe to the Installers folder."
            } catch (e: Exception) { message = "Could not copy the installer: ${e.message}" }
            finally { installerCopying = false }
        }.start()
    }

    fun deleteInstaller(f: File) { f.delete() }

    fun runInstaller(f: File) {
        if (installerRunning) return
        e.pause(); playing = false
        tracks.filter { it.editorOpen }.forEach { closeEditor(it) }  // the installer's desktop uses the top of the same X screen
        installerName = f.name; installerRunning = true
        host.runInstaller(f,
            onStarted = { ok -> if (!ok) { installerRunning = false; installerName = null; message = "Could not start the installer. Is the Windows runtime installed?" } },
            onExit = { code -> installerRunning = false; message = if (code == 0) "Installer finished. Plugins it installed appear in Add → Instrument track." else "Installer ended (code $code)." })
    }
    fun stopInstaller() { host.stopInstaller() }
    /** Leaves the installer screen (only once it has ended). */
    fun closeInstaller() { if (!installerRunning) installerName = null }
    /** Bumped when files are copied into the project's Samples folder, so open lists refresh. */
    var samplesVersion by mutableIntStateOf(0); private set
    private fun wavs(dir: File?) = dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { f -> f.isFile && f.extension.equals("wav", true) }?.sortedBy { f -> f.name.lowercase() }?.toList() ?: emptyList()
    /** WAV files of this project (its own Samples folder, recordings included). */
    fun projectSamples(): List<File> { samplesVersion; return wavs(projectSamplesDir()) }
    /** WAV files of the shared library (workspace/Samples), kept from before samples became per project. */
    fun sharedSamples(): List<File> { samplesVersion; return wavs(workspace?.let { File(it, "Samples") }) }

    /** Copies the chosen files into this project's Samples folder (the originals are left where they are). */
    fun importSamples(uris: List<android.net.Uri>) {
        val dir = projectSamplesDir() ?: run { message = "No workspace folder."; return }
        Thread {
            var ok = 0; var skipped = 0
            try {
                dir.mkdirs()
                val cr = getApplication<Application>().contentResolver
                for (uri in uris) {
                    val name = cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        ?: uri.lastPathSegment?.substringAfterLast('/') ?: "sample.wav"
                    val safe = name.replace(Regex("[^A-Za-z0-9 _.()+-]"), "_")
                    if (!safe.endsWith(".wav", true)) { skipped++; continue }
                    var out = File(dir, safe); var n = 2
                    while (out.exists()) out = File(dir, safe.substringBeforeLast('.') + " ($n)." + safe.substringAfterLast('.')).also { n++ }
                    cr.openInputStream(uri)?.use { i -> out.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }?.let { ok++ }
                }
            } catch (e: Exception) { message = "Could not copy the sample: ${e.message}"; return@Thread }
            samplesVersion++
            message = when { ok > 0 && skipped == 0 -> "Copied $ok sample(s) into this project."; ok > 0 -> "Copied $ok; $skipped skipped (only WAV files are supported)."; else -> "Only WAV files are supported." }
        }.start()
    }

    // ---------- export ----------
    var exporting by mutableStateOf(false); private set
    /**
     * Renders to Exports/. [range] 0 = whole project (+2 s tail), 1 = loop region. [format] 0 = WAV 16-bit, 1 = WAV 24-bit, 2 = WAV float,
     * 3 = AAC (.m4a, 256 kbps), 4/5 = FLAC 16/24-bit, 6/7 = MP3 320/192 kbps. [stems]: one file per track instead of the mix.
     */
    fun exportAudio(range: Int, format: Int, name: String, stems: Boolean) {
        val dir = projectExportsDir() ?: run { message = "No workspace folder."; return }
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
    private val audioExt = setOf("wav", "flac", "mp3", "m4a")
    /** This project's rendered files, then those left in the old shared Exports folder; newest first within each. */
    fun exportedFiles(): List<File> {
        fun list(d: File?) = d?.listFiles { f -> f.isFile && f.extension.lowercase() in audioExt }?.sortedByDescending { it.lastModified() } ?: emptyList()
        return list(projectExportsDir()) + list(workspace?.let { File(it, "Exports") })
    }
    fun isSharedExport(f: File) = workspace != null && f.parentFile?.canonicalPath == File(workspace, "Exports").canonicalPath

    // ---------- persistence ----------
    /** Writes the project when something changed, including the zoom and scroll positions (called when the app leaves the screen). */
    fun flush() { if (!projectBusy && (dirty || viewDirty) && AppSettings.autosave.on && !recording) { save(); dirty = false } }

    fun save() {
        val f = projectFile ?: return
        viewDirty = false
        store?.backup(projectName)
        store?.create(projectName)
        write(f)
    }

    /** Writes the project to [f]; [mapPath] rewrites sample paths (used when the project moves to another folder). */
    private fun write(f: File, mapPath: (String) -> String = { it }) {
        val root = JSONObject().put("version", 1).put("bpm", bpm.toDouble()).put("master", masterGain.toDouble()).put("loopBars", loopBars).put("loop", loop)
            .put("view", JSONObject().put("ppb", ppb.toDouble()).put("ax", arrangeX).put("ay", arrangeY).put("rx", rollX).put("ry", rollY))
        val arr = JSONArray()
        for (t in tracks) arr.put(JSONObject().put("name", t.name).put("kind", t.kind.name).put("gain", t.gain.toDouble()).put("pan", t.pan.toDouble())
            .put("mute", t.mute).put("solo", t.solo).put("plugin", t.pluginPath ?: JSONObject.NULL)
            .put("notes", JSONArray().also { a -> t.notes.forEach { n -> a.put(JSONArray().put(n.pitch).put(n.start.toDouble()).put(n.length.toDouble()).put(n.velocity)) } })
            .put("params", paramsJson(t)).put("lanes", lanesJson(t)).put("fx", fxJson(t.fx)).put("preset", t.presetName).put("nstate", JSONObject(t.states.toMap()))
            .put("clips", JSONArray().also { a -> t.clips.forEach { c -> a.put(JSONObject().put("path", mapPath(c.path)).put("start", c.start.toDouble())) } }))
        root.put("tracks", arr).put("masterFx", fxJson(masterFx))
        try { f.parentFile?.mkdirs(); f.writeText(root.toString(1)) } catch (ex: Exception) { message = "Could not save the project: ${ex.message}" }
    }

    private fun paramsJson(t: TrackM) = JSONArray().also { a -> t.paramOverrides.forEach { (i, v) -> t.paramNames[i]?.let { nm -> a.put(JSONArray().put(nm).put(v.toDouble())) } } }
    private fun lanesJson(t: TrackM) = JSONArray().also { a -> t.lanes.forEach { l -> a.put(JSONObject().put("param", l.paramName).put("points", JSONArray().also { pa -> l.points.forEach { p -> pa.put(JSONArray().put(p.beat.toDouble()).put(p.value.toDouble()).put(p.shape)) } })) } }
    private fun fxJson(list: List<TrackM>) = JSONArray().also { a -> list.forEach { f -> a.put(JSONObject().put("plugin", f.pluginPath).put("name", f.name).put("bypass", f.bypass).put("params", paramsJson(f)).put("lanes", lanesJson(f)).put("preset", f.presetName).put("nstate", JSONObject(f.states.toMap()))) } }
    /** Saved parameter values and automation, applied once the plugin is up (see applyPending). */
    private fun readPluginState(t: TrackM, o: JSONObject) {
        val st = HashMap<String, String>(); o.optJSONObject("nstate")?.let { s -> s.keys().forEach { k -> st[k] = s.getString(k) } }
        if (st.isNotEmpty() || o.optString("preset").isNotEmpty()) pendingStates[t.id] = o.optString("preset") to st
        o.optJSONArray("lanes")?.let { la -> pendingLanes[t.id] = (0 until la.length()).map { k -> val lo = la.getJSONObject(k); val pa = lo.getJSONArray("points")
            lo.getString("param") to (0 until pa.length()).map { q -> pa.getJSONArray(q).let { x -> AutoPoint(x.getDouble(0).toFloat(), x.getDouble(1).toFloat(), x.optInt(2, 0)) } } } }
        o.optJSONArray("params")?.let { pa -> pendingParams[t.id] = (0 until pa.length()).map { k -> pa.getJSONArray(k).let { x -> x.getString(0) to x.getDouble(1).toFloat() } } }
    }
    private fun loadFx(owner: TrackM?, arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val path = o.optString("plugin").takeIf { it.isNotEmpty() && (PluginHost.isNative(it) || File(it).exists()) } ?: continue
            val fx = addEffectInternal(owner, o.optString("name", File(path).nameWithoutExtension), path) ?: continue
            if (o.optBoolean("bypass")) { fx.bypass = true; e.setFxBypass(fx.id, true) }
            readPluginState(fx, o)
        }
    }

    // ---------- projects (FL Studio style: File menu) ----------
    fun projects(): List<ProjectStore.Info> = store?.list() ?: emptyList()

    /** Saves, empties the workspace and makes [name] current; the project is then loaded (or created empty). */
    private fun switchProject(name: String, create: Boolean) {
        if (projectBusy) return
        projectBusy = true
        if (recording) stopRecording()
        e.pause(); playing = false
        save()
        closeCurrent()
        projectName = name; prefs.edit().putString("project", name).apply()
        viewModelScope.launch {
            // Track ids are only reused after the removed tracks' plugin hosts are gone.
            if (e.trackIdsUsed() > 100) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.delay(3500) }; e.resetTrackIds() }
            if (create) { store?.create(name); store?.file(name)?.let { write(it) } } else load()  // a new project exists on disk at once
            selected = tracks.firstOrNull(); dirty = false; projectBusy = false
        }
    }
    private fun closeCurrent() {
        tracks.toList().forEach { removeTrack(it) }
        masterFx.toList().forEach { removeEffect(null, it) }
        pluginTarget = null; panelExpanded = false
        editorSlots.clear()
        loop = false; ppbValue = 44f; arrangeX = 0; arrangeY = 0; rollX = 0; rollY = -1; viewRevision++
        bpm = 120f; loopBars = 4; masterGain = 1f; e.setMasterGain(1f); applyLoop()
        pendingLanes.clear(); pendingParams.clear(); pendingStates.clear()
        e.seek(0); playhead = 0f; selected = null; laneSelected = null; dirty = false
    }
    /** The name pre-filled when creating or saving a project (the user can type anything else). */
    fun suggestProjectName(): String = store?.nextProjectName() ?: "Project_1"
    /** Bumped for each new project: the screen then goes back to its starting layout (tab, on-screen keys, octave). */
    var newProjectRevision by mutableIntStateOf(0); private set
    fun newProject(name: String) { val s = store ?: return; newProjectRevision++; switchProject(s.uniqueName(name), create = true) }
    fun openProject(name: String) { if (name != projectName && store?.exists(name) == true) switchProject(name, create = false) }
    /** Re-reads the current project from disk (after restoring a backup). */
    private fun reloadCurrent() {
        if (projectBusy) return
        projectBusy = true; e.pause(); playing = false; closeCurrent()
        viewModelScope.launch { load(); selected = tracks.firstOrNull(); dirty = false; projectBusy = false }
    }

    /** Pointing every clip that lives in [from] at [to] (same file name), in memory. */
    private fun repoint(from: File, to: File) {
        val fp = from.absolutePath + "/"
        tracks.forEach { t -> t.clips.forEach { c -> if (c.path.startsWith(fp)) c.path = File(to, c.path.removePrefix(fp)).absolutePath } }
    }

    /** Save as: a new project folder with a copy of this project's samples and presets; it becomes the current project. */
    fun saveAs(name: String) {
        val s = store ?: return
        if (projectBusy) return
        val old = projectName; val n = s.uniqueName(name)
        save()
        s.create(n)
        runCatching { s.samples(old).copyRecursively(s.samples(n), overwrite = false); s.presets(old).copyRecursively(s.presets(n), overwrite = false) }
        val oldSamples = s.samples(old).absolutePath + "/"
        write(s.file(n)) { p -> if (p.startsWith(oldSamples)) File(s.samples(n), p.removePrefix(oldSamples)).absolutePath else p }
        repoint(s.samples(old), s.samples(n))
        projectName = n; prefs.edit().putString("project", n).apply(); dirty = false
        message = "Saved as \"$n\"."
    }
    /** FL Studio's "Save new version": Song -> Song_2 -> Song_3, keeping the earlier ones. */
    fun saveNewVersion() { val s = store ?: return; saveAs(s.nextVersionName(projectName)) }

    fun renameProject(name: String) {
        val s = store ?: return
        val n = s.sanitize(name)
        if (n.isEmpty() || n == projectName) return
        if (s.dir(n).exists()) { message = "A project named \"$n\" already exists."; return }
        save()
        val old = projectName
        val oldDir = s.dir(old)
        if (!oldDir.renameTo(s.dir(n))) { message = "Could not rename the project folder."; return }
        File(s.dir(n), "$old.zedok").renameTo(s.file(n))
        repoint(File(oldDir, "Samples"), s.samples(n))
        projectName = n; prefs.edit().putString("project", n).apply()
        save(); message = "Renamed to \"$n\"."
    }

    fun deleteProject(name: String) {
        val s = store ?: return
        if (name == projectName) { message = "Open another project first."; return }
        s.dir(name).deleteTree()
    }

    /** Copies every sample used by the project that lives elsewhere into its own Samples folder. Returns how many were copied. */
    fun collectSamples(): Int {
        val dir = projectSamplesDir() ?: return 0
        dir.mkdirs()
        var n = 0
        for (t in tracks) for (c in t.clips) {
            val f = File(c.path)
            if (!f.exists() || f.absolutePath.startsWith(dir.absolutePath + "/")) continue
            var out = File(dir, f.name); var k = 2
            while (out.exists()) out = File(dir, f.nameWithoutExtension + " ($k)." + f.extension).also { k++ }
            runCatching { f.copyTo(out); c.path = out.absolutePath; n++ }
        }
        if (n > 0) { save(); samplesVersion++ }
        message = if (n > 0) "Copied $n sample(s) into this project." else "Every sample is already in this project."
        return n
    }

    fun backups(): List<File> = store?.listBackups(projectName) ?: emptyList()
    /** Replaces the project with an earlier save. The current state is kept as a backup first. */
    fun restoreBackup(f: File) {
        val dest = projectFile ?: return
        store?.backup(projectName, minGapMs = 0)
        runCatching { f.copyTo(dest, overwrite = true) }.onFailure { message = "Could not restore: ${it.message}"; return }
        reloadCurrent(); message = "Restored ${f.name}."
    }

    // ---------- presets of the built-in plugins ----------
    val presetLibrary = PresetLibrary(app, workspace)
    fun nativeId(t: TrackM): String? = t.pluginPath?.takeIf { PluginHost.isNative(it) }?.removePrefix(PluginHost.NATIVE_PREFIX)
    /** Applies a preset: every parameter takes the preset's value, or returns to its default when the preset does not list it. */
    fun applyPreset(t: TrackM, p: Preset) {
        for (pm in loadParams(t)) {
            val target = p.values[pm.name] ?: pm.default
            e.setParam(t.id, pm.index, target)
            if (Math.abs(target - pm.default) > 1e-4f) t.paramOverrides[pm.index] = target else t.paramOverrides.remove(pm.index)
        }
        p.state.forEach { (k, x) -> e.setPluginState(t.id, k, x); t.states[k] = x }
        t.presetName = p.name; t.presetKey = p.key
        paramsRevision++; dirty = true
    }
    /** The parameters that differ from their defaults, by name (what a user preset stores). */
    fun changedValues(t: TrackM): Map<String, Float> = loadParams(t).filter { Math.abs(paramValue(t, it.index) - it.default) > 1e-4f }.associate { it.name to paramValue(t, it.index) }
    /** A/B comparison: switches between two snapshots of every parameter (the first switch keeps the current sound in the other slot). */
    fun toggleAB(t: TrackM) {
        val params = loadParams(t)
        t.abStore[t.abSlot] = params.associate { it.index to paramValue(t, it.index) }
        val next = 1 - t.abSlot
        val snap = t.abStore[next] ?: t.abStore[t.abSlot]!!
        for (pm in params) { val v = snap[pm.index] ?: pm.default; e.setParam(t.id, pm.index, v); if (Math.abs(v - pm.default) > 1e-4f) t.paramOverrides[pm.index] = v else t.paramOverrides.remove(pm.index) }
        t.abSlot = next; paramsRevision++; dirty = true
    }
    fun pluginState(t: TrackM, key: String) = e.getPluginState(t.id, key)
    fun setPluginState(t: TrackM, key: String, value: String) { e.setPluginState(t.id, key, value); t.states[key] = value; dirty = true }

    // ---------- plugin presets (per project) ----------
    private fun presetDir(t: TrackM): File? = projectPresetsDir()?.let { File(it, (t.pluginName.ifBlank { t.name }).replace(Regex("[^A-Za-z0-9 _.()+-]"), "_")) }
    fun presets(t: TrackM): List<File> = presetDir(t)?.listFiles { f -> f.isFile && f.extension == "zpreset" }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** Stores every parameter that differs from its default (the plugin's own state is not exposed to the host). */
    fun savePreset(t: TrackM, name: String): Boolean {
        val dir = presetDir(t) ?: return false
        val safe = name.trim().replace(Regex("[^A-Za-z0-9 _.()+-]"), "_").trim('.', ' ').take(60)
        if (safe.isEmpty()) { message = "Give the preset a name."; return false }
        val params = loadParams(t)
        val vals = JSONArray()
        for (p in params) { val v = paramValue(t, p.index); if (Math.abs(v - p.default) > 1e-4f) vals.put(JSONArray().put(p.index).put(v.toDouble())) }
        val o = JSONObject().put("format", 1).put("plugin", t.pluginName).put("params", params.size).put("values", vals)
        return try { dir.mkdirs(); File(dir, "$safe.zpreset").writeText(o.toString()); message = "Preset \"$safe\" saved in this project."; true } catch (ex: Exception) { message = "Could not save the preset: ${ex.message}"; false }
    }
    fun deletePreset(f: File) { f.delete() }
    /** Bumped when parameters change behind the sliders' back (a preset was loaded) so the parameter list re-reads them. */
    var paramsRevision by mutableIntStateOf(0); private set
    /** Applies a preset: listed parameters get their values, all others return to their defaults. Sent in small batches (the engine's queue is short). */
    fun loadPreset(t: TrackM, f: File) {
        val o = try { JSONObject(f.readText()) } catch (ex: Exception) { message = "Could not read the preset."; return }
        val params = loadParams(t)
        if (o.optInt("params", params.size) != params.size) { message = "This preset is for a different version of the plugin."; return }
        val stored = HashMap<Int, Float>(); o.getJSONArray("values").let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { x -> stored[x.getInt(0)] = x.getDouble(1).toFloat() } }
        viewModelScope.launch {
            var sent = 0
            for (p in params) {
                val target = stored[p.index] ?: p.default
                if (Math.abs(paramValue(t, p.index) - target) < 1e-4f) continue
                e.setParam(t.id, p.index, target)
                if (Math.abs(target - p.default) > 1e-4f) t.paramOverrides[p.index] = target else t.paramOverrides.remove(p.index)
                if (++sent % 64 == 0) kotlinx.coroutines.delay(25)
            }
            paramsRevision++; dirty = true; message = "Loaded preset \"${f.nameWithoutExtension}\"."
        }
    }

    private fun load() {
        val f = projectFile ?: return
        if (!f.exists()) return
        try {
            val root = JSONObject(f.readText())
            bpm = root.optDouble("bpm", 120.0).toFloat(); e.setTempo(bpm.toDouble()); masterGain = root.optDouble("master", 1.0).toFloat(); e.setMasterGain(masterGain)
            loopBars = root.optInt("loopBars", 4); loop = root.optBoolean("loop", false); applyLoop()
            root.optJSONObject("view")?.let { v ->
                ppbValue = v.optDouble("ppb", 44.0).toFloat().coerceIn(ZOOM_MIN, ZOOM_MAX)
                arrangeX = v.optInt("ax"); arrangeY = v.optInt("ay"); rollX = v.optInt("rx"); rollY = v.optInt("ry", -1)
            } ?: run { ppbValue = 44f; arrangeX = 0; arrangeY = 0; rollX = 0; rollY = -1 }
            viewRevision++
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
                readPluginState(t, o)
                loadFx(t, o.optJSONArray("fx"))
                val ca = o.getJSONArray("clips")
                for (k in 0 until ca.length()) { val c = ca.getJSONObject(k); val w = File(c.getString("path")); if (w.exists()) importClip(t, w, c.getDouble("start").toFloat()) }
            }
            loadFx(null, root.optJSONArray("masterFx"))
            selected = tracks.firstOrNull()
        } catch (ex: Exception) { message = "Could not open the saved project: ${ex.message}" }
        dirty = false
    }

    companion object { @Volatile var current: DawViewModel? = null; const val ZOOM_MIN = 4f; const val ZOOM_MAX = 400f }

    override fun onCleared() { current = null; stopRecording(); midi.stop(); save(); host.stopAll(); e.pause(); e.stop() }
}
