package com.zedokmusic.engine

import java.io.File

/** On-device smoke test, triggered with: am start -n com.zedokmusic/.MainActivity --ez engine_test true */
object EngineSelfTest {
    fun run(dir: File): String {
        val sb = StringBuilder()
        fun log(s: String) { sb.appendLine(s) }
        val e = NativeEngine
        if (!e.start()) return "ENGINE START FAILED"
        val st = e.stats()
        log("stream: rate=${st[0].toInt()} burst=${st[1].toInt()} buffer=${st[2].toInt()} latency=${"%.1f".format(st[6])}ms exclusive=${st[7] > 0}")
        val a = e.addTrack(); val b = e.addTrack()
        e.addToneClip(a, 0, 4.0, 440.0, 0.5); e.addToneClip(b, 0, 4.0, 880.0, 0.25)
        e.setTrackPan(a, -1f); e.setTrackPan(b, 1f)
        log("tracks=$a,$b length=${e.projectLength()} frames")

        val wav = File(dir, "render.wav").absolutePath
        val t0 = System.nanoTime()
        val ok = e.renderToWav(wav, 0, 0, NativeEngine.WAV_FLOAT32)
        log("offline render ok=$ok in ${(System.nanoTime() - t0) / 1_000_000} ms -> $wav")

        e.seek(0); e.play()
        var maxA = 0f; var maxB = 0f; var maxM = 0f
        for (i in 0 until 40) {
            Thread.sleep(100)
            if (i == 10) e.setTrackMute(b, true)
            if (i == 20) { e.setTrackMute(b, false); e.setTrackGain(a, 0.5f) }
            maxA = maxOf(maxA, e.peak(a)); maxB = maxOf(maxB, e.peak(b)); maxM = maxOf(maxM, e.peak(-1))
        }
        e.pause()
        val s2 = e.stats()
        log("live: position=${e.position()} (expected ~192000) peakA=%.3f peakB=%.3f master=%.3f".format(maxA, maxB, maxM))
        log("callbacks=${s2[3].toInt().let { "xruns=$it" }} avgCb=%.1fus maxCb=%.1fus".format(s2[4], s2[5]))

        // loop test: 1s loop, play 2.5s -> position must stay inside [0, 48000)
        e.setLoop(true, 0, 48000); e.seek(0); e.play(); Thread.sleep(2500)
        val p = e.position(); e.pause(); e.setLoop(false, 0, 0)
        log("loop: position=$p inside=${p in 0 until 48000}")
        // churn: many edits while playing, check no crash
        e.seek(0); e.play()
        repeat(300) { e.setTrackGain(a, 0.2f + (it % 5) * 0.1f); e.setTrackPan(b, ((it % 3) - 1).toFloat()) ; Thread.sleep(5) }
        e.pause()
        log("churn done, xruns=${e.stats()[3].toInt()} maxCb=%.1fus".format(e.stats()[5]))
        e.stop()
        return sb.toString()
    }
}
