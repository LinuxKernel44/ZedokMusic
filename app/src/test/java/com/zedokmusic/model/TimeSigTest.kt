package com.zedokmusic.model

import org.junit.Assert.*
import org.junit.Test

class TimeSigTest {
    @Test fun fourFourIsEveryFourBeats() {
        val b = TimeSig.barStarts(emptyList(), 16f)
        assertEquals(listOf(0f, 4f, 8f, 12f, 16f), b.map { it.first }); assertEquals(listOf(1, 2, 3, 4, 5), b.map { it.second })
    }
    @Test fun aChangeShortensTheBarsFromItsLine() {
        val b = TimeSig.barStarts(listOf(SigPoint(8f, 3, 4)), 17f)   // 4/4 until beat 8, then 3/4 (3-beat bars)
        assertEquals(listOf(0f, 4f, 8f, 11f, 14f, 17f), b.map { it.first }); assertEquals(listOf(1, 2, 3, 4, 5, 6), b.map { it.second })
    }
    @Test fun sixEightIsThreeBeatsPerBar() {
        assertEquals(listOf(0f, 3f, 6f), TimeSig.barStarts(listOf(SigPoint(0.5f, 6, 8)), 6f).map { it.first }.take(1) + listOf(3f, 6f))
        val b = TimeSig.barStarts(listOf(SigPoint(4f, 6, 8)), 10f); assertEquals(listOf(0f, 4f, 7f, 10f), b.map { it.first })
    }
    @Test fun theSignatureAtABeat() {
        val ch = listOf(SigPoint(8f, 3, 4), SigPoint(20f, 7, 8))
        assertEquals(4 to 4, TimeSig.at(ch, 7.9f)); assertEquals(3 to 4, TimeSig.at(ch, 8f)); assertEquals(7 to 8, TimeSig.at(ch, 99f))
    }
}
