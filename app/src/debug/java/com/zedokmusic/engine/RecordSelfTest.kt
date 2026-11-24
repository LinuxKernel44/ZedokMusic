package com.zedokmusic.engine

import java.io.File

/** Records the device microphone while the engine plays a 440 Hz tone. Trigger: --ez rec_test true (needs RECORD_AUDIO). */
object RecordSelfTest {
    fun run(dir: File): String {
        val e = NativeEngine; val sb = StringBuilder()
        if (!e.start()) return "ENGINE START FAILED"
        val tr = e.addTrack(); e.addToneClip(tr, 0, 6.0, 440.0, 0.8)
        val out = File(dir, "recording.wav")
        val err = e.recStart(out.absolutePath, 0)
        if (err.isNotEmpty()) { e.stop(); return "recStart failed: $err" }
        e.seek(0); e.play()
        var lvl = 0f
        repeat(30) { Thread.sleep(100); lvl = maxOf(lvl, e.recLevel()) }
        e.pause()
        val r = e.recStop()
        sb.appendLine("recorded frames=${r[0]} (expected ~144000) dropped=${r[1]} inputBuffer=${r[2]} channels=${r[3]} ok=${r[4]} liveLevel=%.3f file=${out.length()} bytes".format(lvl))
        e.stop()
        return sb.toString()
    }
}
