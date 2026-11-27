package com.zedokmusic.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** A decoded WAV: [data] has one FloatArray per channel. */
class Pcm(val rate: Int, val data: Array<FloatArray>) {
    val frames get() = data.firstOrNull()?.size ?: 0
    val channels get() = data.size
}

object AudioFx {
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)

    /** Reads PCM 16 / 24 / 32 and float32 WAVs (mono or stereo, or more: the first two channels). Null when it cannot be read. */
    fun readWav(f: File): Pcm? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val head = ByteArray(12); r.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var tag = 0; var ch = 0; var rate = 0; var bits = 0
            while (true) {
                val h = ByteArray(8); if (r.read(h) < 8) return null
                val id = String(h, 0, 4); val len = le32(h, 4).toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val b = ByteArray(len.toInt().coerceAtMost(40)); r.readFully(b)
                    tag = le16(b, 0); ch = le16(b, 2); rate = le32(b, 4); bits = le16(b, 14)
                    if (tag == 0xFFFE && b.size >= 26) tag = le16(b, 24)
                    if (len > b.size) r.seek(r.filePointer + len - b.size)
                    if (len and 1L == 1L) r.seek(r.filePointer + 1)
                } else if (id == "data") {
                    if (ch < 1 || rate <= 0) return null
                    val bytes = min(len, r.length() - r.filePointer).toInt()
                    val bps = bits / 8; val frames = bytes / (bps * ch)
                    val nch = min(ch, 2); val out = Array(nch) { FloatArray(frames) }
                    val buf = ByteArray(65536 - 65536 % (bps * ch)); var done = 0
                    while (done < frames) {
                        val want = min(buf.size / (bps * ch), frames - done)
                        r.readFully(buf, 0, want * bps * ch)
                        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until want) for (c in 0 until ch) {
                            val o = (i * ch + c) * bps
                            val v = when {
                                tag == 3 && bits == 32 -> bb.getFloat(o)
                                tag == 1 && bits == 16 -> bb.getShort(o) / 32768f
                                tag == 1 && bits == 24 -> ((((buf[o + 2].toInt()) shl 16) or ((buf[o + 1].toInt() and 255) shl 8) or (buf[o].toInt() and 255)) / 8388608f)
                                tag == 1 && bits == 32 -> bb.getInt(o) / 2147483648f
                                else -> return null
                            }
                            if (c < nch) out[c][done + i] = v
                        }
                        done += want
                    }
                    return Pcm(rate, out)
                } else r.seek(r.filePointer + len + (len and 1L))
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }.getOrNull()

    /** Writes 32-bit float WAV. */
    fun writeWav(f: File, p: Pcm) {
        f.parentFile?.mkdirs()
        val n = p.frames; val ch = p.channels; val dataBytes = n * ch * 4
        f.outputStream().buffered(1 shl 16).use { o ->
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(3).putShort(ch.toShort()).putInt(p.rate).putInt(p.rate * ch * 4).putShort((ch * 4).toShort()).putShort(32)
            h.put("data".toByteArray()).putInt(dataBytes); o.write(h.array())
            val chunk = ByteBuffer.allocate(1 shl 14).order(ByteOrder.LITTLE_ENDIAN); var i = 0
            while (i < n) {
                chunk.clear()
                while (i < n && chunk.remaining() >= ch * 4) { for (c in 0 until ch) chunk.putFloat(p.data[c][i].coerceIn(-1f, 1f)); i++ }
                o.write(chunk.array(), 0, chunk.position())
            }
        }
    }

    // ------------------------------------------------------------------ waveform peaks (cached on disk)
    const val PEAK_FRAMES = 256   // source frames per stored bucket

    /** Duration in seconds, read from the header only (no decoding). */
    fun wavSeconds(f: File): Float = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val head = ByteArray(12); r.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return 0f
            var ch = 0; var rate = 0; var bits = 0
            while (true) {
                val h = ByteArray(8); if (r.read(h) < 8) return 0f
                val id = String(h, 0, 4); val len = le32(h, 4).toLong() and 0xFFFFFFFFL
                if (id == "fmt ") {
                    val b = ByteArray(len.toInt().coerceAtMost(40)); r.readFully(b)
                    ch = le16(b, 2); rate = le32(b, 4); bits = le16(b, 14)
                    if (len > b.size) r.seek(r.filePointer + len - b.size)
                    if (len and 1L == 1L) r.seek(r.filePointer + 1)
                } else if (id == "data") {
                    if (ch < 1 || rate <= 0 || bits < 8) return 0f
                    val bytes = min(len, r.length() - r.filePointer)
                    return (bytes / (bits / 8 * ch)).toFloat() / rate
                } else r.seek(r.filePointer + len + (len and 1L))
            }
            0f
        }
    }.getOrDefault(0f)

    /** min / max per bucket of [PEAK_FRAMES] frames, as bytes (-127..127), interleaved [min0, max0, min1, max1...]. Cached next to the project in [cacheDir]. */
    fun peaks(wav: File, cacheDir: File?): ByteArray? {
        val key = wav.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + "." + wav.length() + "." + wav.lastModified() + ".peaks"
        val cf = cacheDir?.let { File(it, key) }
        if (cf != null && cf.isFile) runCatching { return cf.readBytes() }
        val pcm = readWav(wav) ?: return null
        val nb = (pcm.frames + PEAK_FRAMES - 1) / PEAK_FRAMES
        val out = ByteArray(nb * 2)
        for (b in 0 until nb) {
            var lo = 0f; var hi = 0f
            val s = b * PEAK_FRAMES; val e = min(pcm.frames, s + PEAK_FRAMES)
            for (c in 0 until pcm.channels) { val d = pcm.data[c]; for (i in s until e) { val v = d[i]; if (v < lo) lo = v; if (v > hi) hi = v } }
            out[b * 2] = (lo.coerceIn(-1f, 0f) * 127f).roundToInt().toByte(); out[b * 2 + 1] = (hi.coerceIn(0f, 1f) * 127f).roundToInt().toByte()
        }
        if (cf != null) runCatching { cf.parentFile?.mkdirs(); cf.writeBytes(out) }
        return out
    }

    // ------------------------------------------------------------------ clip processing
    /** Mode of the time / pitch change. */
    const val MODE_RESAMPLE = 0   // speed and pitch change together (like turning a record faster)
    const val MODE_STRETCH = 1    // time and pitch are independent (WSOLA time stretching)

    /**
     * Renders [src] with gain, pitch and speed changes. [speed] > 1 plays faster (shorter). [semis] shifts the pitch. In MODE_RESAMPLE the two
     * simply combine into one playback rate; in MODE_STRETCH the duration follows [speed] only and the pitch follows [semis] only.
     */
    fun process(src: Pcm, gainDb: Float, semis: Float, speed: Float, mode: Int): Pcm {
        val g = 10f.pow(gainDb / 20f); val pr = 2f.pow(semis / 12f)
        var out: Array<FloatArray>
        if (mode == MODE_RESAMPLE || (abs(speed - 1f) < 1e-4f && abs(semis) < 1e-4f)) {
            val rate = (speed * pr).toDouble()
            out = Array(src.channels) { resample(src.data[it], rate) }
        } else {
            // time-stretch by (pr / speed), then resample by pr: duration = N / speed, pitch = x pr
            val stretchOut = pr / speed   // length factor of the stretch (output / input)
            val stretched = wsola(src, stretchOut.toDouble())
            out = if (abs(pr - 1f) < 1e-4f) stretched else Array(stretched.size) { resample(stretched[it], pr.toDouble()) }
        }
        if (abs(g - 1f) > 1e-5f) for (c in out) for (i in c.indices) c[i] *= g
        return Pcm(src.rate, out)
    }

    /** Playback at [rate] x (Hermite interpolation): output length = input / rate. */
    private fun resample(x: FloatArray, rate: Double): FloatArray {
        val n = max(1, (x.size / rate).toInt()); val out = FloatArray(n)
        fun at(i: Int) = if (i < 0) x[0] else if (i >= x.size) 0f else x[i]
        for (k in 0 until n) {
            val p = k * rate; val i = p.toInt(); val f = (p - i).toFloat()
            val y0 = at(i - 1); val y1 = at(i); val y2 = at(i + 1); val y3 = at(i + 2)
            val c0 = y1; val c1 = 0.5f * (y2 - y0); val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3; val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
            out[k] = ((c3 * f + c2) * f + c1) * f + c0
        }
        return out
    }

    /** Waveform-similarity overlap-add time stretch: output length = input * [factor], pitch unchanged. */
    private fun wsola(src: Pcm, factor: Double): Array<FloatArray> {
        val n = src.frames; val ch = src.channels
        if (n < 4096 || abs(factor - 1.0) < 1e-4) return Array(ch) { c -> if (abs(factor - 1.0) < 1e-4) src.data[c].copyOf() else resample(src.data[c], 1.0 / factor) }
        val frame = if (src.rate >= 44100) 2048 else 1024; val hop = frame / 4; val seek = frame / 4
        val outLen = (n * factor).toInt(); val out = Array(ch) { FloatArray(outLen + frame * 2) }; val norm = FloatArray(outLen + frame * 2)
        val win = FloatArray(frame) { (0.5 - 0.5 * cos(2.0 * PI * (it + 0.5) / frame)).toFloat() }
        val mono = FloatArray(n) { i -> var s = 0f; for (c in 0 until ch) s += src.data[c][i]; s / ch }
        var prevPos = 0; var outPos = 0
        // the first frame is copied as it is
        for (c in 0 until ch) for (i in 0 until frame) if (i < n) { out[c][i] += src.data[c][i] * win[i] }
        for (i in 0 until frame) norm[i] += win[i]
        while (true) {
            outPos += hop
            if (outPos >= outLen) break
            val ideal = (outPos / factor).toInt()
            val nat = prevPos + hop                       // where the previous frame would naturally continue
            var best = ideal; var bestC = -1e30
            if (n - frame - 1 < 0) break
            val lo = max(0, min(ideal - seek, n - frame - 1)); val hi = max(lo, min(n - frame - 1, ideal + seek))
            var cand = lo
            // coarse-to-fine search (step 4 then refine) on the correlation with the natural continuation
            val ref0 = min(nat, n - frame - 1).coerceAtLeast(0)
            while (cand <= hi) {
                var cc = 0.0; var i = 0
                while (i < frame) { cc += mono[cand + i] * mono[ref0 + i]; i += 4 }
                if (cc > bestC) { bestC = cc; best = cand }
                cand += 4
            }
            for (cand2 in max(lo, best - 3)..min(hi, best + 3)) {
                var cc = 0.0; var i = 0
                while (i < frame) { cc += mono[cand2 + i] * mono[ref0 + i]; i += 1 + (i and 1) }
                if (cc > bestC) { bestC = cc; best = cand2 }
            }
            for (c in 0 until ch) { val d = src.data[c]; val o = out[c]; for (i in 0 until frame) o[outPos + i] += d[best + i] * win[i] }
            for (i in 0 until frame) norm[outPos + i] += win[i]
            prevPos = best
        }
        return Array(ch) { c -> FloatArray(outLen) { i -> val w = norm[i]; if (w > 1e-3f) out[c][i] / w else 0f } }
    }
}
