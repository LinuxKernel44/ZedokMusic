package com.zedokmusic.model

import org.junit.Assert.*
import org.junit.Test

class HistoryTest {
    private var now = 0L
    private fun history(limit: Int = 100) = History(limit) { now }

    /** A value with edits that record themselves the way the editor's setters do. */
    private class Cell(val h: History, var v: Int = 0) {
        fun set(n: Int, name: String = "Set", key: String? = null) { val o = v; v = n; h.record(name, key, { v = o }, { v = n }) }
    }

    @Test fun undoAndRedoWalkTheStack() {
        val h = history(); val c = Cell(h)
        c.set(1); c.set(2); c.set(3)
        assertEquals("Set", h.undo()); assertEquals(2, c.v)
        h.undo(); assertEquals(1, c.v)
        h.redo(); assertEquals(2, c.v)
        h.undo(); h.undo(); assertEquals(0, c.v); assertFalse(h.canUndo); assertNull(h.undo())
        h.redo(); h.redo(); h.redo(); assertEquals(3, c.v); assertFalse(h.canRedo); assertNull(h.redo())
    }

    @Test fun aNewEditClearsTheRedoStack() {
        val h = history(); val c = Cell(h)
        c.set(1); c.set(2); h.undo(); assertTrue(h.canRedo)
        c.set(9); assertFalse(h.canRedo); assertEquals(9, c.v)
        h.undo(); assertEquals(1, c.v)
    }

    @Test fun editsMadeByAnUndoAreNotRecorded() {
        val h = history(); val c = Cell(h)
        c.set(1)
        val before = h.size
        // the undo action itself calls a recording setter, like the real undo of a clip move does
        h.record("Weird", undo = { c.set(0, "inner") }, redo = { c.set(1, "inner") })
        h.undo(); assertEquals(before, h.size)
        assertEquals("Set", h.undoName)
    }

    @Test fun aDragIsOneEntryWhenTheKeyAndTimeMatch() {
        val h = history(); val c = Cell(h, 100)
        for (i in 1..30) { now += 20; c.set(100 - i, "Volume", "gain") }
        assertEquals(1, h.size)
        h.undo(); assertEquals(100, c.v)
        h.redo(); assertEquals(70, c.v)
    }

    @Test fun aPauseOrAnotherKeyStartsANewEntry() {
        val h = history(); val c = Cell(h)
        c.set(1, "Volume", "gain"); now += 100; c.set(2, "Volume", "gain")
        assertEquals(1, h.size)
        now += History.COALESCE_MS + 1; c.set(3, "Volume", "gain")
        assertEquals(2, h.size)
        c.set(4, "Pan", "pan"); assertEquals(3, h.size)
        c.set(5, "Pan", "pan"); assertEquals(3, h.size)
    }

    @Test fun editsWithoutAKeyNeverMerge() {
        val h = history(); val c = Cell(h)
        c.set(1); c.set(2); c.set(3); assertEquals(3, h.size)
    }

    @Test fun theOldestEntriesAreDropped() {
        val h = history(limit = 3); val c = Cell(h)
        for (i in 1..5) c.set(i)
        assertEquals(3, h.size)
        h.undo(); h.undo(); h.undo(); assertEquals(2, c.v); assertFalse(h.canUndo)
    }

    @Test fun namesFollowTheStacks() {
        val h = history(); val c = Cell(h)
        c.set(1, "Move notes"); c.set(2, "Delete clips")
        assertEquals("Delete clips", h.undoName); assertNull(h.redoName)
        h.undo(); assertEquals("Move notes", h.undoName); assertEquals("Delete clips", h.redoName)
        h.clear(); assertFalse(h.canUndo); assertFalse(h.canRedo)
    }
}
