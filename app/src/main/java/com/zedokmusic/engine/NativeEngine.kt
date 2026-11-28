package com.zedokmusic.engine

/** Thin JNI facade over the native C++ audio engine (single global instance). */
object NativeEngine {
    init { System.loadLibrary("zedokaudio") }

    const val RATE = 48000
    const val WAV_PCM16 = 0
    const val WAV_PCM24 = 1
    const val WAV_FLOAT32 = 2

    external fun start(): Boolean
    external fun stop()
    /** Output device (AudioDeviceInfo id, 0 = automatic), buffer size in bursts (2..8) and exclusive mode; applied by the next [start]. */
    external fun configure(outDevice: Int, bufferMult: Int, exclusive: Boolean)
    external fun addTrack(): Int
    /** How many track ids were handed out; ids restart at 1 with [resetTrackIds] once the project has no track. */
    external fun trackIdsUsed(): Int
    /** Releases every sounding note of an instrument track (once). */
    external fun panic(track: Int)
    /** Hard stop of the track's plugin: voices and effect tails are dropped. */
    external fun silence(track: Int)
    /** Lets removed plugins' Wine hosts end now (see the C++ side). */
    external fun releaseRetired()
    /** Insert effects: [track] 0 = the master. Effect ids (>= 1000) work with every plugin call (bridge, parameters, editor, automation). Returns 0 when full. */
    external fun addFx(track: Int, position: Int): Int
    external fun removeFx(fxId: Int): Boolean
    external fun moveFx(fxId: Int, index: Int): Boolean
    external fun setFxBypass(fxId: Int, bypass: Boolean): Boolean
    /** 0 unknown, 1 instrument, 2 effect, as reported by the loaded plugin. */
    external fun pluginKind(id: Int): Int
    external fun resetTrackIds(): Boolean
    external fun removeTrack(id: Int): Boolean
    /** Loads a WAV into a new clip. Returns [clipId, frames] or null if unreadable. */
    external fun loadClip(track: Int, path: String, startFrame: Long): LongArray?
    external fun removeClip(clipId: Int): Boolean
    external fun moveClip(clipId: Int, startFrame: Long): Boolean
    external fun setClipGain(clipId: Int, gain: Float): Boolean
    /** Test helper. Returns the clip id (0 on failure). */
    external fun addToneClip(track: Int, startFrame: Long, seconds: Double, freq: Double, amp: Double): Int
    external fun setTrackGain(id: Int, gain: Float)
    external fun setTrackPan(id: Int, pan: Float)
    external fun setTrackWidth(id: Int, width: Float)
    external fun setMasterWidth(width: Float)
    external fun setClipFrozen(id: Int, frozen: Boolean): Boolean
    external fun setTrackFrozen(id: Int, frozen: Boolean)
    /** [momentary, short-term, integrated LUFS, range LU, true peak dB, sample peak dB, correlation]; -200 = no value yet. */
    external fun loudness(): FloatArray
    external fun spectrum(bands: Int): FloatArray
    external fun resetLoudness()
    external fun addBus(): Int
    external fun removeBus(id: Int): Boolean
    external fun setBusGain(id: Int, gain: Float)
    external fun setBusPan(id: Int, pan: Float)
    external fun setBusWidth(id: Int, width: Float)
    external fun setBusMute(id: Int, mute: Boolean)
    external fun setTrackOutput(track: Int, bus: Int): Boolean
    external fun setBusOutput(bus: Int, dest: Int): Boolean
    external fun setSend(track: Int, bus: Int, gain: Float, pre: Boolean): Boolean
    external fun removeSend(track: Int, bus: Int): Boolean
    external fun setDataDir(dir: String)
    external fun setTrackMute(id: Int, mute: Boolean)
    external fun setTrackSolo(id: Int, solo: Boolean)
    external fun setMasterGain(gain: Float)
    external fun setLoop(on: Boolean, startFrame: Long, endFrame: Long)
    external fun play()
    external fun pause()
    external fun seek(frame: Long)
    external fun isPlaying(): Boolean
    external fun position(): Long
    external fun projectLength(): Long
    /** Peak since last call; track -1 (or any invalid id) = master. */
    external fun peak(track: Int): Float
    external fun renderToWav(path: String, fromFrame: Long, toFrame: Long, format: Int): Boolean
    /** Renders to WAV (0 16-bit, 1 24-bit, 2 float), FLAC (4 16-bit, 5 24-bit) or MP3 (6 = 320 kbps, 7 = 192 kbps); [track] > 0 = one track only. */
    external fun renderEncoded(path: String, fromFrame: Long, toFrame: Long, format: Int, track: Int): Boolean
    /** Renders a single track (a stem); [track] is its engine id. */
    external fun renderTrackToWav(path: String, fromFrame: Long, toFrame: Long, format: Int, track: Int): Boolean
    /** [sampleRate, burst, bufferSize, xruns, avgCallbackUs, maxCallbackUs, latencyMs, exclusive] */
    external fun stats(): FloatArray

