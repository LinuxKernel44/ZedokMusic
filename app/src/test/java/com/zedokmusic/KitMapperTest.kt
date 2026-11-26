package com.zedokmusic

import com.zedokmusic.model.KitMapper
import com.zedokmusic.model.KitMapper.Entry
import org.junit.Assert.*
import org.junit.Test

class KitMapperTest {
    private fun plan(vararg p: String) = KitMapper.plan("t", p.map { Entry(it) })
    private fun padOf(pl: KitMapper.Plan, file: String) = pl.pads.indexOfFirst { z -> z.any { it.entry.path == file } }

    @Test fun classifiesCommonNames() {
        assertEquals(KitMapper.Role.Kick, KitMapper.roleOf("Kick 01.wav"))
        assertEquals(KitMapper.Role.Kick, KitMapper.roleOf("BD_808.wav"))
        assertEquals(KitMapper.Role.Snare, KitMapper.roleOf("Snares/snare_tight.wav"))
        assertEquals(KitMapper.Role.Clap, KitMapper.roleOf("clap.wav"))
        assertEquals(KitMapper.Role.HatOpen, KitMapper.roleOf("Open Hat 2.wav"))
        assertEquals(KitMapper.Role.HatClosed, KitMapper.roleOf("Closed_HiHat.wav"))
        assertEquals(KitMapper.Role.HatOpen, KitMapper.roleOf("OH_01.wav"))
        assertEquals(KitMapper.Role.Rim, KitMapper.roleOf("rimshot.wav"))
        assertEquals(KitMapper.Role.Cymbal, KitMapper.roleOf("Crash.wav"))
        assertEquals(KitMapper.Role.TomHigh, KitMapper.roleOf("tom high.wav"))
        assertEquals(KitMapper.Role.Other, KitMapper.roleOf("vox chop.wav"))
    }

    @Test fun oneShotKitLandsOnTheStandardPads() {
        val p = plan("Kick.wav", "Snare.wav", "Clap.wav", "ClosedHat.wav", "OpenHat.wav", "Crash.wav", "Tom Low.wav", "Tom High.wav")
        assertEquals(0, padOf(p, "Kick.wav")); assertEquals(3, padOf(p, "Snare.wav")); assertEquals(4, padOf(p, "Clap.wav"))
        assertEquals(6, padOf(p, "ClosedHat.wav")); assertEquals(10, padOf(p, "OpenHat.wav")); assertEquals(12, padOf(p, "Crash.wav"))
        assertEquals(5, padOf(p, "Tom Low.wav")); assertEquals(9, padOf(p, "Tom High.wav"))
        assertTrue(p.dropped.isEmpty())
    }

    @Test fun twoKicksUseTwoPads() {
        val p = plan("Kick A.wav", "Kick B.wav")
        assertEquals(setOf(0, 1), listOf(padOf(p, "Kick A.wav"), padOf(p, "Kick B.wav")).toSet())
    }

    @Test fun velocityLayersShareAPad() {
        val p = plan("snare_v1.wav", "snare_v2.wav", "snare_v3.wav")
        val z = p.pads[3]; assertEquals(3, z.size)
        assertEquals(1, z[0].vlo); assertEquals(127, z[2].vhi)
        assertTrue(z[0].vhi < z[1].vlo + 1 && z[1].vhi < z[2].vlo + 1)   // bands follow each other
    }

    @Test fun namedVelocityLayers() {
        val z = plan("Kick soft.wav", "Kick hard.wav").pads[0]
        assertEquals(2, z.size); assertEquals("Kick soft.wav", z[0].entry.path); assertEquals("Kick hard.wav", z[1].entry.path)
        assertEquals(127, z[1].vhi)
    }

    @Test fun roundRobinsStayInOneZoneRange() {
        val z = plan("hat_rr1.wav", "hat_rr2.wav", "hat_rr3.wav").pads[6]
        assertEquals(3, z.size); assertTrue(z.all { it.vlo == 1 && it.vhi == 127 })
    }

    @Test fun trailingNumbersAreRoundRobins() {
        val z = plan("Kick 1.wav", "Kick 2.wav", "Kick 3.wav").pads[0]
        assertEquals(3, z.size)
    }

    @Test fun extrasGoToFreePadsAndOverflowIsReported() {
        val files = (1..20).map { "perc_$it name.wav" }
        val p = plan(*files.toTypedArray())
        assertEquals(16, p.padsUsed); assertTrue(p.dropped.isNotEmpty())
    }

    @Test fun parsesSfz() {
        val r = KitMapper.parseSfz("""
            <control> default_path=Samples/
            <group> lovel=1 hivel=127
            <region> sample=kick.wav key=36
            <region> sample=snare_soft.wav key=38 lovel=1 hivel=60
            <region> sample=snare_hard.wav key=38 lovel=61 hivel=127
            // a comment
            <region> sample=hat.wav lokey=42 hikey=42
        """.trimIndent())
        assertEquals(4, r.size); assertEquals("Samples/kick.wav", r[0].sample); assertEquals(36, r[0].lokey)
        assertEquals(60, r[1].hivel); assertEquals(61, r[2].lovel); assertEquals(42, r[3].lokey)
        val p = KitMapper.planFromSfz("sfz", r)
        assertEquals(1, p.pads[0].size); assertEquals(2, p.pads[2].size); assertEquals(1, p.pads[6].size)
    }

    @Test fun zoneTextRoundTrips() {
        val z = listOf(KitMapper.Zone(Entry("Kits/a/kick.wav"), 1, 70), KitMapper.Zone(Entry("Kits/a/kick2.wav"), 71, 127))
        val back = KitMapper.parseZoneText(KitMapper.zoneText(z))
        assertEquals(z, back)
    }

    @Test fun padLabelsDropLayerMarkers() {
        assertEquals("Snare", KitMapper.label(Entry("Kits/a/Snare_v1.wav")))
        assertEquals("Kick", KitMapper.label(Entry("Kick 03.wav")))
        assertEquals("Open Hat", KitMapper.label(Entry("Open Hat.wav")))
        assertEquals("hat", KitMapper.label(Entry("hat_rr2.wav")))
    }
}
