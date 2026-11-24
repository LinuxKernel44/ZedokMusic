package com.zedokmusic.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private fun parse(vararg bytes: Int): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val p = MidiParser { s, a, b -> out.add(Triple(s, a, b)) }
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        p.feed(arr, 0, arr.size)
        return out
    }

    @Test fun noteOnAndOff() = assertEquals(listOf(Triple(0x90, 60, 100), Triple(0x80, 60, 0)), parse(0x90, 60, 100, 0x80, 60, 0))
    @Test fun runningStatus() = assertEquals(listOf(Triple(0x90, 60, 100), Triple(0x90, 64, 90), Triple(0x90, 67, 0)), parse(0x90, 60, 100, 64, 90, 67, 0))
    @Test fun realtimeInsideMessageIsIgnored() = assertEquals(listOf(Triple(0x90, 60, 100)), parse(0x90, 0xF8, 60, 0xFE, 100))
    @Test fun sysexIsSkippedAndClearsRunningStatus() =
        assertEquals(listOf(Triple(0x90, 60, 100), Triple(0x90, 62, 80)), parse(0x90, 60, 100, 0xF0, 0x7E, 0x00, 0x09, 0xF7, 0x90, 62, 80))
    @Test fun dataBytesAfterSysexWithoutStatusAreDropped() = assertEquals(emptyList<Triple<Int, Int, Int>>(), parse(0xF0, 1, 2, 3, 0xF7, 60, 100))
    @Test fun programChangeHasOneDataByte() = assertEquals(listOf(Triple(0xC0, 5, 0), Triple(0x90, 60, 100)), parse(0xC0, 5, 0x90, 60, 100))
    @Test fun channelPressureHasOneDataByte() = assertEquals(listOf(Triple(0xD3, 40, 0), Triple(0xD3, 41, 0)), parse(0xD3, 40, 41))
    @Test fun pitchBendAndControlChange() = assertEquals(listOf(Triple(0xE0, 0, 64), Triple(0xB0, 1, 127)), parse(0xE0, 0, 64, 0xB0, 1, 127))
    @Test fun messageSplitAcrossBuffers() {
        val out = ArrayList<Triple<Int, Int, Int>>(); val p = MidiParser { s, a, b -> out.add(Triple(s, a, b)) }
        p.feed(byteArrayOf(0x90.toByte(), 60), 0, 2); p.feed(byteArrayOf(100), 0, 1)
        assertEquals(listOf(Triple(0x90, 60, 100)), out)
    }
    @Test fun offsetAndCountAreRespected() {
        val out = ArrayList<Triple<Int, Int, Int>>(); val p = MidiParser { s, a, b -> out.add(Triple(s, a, b)) }
        p.feed(byteArrayOf(0, 0x90.toByte(), 60, 100, 0x7F), 1, 3)
        assertEquals(listOf(Triple(0x90, 60, 100)), out)
    }
}