    external fun callbackFrames(): Int
    external fun addNote(track: Int, startFrame: Long, lengthFrames: Long, pitch: Int, velocity: Int): Boolean
    external fun clearNotes(track: Int)
    /** Replaces all notes of [track]; [data] holds 4 longs per note: startFrame, lengthFrames, pitch, velocity. */
    external fun setNotes(track: Int, data: LongArray)
    /** Creates the shared-memory bridge file for [track]; the Wine host must then be launched on [path]. */
    external fun createPluginBridge(track: Int, path: String): Boolean
    /** Creates a built-in plugin ([id] "hybrid", ...) on a track or effect slot; it is ready at once. */
    external fun createNativePlugin(track: Int, id: String): Boolean
    /** Extra state of a built-in plugin that is not a parameter (a sample path...), by key. */
    external fun setPluginState(track: Int, key: String, value: String): Boolean
    external fun getPluginState(track: Int, key: String): String
    /** Display data of a built-in plugin: waveform, spectrum, gain reduction... (meaning depends on the plugin and [what]). */
    external fun pluginTelemetry(track: Int, what: Int): FloatArray
    /** The section ("Filter 1", "Reverb"...) a built-in plugin's parameter belongs to. */
    external fun paramGroup(track: Int, index: Int): String
    /** Display text a built-in plugin's parameter would show at normalized value [norm] (choice labels). */
    external fun paramTextAt(track: Int, index: Int, norm: Float): String
    external fun setTempo(bpm: Double)
    external fun setTempoMap(frames: LongArray, bpms: DoubleArray)
    /** The built-in plugins as "id|name|kind|description" (kind 1 = instrument, 2 = effect). */
    external fun nativePlugins(): Array<String>
    external fun detachPlugin(track: Int): Boolean
    /** 0 none, 1 starting, 2 ready, 3 error */
    external fun pluginState(track: Int): Int
    external fun pluginName(track: Int): String
    external fun liveNote(track: Int, on: Boolean, pitch: Int, velocity: Int)
    /** [lateBlocks, processedBlocks, blocksWaitedFor, waitNsTotal, waitNsMax] */
    external fun pluginStats(track: Int): LongArray

    /** Extracts an uncompressed .tar.zst asset (fd/offset/length) into [dest]; returns "" on success, else an error. */
    external fun extractTarZst(fd: Int, offset: Long, length: Long, dest: String): String
    /** Compressed bytes consumed so far by the running extraction. */
    external fun extractProgress(): Long

    // --- plugin parameters (normalized 0..1); valid once pluginState == 2 ---
    /** Automation lanes: [meta] = (param index, point count) per lane; [frames]/[values] = all points, lane after lane. */
    external fun setAutomation(track: Int, meta: LongArray, frames: LongArray, values: FloatArray, shapes: ByteArray)
    /** Parameter edits made inside the plugin editor since the last call: index, value, index, value... */
    external fun pollEdits(track: Int): FloatArray
    /** Plugin editor: [cmd] 1 = open, 2 = close. */
    external fun guiCommand(track: Int, cmd: Int, x: Int, y: Int)
    /** [state (0 closed, 1 open, 2 failed), width, height, x, y]; x, y = the window's position on the X screen. */
    external fun guiState(track: Int): IntArray
    external fun paramCount(track: Int): Int
    /** Returns [name, units] and writes [defaultValue, stepCount] into [out]; null if the index is invalid. */
    external fun paramInfo(track: Int, index: Int, out: FloatArray): Array<String>?
    external fun paramValue(track: Int, index: Int): Float
    external fun paramText(track: Int, index: Int): String
    external fun setParam(track: Int, index: Int, value: Float)

    // --- audio input recording (stereo float32 WAV, mono inputs are duplicated) ---
    /** Starts recording the default input (or [deviceId]) to [path]; returns "" on success, else an error message. */
    external fun recStart(path: String, deviceId: Int): String
    /** [frames, droppedFrames, inputBufferFrames, channels, ok(1/0)] */
    external fun recStop(): LongArray
    external fun recActive(): Boolean
    /** Input peak since the last call. */
    external fun recLevel(): Float
}
