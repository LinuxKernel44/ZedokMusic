package com.zedokmusic.model

/**
 * The project-wide undo / redo stack: one linear list of named actions, each with the code that takes it back and the code that
 * does it again. Pure Kotlin (no Android) so it is unit-tested.
 *
 * - [record] is called by an edit *after* it has been applied; it never runs the actions itself.
 * - While an undo or redo runs, [record] is ignored, so the setters the actions call do not write new history.
 * - A burst of edits with the same [key] (a fader dragged, a slider moved) within [COALESCE_MS] of each other is one entry: the
 *   undo of the first and the redo of the last are kept.
 * - The oldest entries are dropped beyond [limit].
 */
class History(private val limit: Int = 100, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private class Entry(val name: String, val key: String?, val undo: () -> Unit, var redo: () -> Unit, var at: Long)

    private val undos = ArrayList<Entry>()
    private val redos = ArrayList<Entry>()
    private var applying = false

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()
    val undoName: String? get() = undos.lastOrNull()?.name
    val redoName: String? get() = redos.lastOrNull()?.name
    val size: Int get() = undos.size

    fun record(name: String, key: String? = null, undo: () -> Unit, redo: () -> Unit) {
        if (applying) return
        val now = clock()
        val top = undos.lastOrNull()
        if (key != null && top != null && top.key == key && redos.isEmpty() && now - top.at <= COALESCE_MS) { top.redo = redo; top.at = now; return }
        undos.add(Entry(name, key, undo, redo, now))
        if (undos.size > limit) undos.removeAt(0)
        redos.clear()
    }

    /** Takes the last action back; returns its name, or null when there is nothing to undo. */
    fun undo(): String? {
        val e = undos.removeLastOrNull() ?: return null
        run(e.undo); redos.add(e); return e.name
    }

    fun redo(): String? {
        val e = redos.removeLastOrNull() ?: return null
        run(e.redo); undos.add(e); return e.name
    }

    fun clear() { undos.clear(); redos.clear() }

    private fun run(f: () -> Unit) { applying = true; try { f() } finally { applying = false } }

    companion object { const val COALESCE_MS = 800L }
}
