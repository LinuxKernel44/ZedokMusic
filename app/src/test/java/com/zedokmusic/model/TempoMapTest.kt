package com.zedokmusic.model

import org.junit.Assert.*
import org.junit.Test

class TempoMapTest {
    @Test fun constantTempoIsTheOldFormula() {
        val m = TempoMap(120f)
        assertTrue(m.isConstant)
        assertEquals(24000.0, m.frames(1f), 1e-6); assertEquals(96000.0, m.frames(4f), 1e-6)
        assertEquals(4.0, m.beat(96000.0), 1e-9)
        val slow = TempoMap(90f); assertEquals(48000.0 * 60.0 / 90.0 * 3.0, slow.frames(3f), 1e-6)
    }
    @Test fun aTempoChangeBendsTheFrames() {
        val m = TempoMap(120f, listOf(TempoPoint(4f, 60f)))   // 4 beats at 120 = 2 s, then 60 BPM = 1 s per beat
        assertEquals(96000.0, m.frames(4f), 1e-6); assertEquals(96000.0 + 48000.0 * 2, m.frames(6f), 1e-6)
        assertEquals(6.0, m.beat(96000.0 + 96000.0), 1e-9)
        assertEquals(120f, m.bpmAt(3.9f), 0f); assertEquals(60f, m.bpmAt(4f), 0f); assertEquals(60f, m.bpmAt(100f), 0f)
    }
    @Test fun framesAndBeatsAreInverse() {
        val m = TempoMap(128f, listOf(TempoPoint(8f, 90f), TempoPoint(16f, 170f), TempoPoint(40f, 100f)))
        for (b in listOf(0f, 0.5f, 7.99f, 8f, 12.3f, 16f, 39f, 40f, 77.7f)) assertEquals(b.toDouble(), m.beat(m.frames(b)), 1e-6)
        assertEquals(m.frames(20f), m.frames(8f) + (m.frames(20f) - m.frames(8f)), 1e-9)
        var prev = -1.0; for (i in 0..200) { val f = m.frames(i * 0.5f); assertTrue(f > prev); prev = f }   // strictly increasing
    }
    @Test fun changesAreSortedAndAPointAtZeroIsIgnored() {
        val m = TempoMap(100f, listOf(TempoPoint(8f, 50f), TempoPoint(4f, 200f), TempoPoint(0f, 999f), TempoPoint(8f, 80f)))
        assertEquals(3, m.segments); assertEquals(100f, m.bpmAt(0f), 0f); assertEquals(200f, m.bpmAt(5f), 0f); assertEquals(80f, m.bpmAt(9f), 0f)   // the later point at beat 8 wins
    }
    @Test fun clipLengthFollowsTheTempoItCrosses() {
        val m = TempoMap(120f, listOf(TempoPoint(4f, 60f)))
        assertEquals(2f, m.beatsFor(0f, 48000), 1e-5f)       // 1 s at 120 BPM = 2 beats
        assertEquals(1f, m.beatsFor(4f, 48000), 1e-5f)       // 1 s at 60 BPM = 1 beat
        assertEquals(2f + 1f, m.beatsFor(2f, 48000 * 2), 1e-5f)   // starts at beat 2 (1 s before the change), lasts 2 s: 2 beats at 120 then... 1 s at 60
    }
    @Test fun tempoIsClamped() { assertEquals(400f, TempoMap(9999f).bpmAt(0f), 0f); assertEquals(20f, TempoMap(1f).bpmAt(0f), 0f) }
}
