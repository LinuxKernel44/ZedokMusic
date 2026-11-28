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
    /** True when the open project was written by a newer app: it is shown empty and never saved, so the newer file is not overwritten. */
    private var formatLocked = false
    /** Project-wide undo / redo (see [History]). Declared before the init block because load() runs from the constructor. */
    private val hist = History()
    var historyRev by mutableIntStateOf(0); private set
    val undoLabel: String? get() { historyRev; return hist.undoName }
    val redoLabel: String? get() { historyRev; return hist.redoName }
    private fun record(name: String, key: String? = null, undo: () -> Unit, redo: () -> Unit) { hist.record(name, key, undo, redo); historyRev++ }
    fun undo() { val n = hist.undo() ?: return; historyRev++; dirty = true; message = "Undo: $n" }
    fun redo() { val n = hist.redo() ?: return; historyRev++; dirty = true; message = "Redo: $n" }
    private fun clearHistory() { hist.clear(); historyRev++ }

    // ---------- patterns and the playlist (declared before the init block: load() runs from the constructor) ----------
    val patterns = androidx.compose.runtime.mutableStateListOf<PatternM>()
    val playlist = androidx.compose.runtime.mutableStateListOf<PatternClipM>()
    /** The pattern the piano roll edits (its notes are the instrument tracks' [TrackM.notes]). */
    var currentPattern by mutableStateOf<PatternM?>(null); private set
    /** The placement picked in the playlist's pattern lane. */
    var selectedPatternClip by mutableStateOf<PatternClipM?>(null)
    /** The pitch the step sequencer uses for a melodic track (track id -> MIDI note; default C4). */
    val stepPitches = androidx.compose.runtime.mutableStateMapOf<Int, Int>()
    fun stepPitch(t: TrackM): Int = stepPitches[t.id] ?: 60
    fun setStepPitch(t: TrackM, p: Int) { stepPitches[t.id] = p.coerceIn(0, 127) }
    /** true = the playlist plays (Song); false = the current pattern alone, looped, and the audio clips are silent (Pattern mode, like FL Studio's). */
    var songMode by mutableStateOf(true); private set
    fun switchSongMode(on: Boolean) {
        if (songMode == on) return
        songMode = on; applyLoop(); syncAllNotes(); applyClipGains(); dirty = true
    }
    private fun applyClipGains() { for (t in tracks) for (c in t.clips) e.setClipGain(c.engineId, if (songMode) Math.pow(10.0, c.gainDb / 20.0).toFloat() else 0f) }
    /** Tempo changes after beat 0 (the tempo from the start is [bpm]) and the markers of the timeline; saved with the project. */
    val tempoChanges = androidx.compose.runtime.mutableStateListOf<TempoPoint>()
    val markers = androidx.compose.runtime.mutableStateListOf<MarkerM>()
    private var tm = TempoMap(DEFAULT_BPM)
    /** Time signature changes after beat 0 (4/4 at the start); they set the bar lines of the arrangement ruler and are saved with the project. */
    val sigChanges = androidx.compose.runtime.mutableStateListOf<SigPoint>()
    /** The marker whose rename dialog is open. */
    var renameMarkerDialog by mutableStateOf<MarkerM?>(null)
    private var patternCounter = 0
    private var lastPatLen = 0f
    private val projectFile get() = store?.file(projectName)
    fun projectDir(): File? = store?.dir(projectName)
    /** Samples that belong to this project: imports and recordings are stored here, not in a shared library. */
    fun projectSamplesDir(): File? = store?.samples(projectName)
    fun projectExportsDir(): File? = store?.exports(projectName)
    fun projectPresetsDir(): File? = store?.presets(projectName)

    val tracks = mutableStateListOf<TrackM>()
    /** Clips that overlap on a track crossfade (equal power) over the overlap. */
    var autoCrossfade by mutableStateOf(true)
    var bpm by mutableFloatStateOf(DEFAULT_BPM); private set
    var playing by mutableStateOf(false); private set
    /** The project's scale: root (0 = C ... 11 = B) and type (index in Theory.SCALES, 0 = none). Shown in the piano roll grid and saved with the project. */
    var scaleRoot by mutableIntStateOf(0); private set        // every project keeps its own scale; a new project starts in C minor
    var scaleType by mutableIntStateOf(2); private set
    fun setScale(root: Int, type: Int) {
        val or = scaleRoot; val ot = scaleType
        scaleRoot = root.coerceIn(0, 11); scaleType = type.coerceIn(0, Theory.SCALES.size - 1); dirty = true
        val nr = scaleRoot; val nt = scaleType
        if (or != nr || ot != nt) record("Change scale", undo = { scaleRoot = or; scaleType = ot }, redo = { scaleRoot = nr; scaleType = nt })
    }
    var loop by mutableStateOf(false); private set
    /** The timelines scroll along with the play cursor. */
    var followPlayhead: Boolean get() = AppSettings.follow.on; set(v) { AppSettings.follow.set(v) }
    var loopBars by mutableIntStateOf(4); private set
    var playhead by mutableFloatStateOf(0f); private set
    var selected by mutableStateOf<TrackM?>(null)
    var masterGain by mutableFloatStateOf(1f); private set
    val buses = mutableStateListOf<BusM>()   // declared up here: load() runs from the constructor
    var masterStereo by mutableFloatStateOf(0f); private set   // declared up here: load() runs from the constructor
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
    /** Notes held right now by a finger, a MIDI keyboard or a preview: track id * 128 + pitch -> how many. The keyboards light these keys. */
    private val liveHeld = androidx.compose.runtime.mutableStateMapOf<Int, Int>()
    /** Keys to light on a keyboard of [t]: played live (finger, MIDI, preview) or sounding from the notes of the track at the playhead. */
    fun activePitches(t: TrackM): Set<Int> {
        val out = HashSet<Int>()
        for ((k, n) in liveHeld) if (n > 0 && k / 128 == t.id) out.add(k % 128)
        if (playing) { val ph = playhead; for (n in t.notes) if (ph >= n.start && ph < n.start + n.length) out.add(n.pitch) }
        return out
    }
    fun keyEvent(t: TrackM, on: Boolean, pitch: Int, velocity: Int) {
        val hk = t.id * 128 + pitch.coerceIn(0, 127)
        if (on) liveHeld[hk] = (liveHeld[hk] ?: 0) + 1 else (liveHeld[hk] ?: 0).let { c -> if (c <= 1) liveHeld.remove(hk) else liveHeld[hk] = c - 1 }
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
                importClip(t, f, maxOf(0f, tm.beat(tm.frames(recStartBeat) - comp).toFloat()))
                if (r[1] > 0) message = "Recorded with ${r[1]} dropped frames (storage too slow)."
            } else message = "Nothing was recorded."
        }
        recLevel = 0f
    }

    /** Song beats -> engine frames (and back) through the tempo map. */
    private fun fr(beat: Float): Long = tm.frames(beat).toLong()
    /** How many beats an audio clip covers (its length is fixed in time, so it depends on the tempo where it plays). */
    fun clipBeats(c: ClipM): Float = tm.beatsFor(c.start, c.lengthFrames)
    /** Rebuilds the tempo map from [bpm] and [tempoChanges], gives it to the engine and, when [reflow], puts every clip, note, lane and the loop at the right frame again. */
    private fun rebuildTempo(reflow: Boolean) {
        val beat = playhead
        tm = TempoMap(bpm, tempoChanges.toList())
        e.setTempo(bpm.toDouble())
        e.setTempoMap(LongArray(tm.segments - 1) { tm.startFrame(it + 1) }, DoubleArray(tm.segments) { tm.bpmOf(it).toDouble() })
        if (!reflow) return
        tracks.forEach { syncNotes(it); syncLanes(it); it.clips.forEach { c -> e.moveClip(c.engineId, fr(c.start)) } }
        applyLoop(); e.seek(fr(beat)); dirty = true
    }
    fun addTempoChange(beat: Float, newBpm: Float) {
        val b = Math.round(beat * 4f) / 4f; if (b <= 0f) { changeBpm(newBpm); return }
        val old = tempoChanges.firstOrNull { it.beat == b }; val p = TempoPoint(b, newBpm.coerceIn(30f, 300f))
        val at = tempoChanges.indexOf(old)
        if (old != null) tempoChanges[at] = p else tempoChanges.add(p)
        rebuildTempo(true)
        record("Tempo change", undo = { if (old != null) tempoChanges[tempoChanges.indexOf(p)] = old else tempoChanges.remove(p); rebuildTempo(true) }, redo = { if (old != null) tempoChanges[tempoChanges.indexOf(old)] = p else tempoChanges.add(p); rebuildTempo(true) })
    }
    fun removeTempoChange(p: TempoPoint) {
        val at = tempoChanges.indexOf(p); if (at < 0) return
        tempoChanges.remove(p); rebuildTempo(true)
        record("Delete tempo change", undo = { tempoChanges.add(at.coerceAtMost(tempoChanges.size), p); rebuildTempo(true) }, redo = { tempoChanges.remove(p); rebuildTempo(true) })
    }
    fun addSigChange(beat: Float, num: Int, den: Int) {
        val b = Math.round(beat); if (b <= 0) return
        val old = sigChanges.firstOrNull { it.beat == b.toFloat() }; val p = SigPoint(b.toFloat(), num.coerceIn(1, 32), den)
        val at = sigChanges.indexOf(old)
        if (old != null) sigChanges[at] = p else sigChanges.add(p); dirty = true
        record("Time signature", undo = { if (old != null) sigChanges[sigChanges.indexOf(p)] = old else sigChanges.remove(p); dirty = true }, redo = { if (old != null) sigChanges[sigChanges.indexOf(old)] = p else sigChanges.add(p); dirty = true })
    }
    fun removeSigChange(p: SigPoint) {
        val at = sigChanges.indexOf(p); if (at < 0) return
        sigChanges.remove(p); dirty = true
        record("Delete time signature", undo = { sigChanges.add(at.coerceAtMost(sigChanges.size), p); dirty = true }, redo = { sigChanges.remove(p); dirty = true })
    }
    fun addMarker(beat: Float, name: String): MarkerM {
        val m = MarkerM(Math.round(beat * 4f) / 4f, name.ifBlank { "Marker ${markers.size + 1}" }.trim().take(40)); markers.add(m); dirty = true
        record("Add marker", undo = { markers.remove(m); dirty = true }, redo = { markers.add(m); dirty = true })
        return m
    }
    fun renameMarker(m: MarkerM, name: String) {
        val n = name.trim().take(40); val o = m.name; if (n.isEmpty() || n == o) return
        m.name = n; dirty = true; record("Rename marker", undo = { m.name = o; dirty = true }, redo = { m.name = n; dirty = true })
    }
    fun removeMarker(m: MarkerM) {
        val at = markers.indexOf(m); if (at < 0) return
        markers.remove(m); dirty = true
        record("Delete marker", undo = { markers.add(at.coerceAtMost(markers.size), m); dirty = true }, redo = { markers.remove(m); dirty = true })
    }
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
    /** The velocity lane under the piano roll is shown. */
    var velocityPane by mutableStateOf(true)
    /** What the lane under the piano roll shows: 0 velocity, 1 pan, 2 cutoff, 3 resonance, 4 fine pitch, 5 release. */
    var noteLane by mutableIntStateOf(0)
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
        android.os.Handler(android.os.Looper.getMainLooper()).post { scanStorage() }  // after construction: the scan's fields are initialised below this block
        current = this
        store?.create(projectName)
        e.configure(AppSettings.outputDevice.value, AppSettings.bufferBursts, AppSettings.exclusive.on)
        PianoPack.ensure(getApplication())
        e.setTempo(bpm.toDouble())
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
            playhead = tm.beat(e.position().toDouble()).toFloat()
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
    private var lastSeekFrame = -1L
    /** Moves the play cursor. The same place asked twice in a row (a finger resting on the ruler) is only sent once while playing: every seek restarts the audio block. */
    fun seekBeat(beat: Float) {
        val b = beat.coerceAtLeast(0f); val f = fr(b)
        if (playing && f == lastSeekFrame) return
        lastSeekFrame = f; e.seek(f); playhead = b
    }
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
    private fun applyLoop() {
        val p = currentPattern
        if (!songMode && p != null) e.setLoop(true, 0, fr(patternLength(p)))   // Pattern mode: the pattern loops on itself
        else e.setLoop(loop, 0, fr(loopBars * 4f))
    }
    fun changeBpm(v: Float) {
        val old = bpm; applyBpm(v); val now = bpm
        if (old != now) record("Change tempo", "tempo", undo = { applyBpm(old) }, redo = { applyBpm(now) })
    }
    private fun applyBpm(v: Float) {
        val old = bpm; bpm = v.coerceIn(30f, 300f)
        if (old == bpm) { e.setTempo(bpm.toDouble()); return }
        rebuildTempo(true)
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
    fun renameTrack(t: TrackM, name: String) {
        val n = name.trim().take(40); val o = t.name
        if (n.isNotEmpty() && n != o) { t.name = n; dirty = true; record("Rename track", undo = { t.name = o; dirty = true }, redo = { t.name = n; dirty = true }) }
    }
    fun removeTrack(t: TrackM) {
        clearHistory()   // removing a track cannot be undone yet, and older actions may refer to it
        t.fx.toList().forEach { removeEffect(t, it) }
        t.frozenFile?.delete()
        if (t.pluginPath != null) { closeEditor(t); host.stop(t.id) }
        e.removeTrack(t.id); tracks.remove(t); if (selected == t) selected = tracks.firstOrNull(); dirty = true
    }
    fun importClip(t: TrackM, wav: File, startBeat: Float = 0f): ClipM? {
        val r = e.loadClip(t.id, wav.absolutePath, fr(startBeat))
        if (r == null) { message = "Could not read ${wav.name} (WAV only)."; return null }
        val c = ClipM(r[0].toInt(), wav.absolutePath, wav.nameWithoutExtension, startBeat, r[1])
        t.clips.add(c); if (!songMode) e.setClipGain(c.engineId, 0f); dirty = true; return c
    }

    // ---------- clip selection (like the notes of the piano roll) ----------
    /** The selected clips and the Select tool of the arrangement. */
    val clipSelection = androidx.compose.runtime.mutableStateListOf<ClipM>()
    var clipSelectMode by mutableStateOf(false)
    /** While a selected clip is dragged: how far (in beats) every selected clip follows it. */
    var clipDragBeats by mutableFloatStateOf(0f)
    var clipDragging by mutableStateOf(false)
    fun trackOf(c: ClipM): TrackM? = tracks.firstOrNull { c in it.clips }
    fun liveSelection(): List<ClipM> = clipSelection.filter { c -> tracks.any { c in it.clips } }
    fun clearClipSelection() = clipSelection.clear()
    /** A copy of [c] on [t] at [start] (same processed file, same gain / pitch / speed settings). */
    private fun addClipLike(t: TrackM, c: ClipM, start: Float): ClipM? {
        val n = importClip(t, File(c.path), start.coerceAtLeast(0f)) ?: return null
        n.src = c.src; n.semis = c.semis; n.speed = c.speed; n.mode = c.mode; n.srcBpm = c.srcBpm
        setClipGainDb(n, c.gainDb); n.fadeInMs = c.fadeInMs; n.fadeOutMs = c.fadeOutMs; n.fadeInShape = c.fadeInShape; n.fadeOutShape = c.fadeOutShape; pushFade(n); return n
    }
    private fun pushFade(c: ClipM) { e.setClipFade(c.engineId, (c.fadeInMs * 48f).toLong(), (c.fadeOutMs * 48f).toLong(), c.fadeInShape, c.fadeOutShape); dirty = true }
    fun setAutoCrossfadeOn(on: Boolean) { autoCrossfade = on; e.setAutoCrossfade(on); dirty = true }
    /** Fades of every clip in [list] (one slider drag = one undo step). */
    fun setClipsFade(list: List<ClipM>, inMs: Float, outMs: Float, shapeIn: Int, shapeOut: Int) {
        class F(val i: Float, val o: Float, val si: Int, val so: Int)
        val olds = list.map { it to F(it.fadeInMs, it.fadeOutMs, it.fadeInShape, it.fadeOutShape) }
        val nw = F(inMs.coerceIn(0f, 5000f), outMs.coerceIn(0f, 5000f), shapeIn, shapeOut)
        fun put(c: ClipM, f: F) { c.fadeInMs = f.i; c.fadeOutMs = f.o; c.fadeInShape = f.si; c.fadeOutShape = f.so; pushFade(c) }
        for (c in list) put(c, nw)
        record("Clip fades", "cfade" + list.joinToString(",") { it.engineId.toString() }, undo = { olds.forEach { (c, f) -> put(live(c), f) } }, redo = { list.forEach { put(live(it), nw) } })
    }
    /** The clip that stands for [c] now: clip objects are replaced when a clip is edited or put back by an undo, and old history entries still name the old object. */
    private fun live(c: ClipM): ClipM { var x = c; while (true) x = x.replacedBy ?: return x }
    private fun reAdd(t: TrackM, c: ClipM) { val l = live(c); val n = addClipLike(t, l, l.start) ?: return; l.replacedBy = n }
    private fun drop(c: ClipM) { val l = live(c); trackOf(l)?.let { removeClip(it, l) }; clipSelection.remove(l) }
    private fun recordAdded(name: String, made: List<ClipM>) {
        val owned = made.mapNotNull { c -> trackOf(c)?.let { it to c } }
        if (owned.isEmpty()) return
        record(name, undo = { owned.forEach { (_, c) -> drop(c) } }, redo = { owned.forEach { (t, c) -> reAdd(t, c) } })
    }
    /** Imports several WAV files as clips of [t] (one undo step). */
    fun importClips(t: TrackM, files: List<Pair<File, Float>>, name: String = "Add clips"): List<ClipM> {
        val made = files.mapNotNull { (f, b) -> importClip(t, f, b) }
        recordAdded(name, made); return made
    }
    fun moveClips(list: List<ClipM>, delta: Float) {
        if (list.isEmpty()) return
        val olds = list.map { it to it.start }
        val d = maxOf(delta, -(list.minOf { it.start })); for (c in list) moveClip(c, c.start + d)
        val news = list.map { it to it.start }
        if (olds != news) record("Move clips", undo = { olds.forEach { (c, s) -> moveClip(live(c), s) } }, redo = { news.forEach { (c, s) -> moveClip(live(c), s) } })
    }
    fun removeClips(list: List<ClipM>, name: String = "Delete clips") {
        val owned = list.mapNotNull { c -> trackOf(c)?.let { it to c } }
        for ((t, c) in owned) removeClip(t, c)
        clipSelection.removeAll(list.toSet())
        if (owned.isNotEmpty()) record(name, undo = { owned.forEach { (t, c) -> reAdd(t, c) } }, redo = { owned.forEach { (_, c) -> drop(c) } })
    }
    /** Sets the gain of every clip of [list] (a slider drag is one undo step). */
    fun setClipsGain(list: List<ClipM>, db: Float) {
        val olds = list.map { it to it.gainDb }
        for (c in list) setClipGainDb(c, db)
        val news = list.map { it to it.gainDb }
        if (olds != news) record("Clip gain", "cgain" + list.joinToString(",") { it.engineId.toString() }, undo = { olds.forEach { (c, g) -> setClipGainDb(live(c), g) } }, redo = { news.forEach { (c, g) -> setClipGainDb(live(c), g) } })
    }
    /** Clone (Ctrl+B in FL Studio): copies the selection right after its end. Returns the copies, which become the selection. */
    fun cloneClips(sel: List<ClipM>): List<ClipM> {
        if (sel.isEmpty()) return emptyList()
        val span = sel.maxOf { it.start + clipBeats(it) } - sel.minOf { it.start }
        return sel.mapNotNull { c -> trackOf(c)?.let { addClipLike(it, c, c.start + span) } }.also { recordAdded("Clone clips", it) }
    }
    class ClipCopy(val track: TrackM, val src: ClipM, val rel: Float)
    var clipClip by mutableStateOf<List<ClipCopy>>(emptyList()); private set
    fun copyClips(sel: List<ClipM>) { if (sel.isEmpty()) return; val lo = sel.minOf { it.start }; clipClip = sel.mapNotNull { c -> trackOf(c)?.let { ClipCopy(it, c, c.start - lo) } } }
    fun cutClips(sel: List<ClipM>) { copyClips(sel); removeClips(sel, "Cut clips") }
    fun pasteClips(at: Float): List<ClipM> = clipClip.mapNotNull { cc -> (if (cc.track in tracks) cc.track else tracks.firstOrNull { it.kind == TrackKind.Audio })?.let { addClipLike(it, cc.src, at + cc.rel) } }.also { recordAdded("Paste clips", it) }
    /** Edits (pitch, speed) of the whole selection. */
    fun applyClipEdits(list: List<ClipM>, semis: Float, speed: Float, mode: Int, srcBpm: Float) { for (c in list) trackOf(c)?.let { applyClipEdit(it, c, semis, speed, mode, srcBpm) } }

    // ---------- clip editing: gain (live), pitch and speed (a processed copy of the sample, the original is kept) ----------
    private fun processedDir() = projectSamplesDir()?.let { File(it, ".processed") }
    private fun peaksDir() = projectDir()?.let { File(it, "Cache/Peaks") }
    /** The waveform of a clip: min / max per [AudioFx.PEAK_FRAMES] frames, cached in the project's Cache folder (computed once). */
    fun clipPeaks(c: ClipM): ByteArray? = AudioFx.peaks(File(c.path), peaksDir())
    fun setClipGainDb(c: ClipM, db: Float) { c.gainDb = db.coerceIn(-48f, 24f); e.setClipGain(c.engineId, Math.pow(10.0, c.gainDb / 20.0).toFloat()); dirty = true }

    /** Renders [c] with the pitch / speed / mode given (from its original file) and puts the result in its place. Runs in the background. */
    fun applyClipEdit(t: TrackM, c: ClipM, semis: Float, speed: Float, mode: Int, srcBpm: Float, recordIt: Boolean = true) {
        if (c.busy) return
        val oSemis = c.semis; val oSpeed = c.speed; val oMode = c.mode; val oBpm = c.srcBpm
        val sp = speed.coerceIn(0.25f, 4f); val se = semis.coerceIn(-48f, 48f)
        c.busy = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            var target: File? = File(c.src); var err: String? = null
            if (Math.abs(se) > 1e-3f || Math.abs(sp - 1f) > 1e-4f) {
                val dir = processedDir()
                if (dir == null) err = "There is no project folder."
                else {
                    val name = File(c.src).nameWithoutExtension.replace(Regex("[^A-Za-z0-9 _.()+-]"), "_").take(40)
                    val sf = File(c.src)
                    val tag = Integer.toHexString((sf.absolutePath + "|" + sf.length() + "|" + sf.lastModified()).hashCode())
                    val out = File(dir, "$name  %+.2f st  x%.5f  m$mode  $tag.wav".format(se, sp))
                    if (!out.exists() || out.length() < 44) {
                        val pcm = AudioFx.readWav(File(c.src))
                        if (pcm == null) err = "Could not read ${File(c.src).name}."
                        else {
                            dir.mkdirs(); val tmp = File(dir, out.name + ".tmp")
                            runCatching { AudioFx.writeWav(tmp, AudioFx.process(pcm, 0f, se, sp, mode)); if (!tmp.renameTo(out)) error("rename failed") }.onFailure { tmp.delete(); err = "Could not process the clip: ${it.message}" }
                        }
                    }
                    target = out
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                c.busy = false
                if (err != null) { message = err; return@withContext }
                val f = target ?: return@withContext
                val i = t.clips.indexOf(c); if (i < 0) return@withContext
                val r = e.loadClip(t.id, f.absolutePath, fr(c.start))
                if (r == null) { message = "Could not load the processed clip."; return@withContext }
                e.removeClip(c.engineId)
                val n = ClipM(r[0].toInt(), f.absolutePath, c.name, c.start, r[1])
                n.src = c.src; n.gainDb = c.gainDb; n.fadeInMs = c.fadeInMs; n.fadeOutMs = c.fadeOutMs; n.fadeInShape = c.fadeInShape; n.fadeOutShape = c.fadeOutShape; n.semis = se; n.speed = sp; n.mode = mode; n.srcBpm = srcBpm
                e.setClipGain(n.engineId, Math.pow(10.0, n.gainDb / 20.0).toFloat()); pushFade(n)
                t.clips[i] = n; dirty = true; c.replacedBy = n
                if (recordIt) record("Edit clip", undo = { applyClipEdit(t, live(n), oSemis, oSpeed, oMode, oBpm, false) }, redo = { applyClipEdit(t, live(n), se, sp, mode, srcBpm, false) })
                val si = clipSelection.indexOf(c); if (si >= 0) clipSelection[si] = n
                if (clipClip.any { it.src === c }) clipClip = clipClip.map { if (it.src === c) ClipCopy(it.track, n, it.rel) else it }
            }
        }
    }
    fun moveClip(c: ClipM, startBeat: Float) {
        c.start = startBeat.coerceAtLeast(0f); e.moveClip(c.engineId, fr(c.start)); dirty = true
    }
    fun removeClip(t: TrackM, c: ClipM) { e.removeClip(c.engineId); t.clips.remove(c); dirty = true }

    private fun applyGain(t: TrackM, g: Float) { t.gain = g; e.setTrackGain(t.id, g); dirty = true }
    fun setGain(t: TrackM, g: Float) { val o = t.gain; applyGain(t, g); if (o != g) record("Track volume", "gain${t.id}", undo = { applyGain(t, o) }, redo = { applyGain(t, g) }) }
    private fun applyPan(t: TrackM, p: Float) { t.pan = p; e.setTrackPan(t.id, p); dirty = true }
    fun setPan(t: TrackM, p: Float) { val o = t.pan; applyPan(t, p); if (o != p) record("Pan", "pan${t.id}", undo = { applyPan(t, o) }, redo = { applyPan(t, p) }) }
    /** The wheel value (-1 wide .. +1 mono) as the width factor of the engine (2 .. 0). */
    private fun widthOf(s: Float) = 1f - s
    private fun putStereo(t: TrackM, s: Float) { t.stereo = s.coerceIn(-1f, 1f); e.setTrackWidth(t.id, widthOf(t.stereo)); dirty = true }
    fun setStereo(t: TrackM, s: Float) { val o = t.stereo; putStereo(t, s); val n = t.stereo; if (o != n) record("Stereo width", "stereo${t.id}", undo = { putStereo(t, o) }, redo = { putStereo(t, n) }) }
    fun readLoudness(): FloatArray = e.loudness()
    fun resetLoudness() = e.resetLoudness()
    fun readSpectrum(bands: Int): FloatArray = e.spectrum(bands)

    // ---- freeze: render a track (instrument, notes, clips and effects, with a neutral fader) to a file and play that instead ----
    var freezing by mutableStateOf<TrackM?>(null); private set
    private fun freezeDir() = projectDir()?.let { File(it, "Cache/Freeze") }
    private fun applyFrozen(t: TrackM, f: File): Boolean {
        val r = e.loadClip(t.id, f.absolutePath, 0L) ?: return false
        e.setTrackFrozen(t.id, true); e.setClipFrozen(r[0].toInt(), true)   // the track first: until then the new clip stays silent
        t.frozenFile = f; t.frozenClip = r[0].toInt(); t.frozen = true; return true
    }
    fun freezeTrack(t: TrackM) {
        if (t.frozen || freezing != null || exporting || recording) return
        if (t.kind == TrackKind.Audio && t.clips.isEmpty()) { message = "${t.name} has nothing to freeze."; return }
        val dir = freezeDir() ?: run { message = "No project folder."; return }
        val to = fr(songBeats()) + 2 * 48000L
        e.pause(); playing = false; clearHistory(); dir.mkdirs()
        freezing = t; message = "Freezing ${t.name}…"
        Thread {
            val f = File(dir, t.name.replace(Regex("[^A-Za-z0-9 _.-]"), "_") + "_${t.id}_${System.currentTimeMillis()}.wav")
            val ok = try { e.renderEncoded(f.absolutePath, 0L, to, 2, -t.id) } catch (ex: Throwable) { false }
            if (ok && t in tracks && applyFrozen(t, f)) { dirty = true; message = "${t.name} frozen." } else { f.delete(); message = "Could not freeze ${t.name}." }
            freezing = null
        }.start()
    }
    fun unfreezeTrack(t: TrackM) {
        if (!t.frozen) return
        clearHistory()
        e.setTrackFrozen(t.id, false); e.removeClip(t.frozenClip)
        t.frozenFile?.delete(); t.frozenFile = null; t.frozenClip = 0; t.frozen = false; dirty = true; message = "${t.name} unfrozen."
    }

    // ---- mixer routing: buses, track / bus outputs, sends ----
    fun busById(id: Int) = buses.firstOrNull { it.id == id }
    fun addBus(): BusM? {
        val id = e.addBus(); if (id == 0) { message = "No more buses (the limit is 16)."; return null }
        clearHistory()   // removing a bus is not undoable, and older actions may name its id
        val b = BusM(id, "Bus ${buses.size + 1}", TrackColors[id % TrackColors.size]); buses.add(b); dirty = true; return b
    }
    fun removeBus(b: BusM) {
        clearHistory(); b.fx.toList().forEach { removeEffect(null, it, b) }; e.removeBus(b.id); buses.remove(b)
        for (t in tracks) { if (t.output == b.id) t.output = 0; t.sends.removeAll { it.bus == b.id } }
        for (o in buses) if (o.output == b.id) o.output = 0
        dirty = true
    }
    private fun putBusGain(b: BusM, g: Float) { b.gain = g.coerceIn(0f, 4f); e.setBusGain(b.id, b.gain); dirty = true }
    fun setBusGain(b: BusM, g: Float) { val o = b.gain; putBusGain(b, g); val n = b.gain; if (o != n) record("Bus volume", "busgain${b.id}", undo = { putBusGain(b, o) }, redo = { putBusGain(b, n) }) }
    private fun putBusPan(b: BusM, v: Float) { b.pan = v.coerceIn(-1f, 1f); e.setBusPan(b.id, b.pan); dirty = true }
    fun setBusPan(b: BusM, v: Float) { val o = b.pan; putBusPan(b, v); val n = b.pan; if (o != n) record("Bus pan", "buspan${b.id}", undo = { putBusPan(b, o) }, redo = { putBusPan(b, n) }) }
    private fun putBusStereo(b: BusM, s: Float) { b.stereo = s.coerceIn(-1f, 1f); e.setBusWidth(b.id, widthOf(b.stereo)); dirty = true }
    fun setBusStereo(b: BusM, s: Float) { val o = b.stereo; putBusStereo(b, s); val n = b.stereo; if (o != n) record("Bus stereo width", "busstereo${b.id}", undo = { putBusStereo(b, o) }, redo = { putBusStereo(b, n) }) }
    private fun putBusMute(b: BusM, m: Boolean) { b.mute = m; e.setBusMute(b.id, m); dirty = true }
    fun setBusMute(b: BusM, m: Boolean) { val o = b.mute; if (o == m) return; putBusMute(b, m); record(if (m) "Mute bus" else "Unmute bus", "busmute${b.id}", undo = { putBusMute(b, o) }, redo = { putBusMute(b, m) }) }
    fun renameBus(b: BusM, name: String) { b.name = name.trim().ifEmpty { b.name }; dirty = true }
    private fun putTrackOutput(t: TrackM, bus: Int): Boolean { if (!e.setTrackOutput(t.id, bus)) return false; t.output = bus; dirty = true; return true }
    fun setTrackOutput(t: TrackM, bus: Int) { val o = t.output; if (o == bus) return; if (putTrackOutput(t, bus)) record("Track output", "out${t.id}", undo = { putTrackOutput(t, o) }, redo = { putTrackOutput(t, bus) }) }
    private fun putBusOutput(b: BusM, dest: Int): Boolean { if (!e.setBusOutput(b.id, dest)) return false; b.output = dest; dirty = true; return true }
    fun setBusOutput(b: BusM, dest: Int) {
        val o = b.output; if (o == dest) return
        if (!putBusOutput(b, dest)) { message = "That would send the bus back into itself (a routing loop)."; return }
        record("Bus output", "busout${b.id}", undo = { putBusOutput(b, o) }, redo = { putBusOutput(b, dest) })
    }
    /** Buses that [b] could be routed into without a loop. */
    fun busTargets(b: BusM): List<BusM> = buses.filter { x -> var at = x.id; var ok = true; var hops = 0; while (at != 0 && hops++ <= 16) { if (at == b.id) { ok = false; break }; at = busById(at)?.output ?: 0 }; ok }
    private fun putSend(t: TrackM, bus: Int, gain: Float, pre: Boolean): Boolean {
        if (!e.setSend(t.id, bus, gain, pre)) return false
        val s = t.sends.firstOrNull { it.bus == bus }
        if (s == null) t.sends.add(SendM(bus, gain, pre)) else { s.gain = gain; s.pre = pre }
        dirty = true; return true
    }
    private fun dropSend(t: TrackM, bus: Int) { e.removeSend(t.id, bus); t.sends.removeAll { it.bus == bus }; dirty = true }
    fun setSend(t: TrackM, bus: Int, gain: Float, pre: Boolean? = null) {
        val old = t.sends.firstOrNull { it.bus == bus }; val og = old?.gain; val op = old?.pre ?: false
        val np = pre ?: op; val ng = gain.coerceIn(0f, 2f)
        if (!putSend(t, bus, ng, np)) { message = "A track can have at most 4 sends."; return }
        record("Send level", "send${t.id}_$bus", undo = { if (og == null) dropSend(t, bus) else putSend(t, bus, og, op) }, redo = { putSend(t, bus, ng, np) })
    }
    fun removeSend(t: TrackM, bus: Int) {
        val old = t.sends.firstOrNull { it.bus == bus } ?: return; val og = old.gain; val op = old.pre
        dropSend(t, bus)
        record("Remove send", "rmsend${t.id}_$bus", undo = { putSend(t, bus, og, op) }, redo = { dropSend(t, bus) })
    }

    private fun putMasterStereo(s: Float) { masterStereo = s.coerceIn(-1f, 1f); e.setMasterWidth(widthOf(masterStereo)); dirty = true }
    fun applyMasterStereo(s: Float) { val o = masterStereo; putMasterStereo(s); val n = masterStereo; if (o != n) record("Master stereo width", "mstereo", undo = { putMasterStereo(o) }, redo = { putMasterStereo(n) }) }
    private fun putMute(t: TrackM, m: Boolean) { t.mute = m; e.setTrackMute(t.id, m); dirty = true }
    fun setMute(t: TrackM, m: Boolean) { val o = t.mute; putMute(t, m); if (o != m) record(if (m) "Mute" else "Unmute", undo = { putMute(t, o) }, redo = { putMute(t, m) }) }
    private fun putSolo(t: TrackM, s: Boolean) { t.solo = s; e.setTrackSolo(t.id, s); dirty = true }
    fun setSolo(t: TrackM, s: Boolean) { val o = t.solo; putSolo(t, s); if (o != s) record(if (s) "Solo" else "Unsolo", undo = { putSolo(t, o) }, redo = { putSolo(t, s) }) }
    private fun putMaster(g: Float) { masterGain = g; e.setMasterGain(g); dirty = true }
    fun setMaster(g: Float) { val o = masterGain; putMaster(g); if (o != g) record("Master volume", "master", undo = { putMaster(o) }, redo = { putMaster(g) }) }

    // ---------- insert effects ----------
    /** Every plugin of the project: instruments, then each track's effects, then the master's. */
    fun allPlugins(): List<TrackM> = tracks.flatMap { t -> (if (t.pluginPath != null) listOf(t) else emptyList()) + t.fx } + buses.flatMap { it.fx } + masterFx
    fun fxOf(owner: TrackM?, bus: BusM? = null): MutableList<TrackM> = bus?.fx ?: owner?.fx ?: masterFx
    /** The plugin the Plugin and Auto panels work on. */
    fun panelPlugin(): TrackM? {
        val tg = pluginTarget; val sel = selected
        if (tg != null && (tg === sel || sel?.fx?.contains(tg) == true || masterFx.contains(tg) || buses.any { it.fx.contains(tg) })) return tg
        return sel?.takeIf { it.kind == TrackKind.Instrument } ?: sel?.fx?.firstOrNull() ?: tracks.firstOrNull { it.kind == TrackKind.Instrument } ?: masterFx.firstOrNull()
    }

    /** Adds [plugin] at the end of the chain of [owner] (null = the master) and starts its Wine host. */
    fun addEffect(owner: TrackM?, plugin: PluginInfo, bus: BusM? = null): TrackM? = addEffectInternal(owner, plugin.name, plugin.path, bus).also { if (it != null) { pluginTarget = it; dirty = true } }
    private fun addEffectInternal(owner: TrackM?, name: String, path: String, bus: BusM? = null): TrackM? {
        val list = fxOf(owner, bus)
        if (list.size >= 10) { message = "At most 10 effects per mixer track."; return null }
        val id = e.addFx(if (bus != null) -bus.id else owner?.id ?: 0, list.size)
        if (id == 0) { message = "Could not add the effect."; return null }
        val t = TrackM(id, name, TrackKind.Effect, bus?.color ?: owner?.color ?: Color(0xFFE6E8EE), path)
        list.add(t)
        t.pluginState = 1
        if (!host.launch(id, path)) { t.pluginState = 3; message = "Could not start the plugin host." }
        return t
    }
    fun removeEffect(owner: TrackM?, fx: TrackM, bus: BusM? = null) {
        closeEditor(fx); host.stop(fx.id)
        e.removeFx(fx.id); fxOf(owner, bus).remove(fx)
        if (pluginTarget === fx) pluginTarget = null
        dirty = true
    }
    /** Moves an effect one place earlier (delta -1) or later (+1) in its chain. */
    fun moveEffect(owner: TrackM?, fx: TrackM, delta: Int, bus: BusM? = null) {
        val list = fxOf(owner, bus); val i = list.indexOf(fx); val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        if (e.moveFx(fx.id, j)) { list.removeAt(i); list.add(j, fx); dirty = true }
    }
    fun setBypass(fx: TrackM, bypass: Boolean) { fx.bypass = bypass; e.setFxBypass(fx.id, bypass); dirty = true }

    // ---------- notes ----------
    // Every edit of the notes of a track first stores the notes as they were (undo / redo, 50 steps per track).
    /** Records the notes of [t] as they are now; call it just before changing them. What the notes become is read back when the action is undone. */
    private fun snapshot(t: TrackM, name: String = "Edit notes") {
        val before = t.notes.toList(); var after: List<NoteM> = before; val pat = currentPattern
        record(name, undo = { pat?.let { ensurePattern(it) }; after = t.notes.toList(); restoreNotes(t, before) }, redo = { pat?.let { ensurePattern(it) }; restoreNotes(t, after) })
    }
    private fun restoreNotes(t: TrackM, notes: List<NoteM>) { if (e.isPlaying()) e.panic(t.id); t.notes.clear(); t.notes.addAll(notes); syncNotes(t); dirty = true }

    fun addNote(t: TrackM, pitch: Int, start: Float, length: Float, velocity: Int = 100) { snapshot(t, "Add note"); t.notes.add(NoteM(pitch, start, length, velocity)); syncNotes(t); dirty = true }
    /** A note that is sounding right now loses its Note Off when it is deleted or changed: release the track's notes so it cannot hang. */
    private fun releaseIfSounding(t: TrackM, n: NoteM) { if (e.isPlaying() && playhead >= n.start && playhead <= n.start + n.length) e.panic(t.id) }
    fun removeNote(t: TrackM, n: NoteM) { snapshot(t, "Delete note"); releaseIfSounding(t, n); t.notes.remove(n); syncNotes(t); dirty = true }
    fun replaceNote(t: TrackM, old: NoteM, new: NoteM) { val i = t.notes.indexOf(old); if (i >= 0) { snapshot(t); releaseIfSounding(t, old); t.notes[i] = new; syncNotes(t); dirty = true } }

    // ---- several notes at once (one undo step each)
    /** Replaces every note of [changes] (old -> new) at once. */
    fun replaceNotes(t: TrackM, changes: Map<NoteM, NoteM>, name: String = "Edit notes") {
        if (changes.isEmpty()) return
        snapshot(t, name); if (e.isPlaying()) e.panic(t.id)
        for ((o, n) in changes) { val i = t.notes.indexOf(o); if (i >= 0) t.notes[i] = n }
        syncNotes(t); dirty = true
    }
    fun removeNotes(t: TrackM, list: List<NoteM>) { if (list.isEmpty()) return; snapshot(t, "Delete notes"); if (e.isPlaying()) e.panic(t.id); t.notes.removeAll(list.toSet()); syncNotes(t); dirty = true }
    /** Removes [remove] and adds [add] in one undo step (the piano roll tools). */
    fun editNotes(t: TrackM, remove: Collection<NoteM>, add: List<NoteM>, name: String) {
        if (remove.isEmpty() && add.isEmpty()) return
        snapshot(t, name); if (e.isPlaying()) e.panic(t.id)
        t.notes.removeAll(remove.toSet()); t.notes.addAll(add); syncNotes(t); dirty = true
    }
    /** The piano roll shows the notes of the other instrument tracks faintly behind its own. */
    var ghostNotes by mutableStateOf(false)
    fun addNotes(t: TrackM, list: List<NoteM>) { if (list.isEmpty()) return; snapshot(t, "Add notes"); t.notes.addAll(list); syncNotes(t); dirty = true }
    /** Clone (Ctrl+B in FL Studio): copies [sel] right after its end, shifted by the length of the whole selection. Returns the copies. */
    fun cloneNotes(t: TrackM, sel: List<NoteM>): List<NoteM> {
        if (sel.isEmpty()) return emptyList()
        val span = sel.maxOf { it.start + it.length } - sel.minOf { it.start }
        val copies = sel.map { it.copy(start = it.start + span) }
        addNotes(t, copies); return copies
    }
    /** The notes last copied or cut (any track); Paste puts them back. */
    var noteClip by mutableStateOf<List<NoteM>>(emptyList()); private set
    fun copyNotes(sel: List<NoteM>) { if (sel.isNotEmpty()) noteClip = sel.toList() }
    fun cutNotes(t: TrackM, sel: List<NoteM>) { copyNotes(sel); removeNotes(t, sel) }
    /** Pastes the clipboard so that its first note starts at [at] (beats); pitches are kept. Returns the new notes. */
    fun pasteNotes(t: TrackM, at: Float): List<NoteM> {
        val clip = noteClip; if (clip.isEmpty()) return emptyList()
        val lo = clip.minOf { it.start }
        val copies = clip.map { it.copy(start = (it.start - lo + at).coerceAtLeast(0f)) }
        addNotes(t, copies); return copies
    }
    /** Sends the notes the playlist puts on [t]'s timeline to the engine (and those of the other tracks when the current pattern changed length). */
    private fun syncNotes(t: TrackM) {
        pushNotes(t)
        val len = currentPattern?.let { patternLength(it) } ?: 0f
        if (len != lastPatLen) { lastPatLen = len; for (o in tracks) if (o !== t && o.kind == TrackKind.Instrument) pushNotes(o); if (!songMode) applyLoop() }
    }
    private fun syncAllNotes() { lastPatLen = currentPattern?.let { patternLength(it) } ?: 0f; for (t in tracks) if (t.kind == TrackKind.Instrument) pushNotes(t) }
    private fun pushNotes(t: TrackM) {
        val list = derivedNotes(t)
        val d = LongArray(list.size * 10)   // per note: start, length, pitch, velocity, pan, cutoff, res, fine, release, slide
        list.forEachIndexed { i, n ->
            val o = i * 10
            val f0 = fr(n.start); d[o] = f0; d[o + 1] = maxOf(1L, fr(n.start + n.length) - f0); d[o + 2] = n.pitch.toLong(); d[o + 3] = n.velocity.toLong()
            d[o + 4] = n.pan.toLong(); d[o + 5] = n.cutoff.toLong(); d[o + 6] = n.res.toLong(); d[o + 7] = n.fine.toLong(); d[o + 8] = n.release.toLong(); d[o + 9] = if (n.slide) 1L else 0L
        }
        e.setNotes(t.id, d)
    }

    // ---------- patterns ----------
    /** The notes of pattern [p] on track [t]: the live list for the pattern being edited, the stored one for the others. */
    fun notesOf(p: PatternM, t: TrackM): List<NoteM> = if (p === currentPattern) t.notes.toList() else p.stored[t.id] ?: emptyList()
    fun patternLength(p: PatternM): Float = PatternMath.length(tracks.flatMap { notesOf(p, it) }, p.fixedLength)
    fun clipLength(c: PatternClipM): Float = c.length ?: patternLength(c.pattern)
    /** What the arrangement draws on a track's row: the notes of every placement on the timeline. */
    fun notesOnTimeline(t: TrackM): List<NoteM> = derivedNotes(t)
    private fun derivedNotes(t: TrackM): List<NoteM> {
        if (t.kind != TrackKind.Instrument) return emptyList()
        if (!songMode) { val p = currentPattern ?: return emptyList(); val pl = patternLength(p); return PatternMath.place(notesOf(p, t), pl, 0f, pl) }   // Pattern mode
        val out = ArrayList<NoteM>()
        for (c in playlist) out += PatternMath.place(notesOf(c.pattern, t), patternLength(c.pattern), c.start, clipLength(c))
        return out
    }
    /** End of the song in beats: the last pattern clip or audio clip, whole bars, at least one bar. */
    fun songBeats(): Float {
        var end = 0f
        for (c in playlist) end = maxOf(end, c.start + clipLength(c))
        for (t in tracks) for (c in t.clips) end = maxOf(end, c.start + clipBeats(c))
        return maxOf(4f, kotlin.math.ceil(end / 4f) * 4f)
    }
    /** The position of the playhead inside the current pattern (while a placement of it plays), else -1. */
    fun patternPlayhead(): Float {
        val cur = currentPattern ?: return -1f
        if (!songMode) return playhead % patternLength(cur)   // Pattern mode: the pattern plays on its own, from 0
        val c = playlist.firstOrNull { it.pattern === cur && playhead >= it.start && playhead < it.start + clipLength(it) } ?: return -1f
        return (playhead - c.start) % patternLength(cur)
    }
    /** Seeks to [beat] of the current pattern, in the placement the playhead is in, or in the first placement. */
    fun seekPatternBeat(beat: Float) {
        val cur = currentPattern
        if (!songMode) { seekBeat(beat); return }
        val c = playlist.firstOrNull { it.pattern === cur && playhead >= it.start && playhead < it.start + clipLength(it) } ?: playlist.filter { it.pattern === cur }.minByOrNull { it.start }
        seekBeat((c?.start ?: 0f) + beat)
    }
    /** Makes [p] the pattern the piano roll edits: every instrument track's notes list becomes the notes of that pattern. */
    fun selectPattern(p: PatternM) {
        val cur = currentPattern; if (cur === p) return
        if (cur != null) for (t in tracks) if (t.kind == TrackKind.Instrument) cur.stored[t.id] = t.notes.toList()
        for (t in tracks) if (t.kind == TrackKind.Instrument) { t.notes.clear(); t.notes.addAll(p.stored[t.id] ?: emptyList()); p.stored.remove(t.id) }
        currentPattern = p; lastPatLen = patternLength(p)
        if (!songMode) { syncAllNotes(); applyLoop() }
    }
    private fun ensurePattern(p: PatternM) { if (currentPattern !== p && p in patterns) selectPattern(p) }
    private fun resetPatterns() {
        patterns.clear(); playlist.clear(); patternCounter = 0; selectedPatternClip = null
        val p = PatternM(++patternCounter, "Pattern 1", TrackColors[0]); patterns.add(p); currentPattern = p
        playlist.add(PatternClipM(p, 0f, null, 0)); lastPatLen = 0f
    }
    fun newPattern(): PatternM {
        val id = ++patternCounter; val p = PatternM(id, "Pattern $id", TrackColors[(id - 1) % TrackColors.size])
        val prev = currentPattern
        patterns.add(p); selectPattern(p); dirty = true
        record("New pattern", undo = { prev?.let { ensurePattern(it) }; patterns.remove(p) }, redo = { patterns.add(p); selectPattern(p) })
        return p
    }
    fun duplicatePattern(src: PatternM): PatternM {
        val id = ++patternCounter; val p = PatternM(id, src.name + " copy", src.color); p.fixedLength = src.fixedLength
        for (t in tracks) if (t.kind == TrackKind.Instrument) p.stored[t.id] = notesOf(src, t)
        val prev = currentPattern
        patterns.add(p); selectPattern(p); dirty = true
        record("Duplicate pattern", undo = { prev?.let { ensurePattern(it) }; patterns.remove(p) }, redo = { patterns.add(p); selectPattern(p) })
        return p
    }
    fun renamePattern(p: PatternM, name: String) {
        val n = name.trim().take(40); val o = p.name; if (n.isEmpty() || n == o) return
        p.name = n; dirty = true; record("Rename pattern", undo = { p.name = o; dirty = true }, redo = { p.name = n; dirty = true })
    }
    fun setPatternLength(p: PatternM, beats: Float?) {
        val o = p.fixedLength; p.fixedLength = beats; syncAllNotes(); applyLoop(); dirty = true
        record("Pattern length", undo = { p.fixedLength = o; syncAllNotes(); applyLoop(); dirty = true }, redo = { p.fixedLength = beats; syncAllNotes(); applyLoop(); dirty = true })
    }
    fun deletePattern(p: PatternM): Boolean {
        if (patterns.size <= 1 || p !in patterns) { message = "A project keeps at least one pattern."; return false }
        val index = patterns.indexOf(p); val gone = playlist.filter { it.pattern === p }; val goneAt = gone.map { playlist.indexOf(it) }
        val wasCurrent = currentPattern === p
        if (wasCurrent) { for (t in tracks) if (t.kind == TrackKind.Instrument) p.stored[t.id] = t.notes.toList(); selectPattern(patterns.first { it !== p }) }   // its notes stay in `stored` so an undo brings them back
        playlist.removeAll(gone.toSet()); patterns.remove(p); syncAllNotes(); dirty = true
        record("Delete pattern", undo = { patterns.add(index.coerceAtMost(patterns.size), p); gone.forEachIndexed { k, c -> playlist.add(goneAt[k].coerceIn(0, playlist.size), c) }; if (wasCurrent) selectPattern(p); syncAllNotes(); dirty = true },
            redo = { if (currentPattern === p) selectPattern(patterns.first { it !== p }); playlist.removeAll(gone.toSet()); patterns.remove(p); syncAllNotes(); dirty = true })
        return true
    }

    // ---------- the playlist ----------
    fun addPatternClip(p: PatternM, start: Float, row: Int, length: Float? = null): PatternClipM {
        val c = PatternClipM(p, start.coerceAtLeast(0f), length, row.coerceAtLeast(0)); playlist.add(c); syncAllNotes(); dirty = true
        record("Place pattern", undo = { playlist.remove(c); syncAllNotes(); dirty = true }, redo = { playlist.add(c); syncAllNotes(); dirty = true })
        return c
    }
    fun movePatternClip(c: PatternClipM, start: Float, row: Int) {
        val os = c.start; val or = c.row; val ns = start.coerceAtLeast(0f); val nr = row.coerceAtLeast(0)
        if (os == ns && or == nr) return
        c.start = ns; c.row = nr; syncAllNotes(); dirty = true
        record("Move pattern clip", undo = { c.start = os; c.row = or; syncAllNotes(); dirty = true }, redo = { c.start = ns; c.row = nr; syncAllNotes(); dirty = true })
    }
    /** [length] null = back to the pattern's own length. */
    fun resizePatternClip(c: PatternClipM, length: Float?) {
        val o = c.length; val n = length?.coerceAtLeast(0.25f); if (o == n) return
        c.length = n; syncAllNotes(); dirty = true
        record("Resize pattern clip", undo = { c.length = o; syncAllNotes(); dirty = true }, redo = { c.length = n; syncAllNotes(); dirty = true })
    }
    fun removePatternClip(c: PatternClipM) {
        val at = playlist.indexOf(c); if (at < 0) return
        playlist.remove(c); syncAllNotes(); dirty = true
        record("Delete pattern clip", undo = { playlist.add(at.coerceAtMost(playlist.size), c); syncAllNotes(); dirty = true }, redo = { playlist.remove(c); syncAllNotes(); dirty = true })
    }
    /** Gives the clip its own copy of the pattern, so editing it no longer changes the other placements. Returns the copy. */
    fun makeClipUnique(c: PatternClipM): PatternM? {
        val at = playlist.indexOf(c); if (at < 0) return null
        val src = c.pattern; val id = ++patternCounter; val p = PatternM(id, src.name + " (unique)", src.color); p.fixedLength = src.fixedLength
        for (t in tracks) if (t.kind == TrackKind.Instrument) p.stored[t.id] = notesOf(src, t)
        val nc = PatternClipM(p, c.start, c.length, c.row)
        patterns.add(p); playlist[at] = nc; syncAllNotes(); dirty = true
        record("Make unique", undo = { playlist[playlist.indexOf(nc).coerceAtLeast(0)] = c; patterns.remove(p); syncAllNotes(); dirty = true }, redo = { patterns.add(p); playlist[playlist.indexOf(c).coerceAtLeast(0)] = nc; syncAllNotes(); dirty = true })
        return p
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
    /** The value a control goes back to on a double tap: the one of the loaded preset (which is not always the default), or the default in Init. */
    fun resetTarget(t: TrackM, p: ParamM): Float {
        val preset = nativeId(t)?.takeIf { t.presetKey.isNotBlank() }?.let { id -> presetLibrary.all(id).firstOrNull { it.key == t.presetKey } }
        return preset?.values?.get(p.name) ?: p.default
    }
    fun resetParam(t: TrackM, p: ParamM): Float {
        val v = resetTarget(t, p)
        e.setParam(t.id, p.index, v)
        if (Math.abs(v - p.default) > 1e-4f) t.paramOverrides[p.index] = v else t.paramOverrides.remove(p.index)
        dirty = true; return v
    }
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
        t.lanes.add(l); syncLanes(t); dirty = true
        record("Add automation lane", undo = { t.lanes.remove(l); if (laneSelected == l) laneSelected = null; syncLanes(t); dirty = true }, redo = { t.lanes.add(l); syncLanes(t); dirty = true })
        return l
    }
    fun removeLane(t: TrackM, l: AutoLane) {
        val at = t.lanes.indexOf(l); if (at < 0) return
        t.lanes.remove(l); if (laneSelected == l) laneSelected = null; syncLanes(t); dirty = true
        record("Delete automation lane", undo = { t.lanes.add(at.coerceAtMost(t.lanes.size), l); syncLanes(t); dirty = true },
            redo = { t.lanes.remove(l); if (laneSelected == l) laneSelected = null; syncLanes(t); dirty = true })
    }
    private fun putLanePoints(t: TrackM, l: AutoLane, pts: List<AutoPoint>) { l.points.clear(); l.points.addAll(pts); syncLanes(t); dirty = true }
    fun setLanePoints(t: TrackM, l: AutoLane, pts: List<AutoPoint>) {
        val before = l.points.toList(); val after = pts.sortedBy { it.beat }
        putLanePoints(t, l, after)
        if (before != after) record("Edit automation", "lane${System.identityHashCode(l)}", undo = { putLanePoints(t, l, before) }, redo = { putLanePoints(t, l, after) })
    }
    private fun syncLanes(t: TrackM) {
        val lanes = t.lanes.filter { it.points.isNotEmpty() }
        val meta = LongArray(lanes.size * 2); val frames = ArrayList<Long>(); val values = ArrayList<Float>(); val shapes = ArrayList<Byte>()
        lanes.forEachIndexed { i, l -> meta[i * 2] = l.paramIndex.toLong(); meta[i * 2 + 1] = l.points.size.toLong()
            l.points.forEach { frames.add(fr(it.beat)); values.add(it.value); shapes.add(it.shape.toByte()) } }
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
        pendingStates.remove(t.id)?.let { (preset, st) -> t.presetName = preset; st.forEach { (k, x) -> t.states[k] = x; sendPluginState(t, k, x) } }
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
    // ---------- plugins found anywhere on the phone storage (F: in Wine) ----------
    @Volatile private var storagePlugins: List<File> = emptyList()
    private var storageScanning = false
    /** Looks for .vst3 files in the phone storage (a few levels deep, skipping Android/, hidden folders and the ZedokMusic folder that is scanned anyway). Runs in the background; the pickers refresh when done. */
    fun scanStorage() {
        if (storageScanning) return
        storageScanning = true
        Thread {
            val found = ArrayList<File>()
            val root = File("/storage/emulated/0"); val skip = workspace?.let { runCatching { it.canonicalFile }.getOrNull() }
            fun walk(d: File, depth: Int) {
                val kids = d.listFiles() ?: return
                for (f in kids) {
                    if (f.name.startsWith(".")) continue
                    if (f.isFile && f.name.endsWith(".dll", true) && f.path.contains("vst", true)) found.add(f)   // VST2 plugins in a folder with "vst" in its path
                    else if (f.name.endsWith(".vst3", true)) {
                        val dll = if (f.isFile) f else f.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.endsWith(".vst3", true) && it.path.contains("x86_64-win") }
                        if (dll != null) found.add(dll)
                    } else if (f.isDirectory && depth < 4 && !(depth == 0 && f.name == "Android") && (skip == null || runCatching { f.canonicalFile }.getOrNull() != skip)) walk(f, depth + 1)
                }
            }
            runCatching { walk(root, 0) }
            storagePlugins = found; storageScanning = false
            android.os.Handler(android.os.Looper.getMainLooper()).post { pluginsVersion++ }
        }.start()
    }

    /** 64-bit VST2 plugins are .dll files; every .dll of the usual plugin folders (and of the phone storage, in folders named vst...) is a candidate until a probe says whether it is a plugin. */
    private fun dllCandidates(): List<File> {
        val ws = workspace ?: return storagePlugins.filter { it.name.endsWith(".dll", true) }
        val dirs = listOf("Plugins/VST2", "Plugins/VST3", "C/Program Files/VSTPlugins", "C/Program Files/Steinberg/VstPlugins", "C/Program Files/Common Files/VST2",
            "C/Program Files/Common Files/Steinberg/VST2", "C/Program Files/Common Files/VST3").map { File(ws, it) }
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) { d.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f -> if (f.isFile && f.name.endsWith(".dll", true)) out.add(f) else if (f.isDirectory && depth < 2 && !f.name.endsWith(".vst3", true)) walk(f, depth + 1) } }
        dirs.forEach { if (it.isDirectory) walk(it, 0) }
        out.addAll(storagePlugins.filter { it.name.endsWith(".dll", true) && out.none { o -> o.path == it.path } })
        return out
    }
    /** The plugins offered in the pickers: built-in ones and the Windows plugins found, with the type the user chose in the plugin manager (if any) and without the hidden ones. */
    fun scanPlugins(includeHidden: Boolean = false): List<PluginInfo> {
        val out = ArrayList<PluginInfo>(nativePlugins())
        for (dir in listOfNotNull(pluginDir(), installedPluginDir())) {
            dir.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f ->
                if (!f.name.endsWith(".vst3", true)) return@forEach
                val dll = if (f.isFile) f else f.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".vst3", true) && it.path.contains("x86_64-win") }
                if (dll != null && out.none { it.path == dll.path }) {
                    val detected = kindOfPath(dll.path); val ov = pluginOverride(dll.path); val hidden = isPluginHidden(dll.path)
                    if (hidden && !includeHidden) return@forEach
                    out.add(PluginInfo(f.nameWithoutExtension.replace(Regex("\\s*\\((x64|64-?bit)\\)$", RegexOption.IGNORE_CASE), ""), dll.path, ov ?: detected, "", detected, ov != null, hidden))  // u-he installers name the 64-bit file "Name(x64).vst3"
                }
            }
        }
        for (dll in dllCandidates()) {
            if (out.any { it.path == dll.path }) continue
            val detected = kindOfPath(dll.path)
            if (detected == 0 || detected == 4) continue     // not checked yet (probeUnknown does it) or not a plugin at all
            val ov = pluginOverride(dll.path); val hidden = isPluginHidden(dll.path)
            if (hidden && !includeHidden) continue
            out.add(PluginInfo(dll.nameWithoutExtension, dll.path, ov ?: detected, "", detected, ov != null, hidden))
        }
        for (dll in storagePlugins) {
            if (dll.name.endsWith(".dll", true) || out.any { it.path == dll.path }) continue
            val name = (if (dll.path.contains("x86_64-win")) dll.parentFile?.parentFile?.parentFile?.name ?: dll.name else dll.name).removeSuffix(".vst3").replace(Regex("\\s*\\((x64|64-?bit)\\)$", RegexOption.IGNORE_CASE), "")
            val detected = kindOfPath(dll.path); val ov = pluginOverride(dll.path); val hidden = isPluginHidden(dll.path)
            if (hidden && !includeHidden) continue
            out.add(PluginInfo(name, dll.path, ov ?: detected, "", detected, ov != null, hidden))
        }
        return out
    }

    // ---------- plugin manager: what the user decided about each Windows plugin ----------
    private val overrideFile get() = File(getApplication<Application>().filesDir, "plugin_overrides.json")
    private var overrideCache: JSONObject? = null
    private fun overrides(): JSONObject = overrideCache ?: (runCatching { JSONObject(overrideFile.readText()) }.getOrNull() ?: JSONObject()).also { overrideCache = it }
    private fun saveOverrides() { runCatching { overrideFile.writeText(overrides().toString()) }; pluginsVersion++ }
    /** null = automatic (what the plugin reports); 0 = offered both as instrument and effect; 1 = instrument only; 2 = effect only. */
    fun pluginOverride(path: String): Int? = overrides().optJSONObject(path)?.takeIf { it.has("kind") }?.optInt("kind")
    fun isPluginHidden(path: String): Boolean = overrides().optJSONObject(path)?.optBoolean("hidden") == true
    fun setPluginOverride(path: String, kind: Int?) {
        val o = overrides(); val e = o.optJSONObject(path) ?: JSONObject()
        if (kind == null) e.remove("kind") else e.put("kind", kind)
        if (e.length() == 0) o.remove(path) else o.put(path, e); saveOverrides()
    }
    fun setPluginHidden(path: String, hidden: Boolean) {
        val o = overrides(); val e = o.optJSONObject(path) ?: JSONObject()
        if (hidden) e.put("hidden", true) else e.remove("hidden")
        if (e.length() == 0) o.remove(path) else o.put(path, e); saveOverrides()
    }
    /** Forgets what was learned about a plugin's type and asks the plugin again (it is started briefly in Wine, without its window). */
    fun redetectPlugin(path: String) {
        kinds().remove(path); runCatching { kindFile.writeText(kinds().toString()) }; pluginsVersion++
        probeUnknown(scanPlugins().filter { it.path == path })
    }

    // ---------- plugin kinds: instrument or effect ----------
    private val kindFile get() = File(getApplication<Application>().filesDir, "plugin_kinds.json")
    private var kindCache: JSONObject? = null
    private fun kinds(): JSONObject = kindCache ?: (runCatching { JSONObject(kindFile.readText()) }.getOrNull() ?: JSONObject()).also { kindCache = it }
    private fun stamp(f: File) = "${f.length()}:${f.lastModified()}"
    /** 0 = not checked yet, 1 instrument, 2 effect, 3 checked but the plugin does not say, 4 not a plugin (a .dll that is no VST2). Stale when the file changed. */
    fun kindOfPath(path: String): Int { val a = kinds().optJSONArray(path) ?: return 0; return if (a.optString(0) == stamp(File(path))) a.optInt(1) else 0 }
    private fun setKind(path: String, kind: Int) { kinds().put(path, JSONArray().put(stamp(File(path))).put(kind)); runCatching { kindFile.writeText(kinds().toString()) } }
    /** Bumped when plugin kinds become known, so open pickers re-list. */
    var pluginsVersion by mutableIntStateOf(0); private set
    fun refreshPlugins() { pluginsVersion++; scanStorage() }
    private fun rememberKind(t: TrackM) {
        val p = t.pluginPath ?: return
        if (PluginHost.isNative(p)) return
        val k = e.pluginKind(t.id)
        if (k in 1..2 && kindOfPath(p) != k) { setKind(p, k); pluginsVersion++ }
    }
    var probing by mutableStateOf(false); private set
    /** Reads the type (instrument / effect) of plugins not seen before, one by one, without starting them. */
    fun probeUnknown(plugins: List<PluginInfo>) {
        val todo = plugins.filter { it.kind == 0 && !it.manual && !PluginHost.isNative(it.path) } +
            dllCandidates().filter { d -> kindOfPath(d.path) == 0 && plugins.none { it.path == d.path } }.map { PluginInfo(it.nameWithoutExtension, it.path) }
        if (probing || todo.isEmpty()) return
        probing = true
        Thread {
            try { for (p in todo) { val k = host.probe(p.path); setKind(p.path, if (k in 1..2) k else if (k < 0) 4 else 3); pluginsVersion++ } }
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
    private fun wavs(dir: File?) = dir?.takeIf { it.isDirectory }?.walkTopDown()?.filter { f -> f.isFile && f.extension.equals("wav", true) && !f.path.contains("/.processed/") }?.sortedBy { f -> f.name.lowercase() }?.toList() ?: emptyList()
    /** WAV files of this project (its own Samples folder, recordings included). */
    fun samplesChanged() { samplesVersion++ }
    /** Where the sound pack of Zedok Piano is on the phone (the app copies it there at the first start). */
    fun pianoPackPath(): String = java.io.File(getApplication<Application>().filesDir, "plugin-data/piano.zpk").path
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
        val to = if (range == 1) fr(loopBars * 4f)
            else fr(songBeats()) + 2 * 48000L
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
    fun flush() { if (!projectBusy && !formatLocked && (dirty || viewDirty) && AppSettings.autosave.on && !recording) { save(); dirty = false } }

    /** The File → Save command: says so when the project cannot be saved (written by a newer app). */
    fun saveByHand() { if (formatLocked) message = "This project comes from a newer version of ZedokMusic: it is not saved, so the newer file stays intact." else { save(); message = "Project saved" } }

    fun save() {
        val f = projectFile ?: return
        if (formatLocked) return
        viewDirty = false
        store?.backup(projectName)
        store?.create(projectName)
        write(f)
        sweepProcessed()
    }

    /** Deletes processed renders that no clip uses any more (kept for a minute in case one is still being loaded). */
    private fun sweepProcessed() {
        val dir = processedDir() ?: return
        if (tracks.any { t -> t.clips.any { it.busy } }) return
        val used = tracks.flatMap { t -> t.clips.map { File(it.path).absolutePath } }.toSet()
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f -> if (f.isFile && f.absolutePath !in used && now - f.lastModified() > 60_000) f.delete() }
    }

    /** Writes the project to [f]; [mapPath] rewrites sample paths (used when the project moves to another folder). */
    private fun write(f: File, mapPath: (String) -> String = { it }) {
        val root = JSONObject().put("version", ProjectFormat.CURRENT).put("bpm", bpm.toDouble()).put("master", masterGain.toDouble()).put("masterStereo", masterStereo.toDouble()).put("loopBars", loopBars).put("loop", loop).put("autoCrossfade", autoCrossfade).put("scale", JSONObject().put("root", scaleRoot).put("type", scaleType))
            .put("view", JSONObject().put("ppb", ppb.toDouble()).put("ax", arrangeX).put("ay", arrangeY).put("rx", rollX).put("ry", rollY))
        val arr = JSONArray()
        for (t in tracks) arr.put(JSONObject().put("name", t.name).put("kind", t.kind.name).put("gain", t.gain.toDouble()).put("pan", t.pan.toDouble()).put("stereo", t.stereo.toDouble())
            .put("mute", t.mute).put("solo", t.solo).put("plugin", t.pluginPath ?: JSONObject.NULL)
            .also { o -> t.frozenFile?.let { ff -> if (t.frozen) o.put("frozen", mapPath(ff.absolutePath)) }; if (t.output != 0) o.put("output", busPos(t.output)); if (t.sends.isNotEmpty()) o.put("sends", JSONArray().also { a -> t.sends.forEach { s -> a.put(JSONObject().put("bus", busPos(s.bus)).put("gain", s.gain.toDouble()).put("pre", s.pre)) } }) }
            .put("params", paramsJson(t)).put("lanes", lanesJson(t)).put("fx", fxJson(t.fx)).put("preset", t.presetName).put("nstate", JSONObject(t.states.toMap()))
            .put("clips", JSONArray().also { a -> t.clips.forEach { c -> a.put(JSONObject().put("path", mapPath(c.path)).put("src", mapPath(c.src)).put("start", c.start.toDouble())
                .put("gain", c.gainDb.toDouble()).put("semis", c.semis.toDouble()).put("speed", c.speed.toDouble()).put("mode", c.mode).put("srcBpm", c.srcBpm.toDouble())
                .also { o -> if (c.hasFades) o.put("fadeIn", c.fadeInMs.toDouble()).put("fadeOut", c.fadeOutMs.toDouble()).put("fadeInShape", c.fadeInShape).put("fadeOutShape", c.fadeOutShape) }) } }))
        root.put("tracks", arr).put("masterFx", fxJson(masterFx))
        if (buses.isNotEmpty()) root.put("buses", JSONArray().also { a -> buses.forEach { b -> a.put(JSONObject().put("name", b.name).put("gain", b.gain.toDouble()).put("pan", b.pan.toDouble()).put("stereo", b.stereo.toDouble()).put("mute", b.mute).put("output", busPos(b.output)).also { o -> if (b.fx.isNotEmpty()) o.put("fx", fxJson(b.fx)) }) } })
        val pats = JSONArray()
        for (pt in patterns) {
            val per = JSONObject()
            tracks.forEachIndexed { i, t -> if (t.kind == TrackKind.Instrument) { val ns = notesOf(pt, t); if (ns.isNotEmpty()) per.put(i.toString(), JSONArray().also { a -> ns.forEach { n -> a.put(noteJson(n)) } }) } }
            pats.put(JSONObject().put("id", pt.id).put("name", pt.name).put("color", TrackColors.indexOf(pt.color).coerceAtLeast(0)).also { o -> pt.fixedLength?.let { o.put("length", it.toDouble()) } }.put("tracks", per))
        }
        root.put("sigChanges", JSONArray().also { a -> sigChanges.forEach { a.put(JSONArray().put(it.beat.toDouble()).put(it.num).put(it.den)) } })
        root.put("tempoChanges", JSONArray().also { a -> tempoChanges.forEach { a.put(JSONArray().put(it.beat.toDouble()).put(it.bpm.toDouble())) } })
            .put("markers", JSONArray().also { a -> markers.forEach { a.put(JSONObject().put("beat", it.beat.toDouble()).put("name", it.name)) } })
        root.put("patterns", pats).put("currentPattern", currentPattern?.id ?: 1)
            .put("playlist", JSONArray().also { a -> playlist.forEach { c -> a.put(JSONObject().put("pattern", c.pattern.id).put("start", c.start.toDouble()).put("row", c.row).also { o -> c.length?.let { o.put("length", it.toDouble()) } }) } })
        try { f.parentFile?.mkdirs(); f.writeText(root.toString(1)) } catch (ex: Exception) { message = "Could not save the project: ${ex.message}" }
    }

    /** A bus as saved: its 1-based position in the bus list (engine ids change from one session to the next), 0 = the master. */
    private fun busPos(id: Int) = if (id == 0) 0 else buses.indexOfFirst { it.id == id } + 1

    /** A note as saved: [pitch, start, length, velocity] and, when it has properties, pan, cutoff, res, fine, release, slide. */
    private fun noteJson(n: NoteM) = JSONArray().put(n.pitch).put(n.start.toDouble()).put(n.length.toDouble()).put(n.velocity).also { r -> if (n.hasProps) r.put(n.pan).put(n.cutoff).put(n.res).put(n.fine).put(n.release).put(if (n.slide) 1 else 0) }
    private fun noteFrom(n: JSONArray) = NoteM(n.getInt(0), n.getDouble(1).toFloat(), n.getDouble(2).toFloat(), n.getInt(3), n.optInt(4, 0), n.optInt(5, 0), n.optInt(6, 0), n.optInt(7, 0), n.optInt(8, 0), n.optInt(9, 0) != 0)
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
    private fun loadFx(owner: TrackM?, arr: JSONArray?, bus: BusM? = null) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val path = o.optString("plugin").takeIf { it.isNotEmpty() && (PluginHost.isNative(it) || File(it).exists()) } ?: continue
            val fx = addEffectInternal(owner, o.optString("name", File(path).nameWithoutExtension), path, bus) ?: continue
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
        formatLocked = false
        resetPatterns()
        tracks.toList().forEach { removeTrack(it) }
        masterFx.toList().forEach { removeEffect(null, it) }
        buses.toList().forEach { b -> b.fx.toList().forEach { removeEffect(null, it, b) }; e.removeBus(b.id) }; buses.clear()
        pluginTarget = null; panelExpanded = false
        editorSlots.clear()
        loop = false; ppbValue = 44f; arrangeX = 0; arrangeY = 0; rollX = 0; rollY = -1; viewRevision++
        tempoChanges.clear(); markers.clear(); sigChanges.clear(); bpm = DEFAULT_BPM; rebuildTempo(false); scaleRoot = 0; scaleType = 2; loopBars = 4; masterGain = 1f; e.setMasterGain(1f); masterStereo = 0f; e.setMasterWidth(1f); applyLoop()
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
        tracks.forEach { t -> t.clips.forEach { c -> if (c.path.startsWith(fp)) c.path = File(to, c.path.removePrefix(fp)).absolutePath; if (c.src.startsWith(fp)) c.src = File(to, c.src.removePrefix(fp)).absolutePath } }
    }

    /** Save as: a new project folder with a copy of this project's samples and presets; it becomes the current project. */
    fun saveAs(name: String) {
        val s = store ?: return
        if (projectBusy) return
        if (formatLocked) { message = "This project was saved by a newer version of ZedokMusic and cannot be saved by this one."; return }
        val old = projectName; val n = s.uniqueName(name)
        save()
        s.create(n)
        runCatching { s.samples(old).copyRecursively(s.samples(n), overwrite = false); s.presets(old).copyRecursively(s.presets(n), overwrite = false); s.kits(old).copyRecursively(s.kits(n), overwrite = false) }
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
        val moved = HashMap<String, String>()
        for (t in tracks) for (c in t.clips) {
            val oldPath = c.path
            fun bring(p: String): String? {
                val f = File(p)
                if (!f.exists()) return null
                if (f.absolutePath.startsWith(dir.absolutePath + "/")) return p
                moved[p]?.let { return it }
                var out = File(dir, f.name); var k = 2
                while (out.exists()) out = File(dir, f.nameWithoutExtension + " ($k)." + f.extension).also { k++ }
                return runCatching { f.copyTo(out); n++; out.absolutePath.also { moved[p] = it } }.getOrNull()
            }
            val np = bring(oldPath)
            if (np != null) c.path = np
            // the original recording follows (an unedited clip's original is the clip's own file)
            val ns = if (c.src == oldPath) np else bring(c.src)
            if (ns != null) c.src = ns
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
    /**
     * Applies a preset: every parameter takes the preset's value, or returns to its default when the preset does not list it.
     * Instruments are hard-silenced first (a short fade, then every voice and effect tail is forgotten), so the previous sound can never ring on or colour the new one.
     * [preview] plays a short phrase of the new sound; only the preset browser asks for it, the arrows do not.
     */
    fun applyPreset(t: TrackM, p: Preset, preview: Boolean = false) {
        presetPreview?.cancel()
        if (isNativeInstrument(t)) { e.silence(t.id); lastPreviewAt = 0 }
        for (pm in loadParams(t)) {
            val target = p.values[pm.name] ?: pm.default
            e.setParam(t.id, pm.index, target)
            if (Math.abs(target - pm.default) > 1e-4f) t.paramOverrides[pm.index] = target else t.paramOverrides.remove(pm.index)
        }
        p.state.forEach { (k, x) -> sendPluginState(t, k, x); t.states[k] = x }
        t.presetName = p.name; t.presetKey = p.key
        paramsRevision++; dirty = true
        if (preview) previewPresetNote(t)
    }
    private fun isNativeInstrument(t: TrackM) = nativeId(t) in setOf("hybrid", "grain", "drums", "sampler", "piano")
    private var presetPreview: kotlinx.coroutines.Job? = null
    private var lastPreviewAt = 0L
    /** Stops a preview that is still playing (the preset browser was closed): the notes are released, never cut. */
    fun stopPreview() { presetPreview?.cancel(); presetPreview = null }
    /**
     * Plays a short phrase so the chosen preset can be heard (Settings -> Plugins: C4, C5 or off). One preview at a time: a new preset (or closing the browser) ends the previous one.
     * Synths and the piano play one note; Zedok Drums a kick, a snare and a hat; the sampler its own root note.
     */
    private fun previewPresetNote(t: TrackM) {
        val setting = com.zedokmusic.settings.AppSettings.presetPreviewNote.value
        val base = when (setting) { 0 -> 48; 1 -> 60; 2 -> 72; else -> return }
        val id = nativeId(t) ?: return
        val notes: List<Pair<Int, Long>>   // pitch, how long it is held (ms)
        val pitch = if (id == "sampler") (loadParams(t).firstOrNull { it.name == "Root Note" }?.let { paramValue(t, it.index) * 127f }?.toInt() ?: base) else base
        notes = when (id) {
            "drums" -> listOf(36 to 120L, 38 to 120L, 42 to 120L)
            "piano" -> listOf(pitch to 1500L)
            "grain" -> listOf(pitch to 1500L)
            else -> listOf(pitch to 900L)
        }
        lastPreviewAt = System.currentTimeMillis()
        presetPreview = viewModelScope.launch {
            kotlinx.coroutines.delay(140)   // the old sound fades out and the new values reach the plugin first
            var held = -1
            try {
                for ((i, n) in notes.withIndex()) {
                    held = n.first; liveNote(t, true, n.first)
                    kotlinx.coroutines.delay(n.second)
                    liveNote(t, false, n.first); held = -1
                    if (id == "drums" && i < notes.size - 1) kotlinx.coroutines.delay(110)
                }
            } finally { if (held >= 0) liveNote(t, false, held) }
        }
    }
    /** True when the user changed the current sound: a control differs from what the selected preset (or the defaults) gives, or something was loaded into the plugin (samples, a kit, ...). */
    fun isTouched(t: TrackM): Boolean {
        val preset = nativeId(t)?.let { id -> presetLibrary.all(id).firstOrNull { it.key == t.presetKey } }
        for (pm in loadParams(t)) if (Math.abs(paramValue(t, pm.index) - (preset?.values?.get(pm.name) ?: pm.default)) > 1e-4f) return true
        return t.states.any { (k, x) -> x.isNotBlank() && x != (preset?.state?.get(k) ?: "") }
    }
    /** "Init preset": every control back to its default and everything loaded in the plugin removed (samples, kit...). Files stay in the project folder. Sent in small batches (the engine's queue is short). */
    fun initPlugin(t: TrackM) {
        // a toast: the preset browser is a full-screen dialog that would hide the snackbar
        android.widget.Toast.makeText(getApplication<Application>(), "Init preset loaded: every control is back to its default.", android.widget.Toast.LENGTH_SHORT).show()
        if (isNativeInstrument(t)) { presetPreview?.cancel(); e.silence(t.id) }
        for (k in t.states.keys.toList()) sendPluginState(t, k, "")
        t.states.clear(); t.presetName = ""; t.presetKey = ""; t.abStore[0] = null; t.abStore[1] = null; t.abSlot = 0
        val params = loadParams(t)
        viewModelScope.launch {
            var sent = 0
            for (p in params) {
                if (Math.abs(paramValue(t, p.index) - p.default) < 1e-4f) { t.paramOverrides.remove(p.index); continue }
                e.setParam(t.id, p.index, p.default); t.paramOverrides.remove(p.index)
                if (++sent % 64 == 0) kotlinx.coroutines.delay(25)
            }
            paramsRevision++; dirty = true; message = "${t.pluginName.ifBlank { t.name }} reset to Init."
        }
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
    /** Drum kits: the pad states hold project-relative paths ("Kits/Name/kick.wav"); the engine gets absolute ones. Loading audio takes a moment, so it runs on the kit worker. */
    internal fun sendPluginState(t: TrackM, key: String, value: String) {
        if (key.length > 3 && key.startsWith("pad") && key[3].isDigit()) {
            val dir = projectDir()?.absolutePath ?: return
            val abs = value.lines().joinToString("\n") { l -> val p = l.split(',', limit = 4); if (p.size == 4 && !p[3].startsWith("/")) "${p[0]},${p[1]},${p[2]},$dir/${p[3]}" else l }
            kits.run { e.setPluginState(t.id, key, abs) }
        } else e.setPluginState(t.id, key, value)
    }
    val kits = KitManager(this, app)
    /** The sound browser of the Tracks view is open. */
    var browserOpen by mutableStateOf(false)
    internal fun markDirty() { dirty = true }
    internal fun bumpParams() { paramsRevision++ }
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

    private fun loadTempo(root: JSONObject) {
        tempoChanges.clear(); markers.clear(); sigChanges.clear()
        root.optJSONArray("sigChanges")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { sigChanges.add(SigPoint(it.getDouble(0).toFloat(), it.getInt(1), it.getInt(2))) } }
        root.optJSONArray("tempoChanges")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { tempoChanges.add(TempoPoint(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())) } }
        root.optJSONArray("markers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { markers.add(MarkerM(it.optDouble("beat", 0.0).toFloat(), it.optString("name", "Marker"))) } }
        rebuildTempo(false)
    }

    /** Reads the patterns and the playlist; the notes of the current pattern go into the tracks' note lists, the others stay stored in their pattern. */
    private fun loadPatterns(root: JSONObject) {
        patterns.clear(); playlist.clear(); currentPattern = null
        val arr = root.optJSONArray("patterns") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val id = o.optInt("id", i + 1); patternCounter = maxOf(patternCounter, id)
            val p = PatternM(id, o.optString("name", "Pattern $id"), TrackColors[o.optInt("color", 0).coerceIn(0, TrackColors.size - 1)])
            if (o.has("length")) p.fixedLength = o.getDouble("length").toFloat()
            val per = o.optJSONObject("tracks")
            if (per != null) for (key in per.keys()) { val t = tracks.getOrNull(key.toIntOrNull() ?: -1) ?: continue; val na = per.getJSONArray(key); p.stored[t.id] = (0 until na.length()).map { noteFrom(na.getJSONArray(it)) } }
            patterns.add(p)
        }
        if (patterns.isEmpty()) patterns.add(PatternM(++patternCounter, "Pattern 1", TrackColors[0]))
        val cur = patterns.firstOrNull { it.id == root.optInt("currentPattern", 1) } ?: patterns.first()
        val pl = root.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until pl.length()) {
            val o = pl.getJSONObject(i); val p = patterns.firstOrNull { it.id == o.optInt("pattern") } ?: continue
            playlist.add(PatternClipM(p, o.optDouble("start", 0.0).toFloat(), if (o.has("length")) o.getDouble("length").toFloat() else null, o.optInt("row", 0)))
        }
        for (t in tracks) if (t.kind == TrackKind.Instrument) { t.notes.clear(); t.notes.addAll(cur.stored[t.id] ?: emptyList()); cur.stored.remove(t.id) }
        currentPattern = cur
        syncAllNotes()
    }

    private fun busAt(pos: Int) = if (pos in 1..buses.size) buses[pos - 1].id else 0
    private fun loadBuses(a: JSONArray?) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val id = e.addBus(); if (id == 0) break
            val b = BusM(id, a.getJSONObject(i).optString("name", "Bus ${i + 1}"), TrackColors[id % TrackColors.size]); buses.add(b)
        }
        for (i in buses.indices) {
            val o = a.getJSONObject(i); val b = buses[i]
            b.gain = o.optDouble("gain", 1.0).toFloat(); b.pan = o.optDouble("pan", 0.0).toFloat(); b.stereo = o.optDouble("stereo", 0.0).toFloat(); b.mute = o.optBoolean("mute")
            e.setBusGain(b.id, b.gain); e.setBusPan(b.id, b.pan); e.setBusWidth(b.id, widthOf(b.stereo)); e.setBusMute(b.id, b.mute)
            val dest = busAt(o.optInt("output", 0)); if (dest != 0 && e.setBusOutput(b.id, dest)) b.output = dest
            loadFx(null, o.optJSONArray("fx"), b)
        }
    }
    private fun loadRouting(t: TrackM, o: JSONObject) {
        val out = busAt(o.optInt("output", 0)); if (out != 0 && e.setTrackOutput(t.id, out)) t.output = out
        val sa = o.optJSONArray("sends") ?: return
        for (k in 0 until sa.length()) { val s = sa.getJSONObject(k); val bus = busAt(s.optInt("bus", 0)); val g = s.optDouble("gain", 1.0).toFloat(); val pre = s.optBoolean("pre")
            if (bus != 0 && e.setSend(t.id, bus, g, pre)) t.sends.add(SendM(bus, g, pre)) }
    }

    private fun load() {
        formatLocked = false
        clearHistory()
        val f = projectFile ?: return
        if (!f.exists()) return
        try {
            val root = try { ProjectFormat.migrate(JSONObject(f.readText())) } catch (ex: ProjectFormat.TooNew) { formatLocked = true; message = ex.message; dirty = false; return }
            bpm = root.optDouble("bpm", 120.0).toFloat(); loadTempo(root); masterGain = root.optDouble("master", 1.0).toFloat(); e.setMasterGain(masterGain); masterStereo = root.optDouble("masterStereo", 0.0).toFloat(); e.setMasterWidth(widthOf(masterStereo))
            loopBars = root.optInt("loopBars", 4); loop = root.optBoolean("loop", false); applyLoop()
            root.optJSONObject("scale").let { sc -> scaleRoot = sc?.optInt("root", 0)?.coerceIn(0, 11) ?: 0; scaleType = sc?.optInt("type", 0)?.coerceIn(0, Theory.SCALES.size - 1) ?: 0 }
            root.optJSONObject("view")?.let { v ->
                ppbValue = v.optDouble("ppb", 44.0).toFloat().coerceIn(ZOOM_MIN, ZOOM_MAX)
                arrangeX = v.optInt("ax"); arrangeY = v.optInt("ay"); rollX = v.optInt("rx"); rollY = v.optInt("ry", -1)
            } ?: run { ppbValue = 44f; arrangeX = 0; arrangeY = 0; rollX = 0; rollY = -1 }
            viewRevision++
            autoCrossfade = root.optBoolean("autoCrossfade", false); e.setAutoCrossfade(autoCrossfade)
            loadBuses(root.optJSONArray("buses"))
            val arr = root.getJSONArray("tracks")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plugin = if (o.isNull("plugin")) null else o.getString("plugin")
                val t: TrackM
                if (plugin != null) {
                    val id = e.addTrack(); t = TrackM(id, o.getString("name"), TrackKind.Instrument, newColor(), plugin); tracks.add(t)
                    t.pluginState = 1; if (!host.launch(id, plugin)) t.pluginState = 3
                } else { val id = e.addTrack(); t = TrackM(id, o.getString("name"), TrackKind.Audio, newColor(), null); tracks.add(t) }
                t.gain = o.optDouble("gain", 1.0).toFloat(); t.pan = o.optDouble("pan", 0.0).toFloat(); t.stereo = o.optDouble("stereo", 0.0).toFloat(); e.setTrackWidth(t.id, widthOf(t.stereo))
                t.mute = o.optBoolean("mute"); t.solo = o.optBoolean("solo")
                e.setTrackGain(t.id, t.gain); e.setTrackPan(t.id, t.pan); e.setTrackMute(t.id, t.mute); e.setTrackSolo(t.id, t.solo)
                loadRouting(t, o)
                o.optString("frozen").takeIf { it.isNotEmpty() }?.let { fp -> File(fp).takeIf { it.exists() }?.let { applyFrozen(t, it) } }
                readPluginState(t, o)
                loadFx(t, o.optJSONArray("fx"))
                val ca = o.getJSONArray("clips")
                for (k in 0 until ca.length()) { val c = ca.getJSONObject(k); val src = File(c.optString("src", c.getString("path"))); val w = File(c.getString("path")).takeIf { it.exists() } ?: src.takeIf { it.exists() }
                    if (w != null) importClip(t, w, c.getDouble("start").toFloat())?.let { cl ->
                        cl.src = src.absolutePath; cl.semis = c.optDouble("semis", 0.0).toFloat(); cl.speed = c.optDouble("speed", 1.0).toFloat(); cl.mode = c.optInt("mode", 1); cl.srcBpm = c.optDouble("srcBpm", 0.0).toFloat()
                        if (w == src && (cl.semis != 0f || cl.speed != 1f)) { cl.semis = 0f; cl.speed = 1f }   // the processed copy is gone: back to the original
                        setClipGainDb(cl, c.optDouble("gain", 0.0).toFloat())
                        cl.fadeInMs = c.optDouble("fadeIn", 0.0).toFloat(); cl.fadeOutMs = c.optDouble("fadeOut", 0.0).toFloat(); cl.fadeInShape = c.optInt("fadeInShape", 0); cl.fadeOutShape = c.optInt("fadeOutShape", 0); if (cl.hasFades) pushFade(cl)
                        dirty = false
                    } }
            }
            loadFx(null, root.optJSONArray("masterFx"))
            loadPatterns(root)
            selected = tracks.firstOrNull()
        } catch (ex: Exception) { android.util.Log.e("Zedok", "project load failed", ex); message = "Could not open the saved project: ${ex.message}" }
        dirty = false
    }

    companion object { const val DEFAULT_BPM = 130f; @Volatile var current: DawViewModel? = null; const val ZOOM_MIN = 4f; const val ZOOM_MAX = 400f }

    override fun onCleared() { current = null; stopRecording(); midi.stop(); save(); host.stopAll(); e.pause(); e.stop() }
}
