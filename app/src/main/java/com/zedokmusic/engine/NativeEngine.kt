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
    external fun addTrack(): Int
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
    /** [sampleRate, burst, bufferSize, xruns, avgCallbackUs, maxCallbackUs, latencyMs, exclusive] */
    external fun stats(): FloatArray

    external fun callbackFrames(): Int
    external fun addNote(track: Int, startFrame: Long, lengthFrames: Long, pitch: Int, velocity: Int): Boolean
    external fun clearNotes(track: Int)
    /** Replaces all notes of [track]; [data] holds 4 longs per note: startFrame, lengthFrames, pitch, velocity. */
    external fun setNotes(track: Int, data: LongArray)
    /** Creates the shared-memory bridge file for [track]; the Wine host must then be launched on [path]. */
    external fun createPluginBridge(track: Int, path: String): Boolean
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
    external fun setAutomation(track: Int, meta: LongArray, frames: LongArray, values: FloatArray)
    /** Parameter edits made inside the plugin editor since the last call: index, value, index, value... */
    external fun pollEdits(track: Int): FloatArray
    /** Plugin editor: [cmd] 1 = open, 2 = close. */
    external fun guiCommand(track: Int, cmd: Int)
    /** [state (0 closed, 1 open, 2 failed), width, height]. */
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
