package com.zedokmusic.wine

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class SafeFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun deleteTreeNeverFollowsLinksToFolders() {
        val workspace = tmp.newFolder("workspace"); java.io.File(workspace, "Projects").mkdirs(); java.io.File(workspace, "Projects/song.zedok").writeText("precious")
        val prefix = tmp.newFolder("pfx"); java.io.File(prefix, "dosdevices").mkdirs(); java.io.File(prefix, "keep.txt").writeText("x")
        Files.createSymbolicLink(java.io.File(prefix, "dosdevices/d:").toPath(), workspace.toPath())  // like Wine's drive D: -> the user's folder
        assertTrue(prefix.deleteTree())
        assertFalse(prefix.exists())
        assertTrue(java.io.File(workspace, "Projects/song.zedok").exists())
        assertEquals("precious", java.io.File(workspace, "Projects/song.zedok").readText())
    }

    @Test fun deleteTreeRemovesNormalTreesAndMissingPaths() {
        val d = tmp.newFolder("a"); java.io.File(d, "x/y").mkdirs(); java.io.File(d, "x/y/f").writeText("1")
        assertTrue(d.deleteTree()); assertFalse(d.exists())
        assertTrue(d.deleteTree())
    }

    @Test fun deleteTreeRemovesABrokenLink() {
        val prefix = tmp.newFolder("p"); Files.createSymbolicLink(java.io.File(prefix, "l").toPath(), java.io.File("/nonexistent/zz").toPath())
        assertTrue(prefix.deleteTree()); assertFalse(prefix.exists())
    }
}
