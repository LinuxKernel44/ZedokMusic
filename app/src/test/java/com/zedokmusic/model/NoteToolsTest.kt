package com.zedokmusic.model

import org.junit.Assert.*
import org.junit.Test
import com.zedokmusic.model.NoteTools.ArpPattern
import com.zedokmusic.model.NoteTools.QuantTarget

class NoteToolsTest {
    private fun n(p: Int, s: Float, l: Float = 1f, v: Int = 100) = NoteM(p, s, l, v)

    @Test fun quantizeFullStrengthLandsOnTheGridKeepingLength() {
        val r = NoteTools.quantize(listOf(n(60, 0.93f, 0.8f), n(62, 2.2f, 1f)), 0.5f)
        assertEquals(1.0f, r[0].start, 1e-6f); assertEquals(0.8f, r[0].length, 1e-6f); assertEquals(2.0f, r[1].start, 1e-6f)
    }
    @Test fun quantizeStrengthMovesPartWay() {
        val r = NoteTools.quantize(listOf(n(60, 1.2f)), 1f, strength = 0.5f)
        assertEquals(1.1f, r[0].start, 1e-5f)
    }
    @Test fun quantizeWindowLeavesFarNotesAlone() {
        val r = NoteTools.quantize(listOf(n(60, 0.45f), n(62, 0.05f)), 1f, window = 0.1f)
        assertEquals(0.45f, r[0].start, 1e-6f); assertEquals(0.0f, r[1].start, 1e-6f)
    }
    @Test fun quantizeSwingDelaysEveryOtherStep() {
        val r = NoteTools.quantize(listOf(n(60, 0f), n(60, 0.5f), n(60, 1f), n(60, 1.5f)), 0.5f, swing = 1f)
        assertEquals(0f, r[0].start, 1e-6f); assertEquals(0.75f, r[1].start, 1e-6f); assertEquals(1f, r[2].start, 1e-6f); assertEquals(1.75f, r[3].start, 1e-6f)
    }
    @Test fun quantizeEndsChangesTheLengthNotTheStart() {
        val r = NoteTools.quantize(listOf(n(60, 1f, 0.8f)), 1f, target = QuantTarget.Ends)
        assertEquals(1f, r[0].start, 1e-6f); assertEquals(1f, r[0].length, 1e-6f)
    }
    @Test fun humanizeIsRepeatableAndBounded() {
        val src = (0 until 20).map { n(60, it.toFloat(), 1f, 100) }
        val a = NoteTools.humanize(src, 0.05f, 10, 0.2f, 7); val b = NoteTools.humanize(src, 0.05f, 10, 0.2f, 7); val c = NoteTools.humanize(src, 0.05f, 10, 0.2f, 8)
        assertEquals(a.map { it.start }, b.map { it.start }); assertNotEquals(a.map { it.start }, c.map { it.start })
        for ((o, x) in src.zip(a)) { assertTrue(Math.abs(x.start - o.start) <= 0.05f + 1e-5f); assertTrue(Math.abs(x.velocity - o.velocity) <= 10); assertTrue(x.length in 0.79f..1.21f) }
        assertEquals(src.map { it.start }, NoteTools.humanize(src, 0f, 0, 0f, 3).map { it.start })
    }
    @Test fun chordsGroupNotesThatStartTogether() {
        val g = NoteTools.chords(listOf(n(64, 0f), n(60, 0f), n(67, 0.01f), n(62, 2f)))
        assertEquals(2, g.size); assertEquals(listOf(60, 64, 67), g[0].map { it.pitch }); assertEquals(1, g[1].size)
    }
    @Test fun strumSpreadsTheStartsAndKeepsTheEnds() {
        val r = NoteTools.strum(listOf(n(60, 0f, 4f), n(64, 0f, 4f), n(67, 0f, 4f)), 0.5f)
        assertEquals(listOf(0f, 0.25f, 0.5f), r.map { it.start }); assertEquals(listOf(4f, 3.75f, 3.5f), r.map { it.length })
        val d = NoteTools.strum(listOf(n(60, 0f, 4f), n(64, 0f, 4f), n(67, 0f, 4f)), 0.5f, fromTop = true)
        assertEquals(listOf(67, 64, 60), d.map { it.pitch })
    }
    @Test fun arpeggioUpWalksThroughThePitches() {
        val r = NoteTools.arpeggiate(listOf(n(60, 0f, 2f), n(64, 0f, 2f), n(67, 0f, 2f)), ArpPattern.Up, 0.5f)
        assertEquals(listOf(60, 64, 67, 60), r.map { it.pitch }); assertEquals(listOf(0f, 0.5f, 1f, 1.5f), r.map { it.start })
        assertTrue(r.all { Math.abs(it.length - 0.4f) < 1e-5f })
    }
    @Test fun arpeggioUpDownAndOctaves() {
        val ud = NoteTools.arpeggiate(listOf(n(60, 0f, 3f), n(64, 0f, 3f), n(67, 0f, 3f)), ArpPattern.UpDown, 0.5f).map { it.pitch }
        assertEquals(listOf(60, 64, 67, 64, 60, 64), ud)
        val oct = NoteTools.arpeggiate(listOf(n(60, 0f, 2f), n(67, 0f, 2f)), ArpPattern.Up, 0.5f, octaves = 2).map { it.pitch }
        assertEquals(listOf(60, 67, 72, 79), oct)
    }
    @Test fun chordNamesAndInversions() {
        assertEquals("C", NoteTools.chordName(listOf(60, 64, 67))); assertEquals("Am", NoteTools.chordName(listOf(57, 60, 64)))
        assertEquals("G7", NoteTools.chordName(listOf(55, 59, 62, 65))); assertEquals("Cmaj7", NoteTools.chordName(listOf(48, 55, 59, 64)))
        assertEquals("C/E", NoteTools.chordName(listOf(52, 55, 60))); assertNull(NoteTools.chordName(listOf(60, 61, 62, 63, 64)))
        assertNull(NoteTools.chordName(listOf(60)))
    }
    @Test fun stampBuildsAChordAndInverts() {
        val maj = NoteTools.CHORDS[0]; val min7 = NoteTools.CHORDS.first { it.suffix == "m7" }
        assertEquals(listOf(60, 64, 67), NoteTools.stamp(60, maj)); assertEquals(listOf(64, 67, 72), NoteTools.stamp(60, maj, 1))
        assertEquals(listOf(62, 65, 69, 72), NoteTools.stamp(62, min7)); assertEquals("Dm7", NoteTools.chordName(NoteTools.stamp(62, min7)))
        assertTrue(NoteTools.CHORDS.all { c -> NoteTools.chordName(NoteTools.stamp(60, c)) != null })
    }
    @Test fun riffStaysInTheScaleAndTheRange() {
        val scale = { p: Int -> Theory.inScale(p, 0, 2) }
        val a = NoteTools.riff(scale, 60, 84, 0f, 2, 0.5f, 0.7f, 0.3f, 5); val b = NoteTools.riff(scale, 60, 84, 0f, 2, 0.5f, 0.7f, 0.3f, 5)
        assertTrue(a.isNotEmpty()); assertEquals(a.map { it.pitch to it.start }, b.map { it.pitch to it.start })
        assertTrue(a.all { it.pitch in 60..84 && scale(it.pitch) && it.start >= 0f && it.start + it.length <= 8.01f })
        assertNotEquals(a.map { it.pitch }, NoteTools.riff(scale, 60, 84, 0f, 2, 0.5f, 0.7f, 0.3f, 6).map { it.pitch })
    }
    @Test fun lfoFollowsTheShapeAndStaysInRange() {
        val src = (0..8).map { n(60, it * 0.5f) }
        val sq = NoteTools.lfo(src, NoteTools.LfoShape.Square, 1f, 1f, 0f, -127, 127)
        assertEquals(127, sq[src[0]]); assertEquals(-127, sq[src[6]])
        val ramp = NoteTools.lfo(src, NoteTools.LfoShape.Ramp, 1f, 0.5f, 0f, -127, 127)
        assertTrue(ramp.values.all { it in -64..64 })
        val vel = NoteTools.lfo(src, NoteTools.LfoShape.Sine, 2f, 1f, 64f, 1, 127)
        assertTrue(vel.values.all { it in 1..127 })
    }
}
