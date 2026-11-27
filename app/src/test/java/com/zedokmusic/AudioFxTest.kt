package com.zedokmusic

import com.zedokmusic.model.AudioFx
import com.zedokmusic.model.Pcm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioFxTest {
    private fun sine(freq: Double, sec: Double = 1.0, rate: Int = 48000) = Pcm(rate, arrayOf(FloatArray((rate * sec).toInt()) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }))
    /** Frequency estimated from the zero crossings of the middle half of the signal. */
    private fun freq(p: Pcm): Double {
        val d = p.data[0]; val a = d.size / 4; val b = d.size * 3 / 4; var z = 0
        for (i in a + 1 until b) if (d[i - 1] <= 0f && d[i] > 0f) z++
        return z / ((b - a).toDouble() / p.rate)
    }

    @Test fun resampleChangesLengthAndPitchTogether() {
        val o = AudioFx.process(sine(440.0), 0f, 12f, 1f, AudioFx.MODE_RESAMPLE)
        assertEquals(24000.0, o.frames.toDouble(), 50.0)
        assertEquals(880.0, freq(o), 15.0)
    }
    @Test fun stretchKeepsPitchWhenSpeedChanges() {
        val o = AudioFx.process(sine(440.0), 0f, 0f, 2f, AudioFx.MODE_STRETCH)
        assertEquals(24000.0, o.frames.toDouble(), 600.0)
        assertEquals(440.0, freq(o), 15.0)
    }
    @Test fun stretchKeepsLengthWhenPitchChanges() {
        val o = AudioFx.process(sine(440.0), 0f, 12f, 1f, AudioFx.MODE_STRETCH)
        assertEquals(48000.0, o.frames.toDouble(), 800.0)
        assertEquals(880.0, freq(o), 25.0)
    }
    @Test fun gainScalesTheSignal() {
        val o = AudioFx.process(sine(440.0), -6.0206f, 0f, 1f, AudioFx.MODE_RESAMPLE)
        assertTrue(abs(o.data[0].maxOrNull()!! - 0.25f) < 0.01f)
    }
    @Test fun wavRoundTrip() {
        val f = File.createTempFile("zedoktest", ".wav"); val p = sine(220.0, 0.2)
        AudioFx.writeWav(f, p); val r = AudioFx.readWav(f)!!
        assertEquals(p.frames, r.frames); assertEquals(48000, r.rate); assertTrue(abs(p.data[0][1000] - r.data[0][1000]) < 1e-6f); f.delete()
    }
    @Test fun headerDurationMatchesDecodedLength() {
        val f = File.createTempFile("zedoktest", ".wav"); val p = sine(220.0, 0.5)
        AudioFx.writeWav(f, p)
        assertEquals(p.frames.toFloat() / p.rate, AudioFx.wavSeconds(f), 1e-4f); f.delete()
    }
    @Test fun stretchKeepsTheEndOfTheSound() {
        val o = AudioFx.process(sine(440.0, 1.0), 0f, 0f, 2f, AudioFx.MODE_STRETCH)
        val tail = o.data[0].takeLast(o.frames / 10).maxOf { abs(it) }
        assertTrue("tail is silent ($tail)", tail > 0.1f)
    }
    @Test fun pitchDetection() {
        for (f in doubleArrayOf(110.0, 261.63, 440.0, 880.0)) {
            val hz = AudioFx.detectPitchHz(sine(f, 1.0))
            assertTrue("$f detected as $hz", abs(hz - f) < f * 0.01)
        }
        // an F with a loud second harmonic is still an F
        val p = sine(174.61, 1.0); val q = sine(349.22, 1.0); for (i in p.data[0].indices) p.data[0][i] += 0.6f * q.data[0][i]
        val hz = AudioFx.detectPitchHz(p); assertTrue("F with harmonic detected as $hz", abs(hz - 174.61) < 3.0)
    }
}
