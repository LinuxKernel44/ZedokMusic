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
}
