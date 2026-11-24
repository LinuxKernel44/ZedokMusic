package com.zedokmusic.engine

import android.content.Context
import java.io.File

/** End-to-end test: Podolski (VST3 under Wine/Box64) driven by the engine. Trigger: --ez plugin_test true */
object PluginSelfTest {
    fun run(ctx: Context): String {
        val sb = StringBuilder(); fun log(s: String) { sb.appendLine(s) }
        val e = NativeEngine; val dir = ctx.filesDir
        if (!e.start()) return "ENGINE START FAILED"
        val frames = e.callbackFrames()
        val tr = e.addTrack()
        val host = PluginHost(ctx)
        val t0 = System.currentTimeMillis()
        if (!host.launch(tr, File(com.zedokmusic.wine.StorageFolder.saved(ctx)!!, "Plugins/VST3/Podolski.vst3").path)) return "bridge creation failed"
        while (e.pluginState(tr) == 1 && System.currentTimeMillis() - t0 < 90_000) Thread.sleep(100)
        log("host state=${e.pluginState(tr)} name='${e.pluginName(tr)}' ready after ${System.currentTimeMillis() - t0} ms (block=$frames frames)")
        if (e.pluginState(tr) != 2) { log(File(dir, "host_$tr.log").readText().take(1500)); host.stopAll(); e.stop(); return sb.toString() }

        val np = e.paramCount(tr)
        val out = FloatArray(2); val names = ArrayList<String>()
        for (i in 0 until np) e.paramInfo(tr, i, out)?.let { names.add(it[0]) }
        log("params=$np first=${names.take(12)}")
        var volIdx = names.indexOfFirst { it == "Output" }.takeIf { it >= 0 } ?: names.indexOfFirst { it.contains("vol", true) || it.contains("master", true) || it.contains("gain", true) || it.contains("level", true) }
        log("volume-like param: ${if (volIdx >= 0) names[volIdx] + " idx=$volIdx value=" + e.paramValue(tr, volIdx) + " text='" + e.paramText(tr, volIdx) + "'" else "none"}")
        // arpeggio: C minor 7, 8 notes of 0.4 s, looping would be wrong here: play once
        val rate = NativeEngine.RATE; val pitches = intArrayOf(48, 55, 60, 63, 67, 70, 72, 75)
        pitches.forEachIndexed { i, p -> e.addNote(tr, i * 0.4 .let { (it * rate).toLong() }, (0.35 * rate).toLong(), p, 100) }
        e.seek(0); e.play()
        var peak = 0f; var nonSilent = 0
        for (i in 0 until 40) { Thread.sleep(100); val pk = e.peak(tr); peak = maxOf(peak, pk); if (pk > 1e-4f) nonSilent++ }
        e.pause()
        val s = e.stats(); val ps = e.pluginStats(tr)
        log("live arpeggio: peak=%.3f activeWindows=%d/40 position=%d xruns=%d avgCb=%.0fus maxCb=%.0fus".format(peak, nonSilent, e.position(), s[3].toInt(), s[4], s[5]))
        log("bridge: late=${ps[0]} blocks=${ps[1]} waitedFor=${ps[2]} avgWait=%.0fus maxWait=%.0fus".format(if (ps[2] > 0) ps[3] / ps[2] / 1000.0 else 0.0, ps[4] / 1000.0))

        if (volIdx >= 0) {
            val orig = e.paramValue(tr, volIdx)
            e.setParam(tr, volIdx, 0f); Thread.sleep(300)
            e.seek(0); e.play(); e.peak(tr)
            var p0 = 0f; for (i in 0 until 20) { Thread.sleep(100); p0 = maxOf(p0, e.peak(tr)) }
            e.pause()
            log("with ${names[volIdx]}=0 -> text='${e.paramText(tr, volIdx)}' peak=%.4f (was %.3f)".format(p0, peak))
            e.setParam(tr, volIdx, orig); Thread.sleep(300)
            e.seek(0); e.play(); e.peak(tr)
            var p1 = 0f; for (i in 0 until 20) { Thread.sleep(100); p1 = maxOf(p1, e.peak(tr)) }
            e.pause()
            log("restored -> text='${e.paramText(tr, volIdx)}' peak=%.3f".format(p1))
        }
        // live MIDI: hold a chord for 1 s with the transport stopped
        e.peak(-1); e.peak(tr)
        e.liveNote(tr, true, 60, 110); e.liveNote(tr, true, 64, 110); e.liveNote(tr, true, 67, 110)
        Thread.sleep(1000)
        val livePeak = e.peak(tr)
        e.liveNote(tr, false, 60, 0); e.liveNote(tr, false, 64, 0); e.liveNote(tr, false, 67, 0)
        log("live keys (transport stopped): peak=%.3f".format(livePeak))

        // offline render through the bridge
        val wav = File(dir, "render_plugin.wav").absolutePath
        val r0 = System.nanoTime()
        val ok = e.renderToWav(wav, 0, (3.4 * rate).toLong(), NativeEngine.WAV_PCM24)
        log("offline render with plugin ok=$ok in ${(System.nanoTime() - r0) / 1_000_000} ms -> $wav")
        val ps2 = e.pluginStats(tr)
        log("after render: late=${ps2[0]} blocks=${ps2[1]}")
        host.stopAll(); Thread.sleep(500); e.stop()
        return sb.toString()
    }
}
