package com.zedokmusic.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A pattern: a block of notes for every instrument track, that the playlist can place any number of times (every placement plays the same notes:
 * edit the pattern and all of them change). The notes of the pattern being edited live in [TrackM.notes]; the other patterns keep theirs in [stored]
 * (track id -> notes), see DawViewModel.setCurrentPattern.
 */
class PatternM(val id: Int, name: String, color: Color) {
    var name by mutableStateOf(name)
    var color by mutableStateOf(color)
    /** Length in beats chosen by the user, or null: follows the notes (rounded up to whole bars, at least one bar). */
    var fixedLength by mutableStateOf<Float?>(null)
    val stored = HashMap<Int, List<NoteM>>()
}

/** A placement of a pattern in the playlist. [length] null = the pattern's own length; a longer clip repeats the pattern, a shorter one cuts it. */
class PatternClipM(val pattern: PatternM, start: Float, length: Float? = null, row: Int = 0) {
    var start by mutableStateOf(start)
    var length by mutableStateOf(length)
    var row by mutableStateOf(row)
}

object PatternMath {
    /** The length of a pattern whose notes are [all]: [fixed] when set, else the end of the last note rounded up to whole bars (min 4 beats). */
    fun length(all: Collection<NoteM>, fixed: Float?): Float {
        if (fixed != null && fixed > 0f) return fixed
        var end = 0f; for (n in all) end = max(end, n.start + n.length)
        return max(4f, ceil(end / 4f - 1e-4f) * 4f)
    }

    /**
     * The notes one placement of a pattern puts on the timeline: [notes] (pattern time) repeated every [patternLen] beats up to [clipLen] beats, shifted
     * to [clipStart], and cut where the clip ends. Notes that start after the pattern's end (never inside a repeat) are dropped.
     */
    fun place(notes: List<NoteM>, patternLen: Float, clipStart: Float, clipLen: Float): List<NoteM> {
        if (patternLen <= 0f || clipLen <= 0f) return emptyList()
        val out = ArrayList<NoteM>(); var rep = 0
        while (rep * patternLen < clipLen - 1e-4f) {
            val off = rep * patternLen
            for (n in notes) {
                if (n.start >= patternLen - 1e-4f && !(rep == 0 && n.start < clipLen)) continue
                val s = off + n.start; if (s >= clipLen - 1e-4f) continue
                val len = min(n.length, clipLen - s)
                if (len <= 0f) continue
                out.add(n.copy(start = clipStart + s, length = len))
            }
            rep++
        }
        return out
    }
}
