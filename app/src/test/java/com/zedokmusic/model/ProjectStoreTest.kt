package com.zedokmusic.model

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store() = ProjectStore(tmp.newFolder("ws"))

    @Test fun sanitizeKeepsSafeCharacters() {
        val s = store()
        assertEquals("My Song_ 1", s.sanitize("  My Song? 1 "))
        assertEquals("a_b_c", s.sanitize("a/b\\c"))
        assertEquals("", s.sanitize("..."))
    }

    @Test fun uniqueNameAddsCounter() {
        val s = store()
        assertEquals("Song", s.uniqueName("Song"))
        s.create("Song"); s.file("Song").writeText("{}")
        assertEquals("Song (2)", s.uniqueName("Song"))
        s.create("Song (2)"); s.file("Song (2)").writeText("{}")
        assertEquals("Song (3)", s.uniqueName("Song"))
    }

    @Test fun defaultNameIsTheFirstFreeProjectNumber() {
        val s = store()
        assertEquals("Project_1", s.nextProjectName())
        s.create("Project_1"); s.file("Project_1").writeText("{}")
        assertEquals("Project_2", s.nextProjectName())
        s.create("Project_3"); s.file("Project_3").writeText("{}")      // a gap is filled before going past the highest
        assertEquals("Project_2", s.nextProjectName())
        s.create("Project_2"); s.file("Project_2").writeText("{}")
        assertEquals("Project_4", s.nextProjectName())
    }

    @Test fun nextVersionNameIncrements() {
        val s = store()
        assertEquals("Song_2", s.nextVersionName("Song"))
        assertEquals("Song_3", s.nextVersionName("Song_2"))
        s.create("Song_2"); s.file("Song_2").writeText("{}")
        assertEquals("Song_3", s.nextVersionName("Song"))
    }

    @Test fun legacyProjectFileMovesIntoItsFolder() {
        val s = store()
        s.root.mkdirs()
        java.io.File(s.root, "Untitled.zedok").writeText("{\"a\":1}")
        java.io.File(s.root, "Untitled/Samples").mkdirs()
        s.migrateLegacy()
        assertFalse(java.io.File(s.root, "Untitled.zedok").exists())
        assertEquals("{\"a\":1}", s.file("Untitled").readText())
        assertTrue(java.io.File(s.root, "Untitled/Samples").isDirectory)
        s.migrateLegacy()  // idempotent
        assertEquals(1, s.list().size)
    }

    @Test fun listIsNewestFirst() {
        val s = store()
        for ((n, t) in listOf("A" to 1000L, "B" to 3000L, "C" to 2000L)) { s.create(n); s.file(n).writeText("{}"); s.file(n).setLastModified(t) }
        assertEquals(listOf("B", "C", "A"), s.list().map { it.name })
    }

    @Test fun backupIsRateLimitedAndPruned() {
        val s = store(); s.create("P"); s.file("P").writeText("v1")
        val t0 = 1_700_000_000_000L
        assertNotNull(s.backup("P", now = t0, minGapMs = 1000, keep = 3))
        assertNull(s.backup("P", now = t0 + 500, minGapMs = 1000, keep = 3))       // too soon
        for (i in 1..5) assertNotNull(s.backup("P", now = t0 + i * 2000L, minGapMs = 1000, keep = 3))
        val left = s.listBackups("P")
        assertEquals(3, left.size)
        assertEquals(t0 + 10_000L, left.first().lastModified())                      // newest kept
    }
}
