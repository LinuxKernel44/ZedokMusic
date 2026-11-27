package com.zedokmusic.model

import org.junit.Assert.*
import org.junit.Test

class PatternMathTest {
    private fun n(p: Int, s: Float, l: Float = 1f) = NoteM(p, s, l, 100)

    @Test fun lengthFollowsTheNotesInWholeBars() {
        assertEquals(4f, PatternMath.length(emptyList(), null), 0f)
        assertEquals(4f, PatternMath.length(listOf(n(60, 0f, 4f)), null), 0f)
        assertEquals(8f, PatternMath.length(listOf(n(60, 3.5f, 1f)), null), 0f)
        assertEquals(16f, PatternMath.length(listOf(n(60, 0f, 1f)), 16f), 0f)   // a fixed length wins
        assertEquals(4f, PatternMath.length(listOf(n(60, 0f, 4f)), 0f), 0f)    // 0 = not fixed
    }
    @Test fun aPlacementShiftsTheNotes() {
        val r = PatternMath.place(listOf(n(60, 0f), n(64, 2f)), 4f, 16f, 4f)
        assertEquals(listOf(16f, 18f), r.map { it.start }); assertEquals(listOf(60, 64), r.map { it.pitch })
    }
    @Test fun aLongerClipRepeatsThePattern() {
        val r = PatternMath.place(listOf(n(60, 0f), n(64, 2f)), 4f, 8f, 12f)
        assertEquals(listOf(8f, 10f, 12f, 14f, 16f, 18f), r.map { it.start })
    }
    @Test fun aShorterClipCutsThePattern() {
        val r = PatternMath.place(listOf(n(60, 0f, 4f), n(64, 3f)), 4f, 0f, 2f)
        assertEquals(1, r.size); assertEquals(2f, r[0].length, 1e-6f)   // the long note is cut at the clip end, the note at beat 3 is gone
    }
    @Test fun aNoteCrossingTheEndOfTheClipIsShortened() {
        val r = PatternMath.place(listOf(n(60, 3f, 2f)), 4f, 0f, 8f)
        assertEquals(listOf(3f, 7f), r.map { it.start }); assertEquals(listOf(2f, 1f), r.map { it.length })
    }
    @Test fun propertiesTravelWithTheNotes() {
        val r = PatternMath.place(listOf(NoteM(60, 0f, 1f, 90, pan = 40, slide = true)), 4f, 4f, 4f)
        assertEquals(40, r[0].pan); assertTrue(r[0].slide); assertEquals(90, r[0].velocity)
    }
    @Test fun emptyOrDegenerateInputGivesNothing() {
        assertTrue(PatternMath.place(emptyList(), 4f, 0f, 8f).isEmpty())
        assertTrue(PatternMath.place(listOf(n(60, 0f)), 0f, 0f, 8f).isEmpty())
        assertTrue(PatternMath.place(listOf(n(60, 0f)), 4f, 0f, 0f).isEmpty())
    }
}
