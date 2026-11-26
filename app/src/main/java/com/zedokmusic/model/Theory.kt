package com.zedokmusic.model

/** Note names (sharps) and the scales the project can be set to. */
object Theory {
    val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    /** Name with octave, in the app's convention where MIDI note 60 is C4. */
    fun name(pitch: Int) = NAMES[((pitch % 12) + 12) % 12] + (pitch / 12 - 1)
    fun pc(pitch: Int) = NAMES[((pitch % 12) + 12) % 12]

    class ScaleType(val label: String, val steps: IntArray)
    val SCALES = listOf(
        ScaleType("Chromatic (no scale)", IntArray(0)),
        ScaleType("Major", intArrayOf(0, 2, 4, 5, 7, 9, 11)),
        ScaleType("Minor (natural)", intArrayOf(0, 2, 3, 5, 7, 8, 10)),
        ScaleType("Harmonic minor", intArrayOf(0, 2, 3, 5, 7, 8, 11)),
        ScaleType("Melodic minor", intArrayOf(0, 2, 3, 5, 7, 9, 11)),
        ScaleType("Dorian", intArrayOf(0, 2, 3, 5, 7, 9, 10)),
        ScaleType("Phrygian", intArrayOf(0, 1, 3, 5, 7, 8, 10)),
        ScaleType("Lydian", intArrayOf(0, 2, 4, 6, 7, 9, 11)),
        ScaleType("Mixolydian", intArrayOf(0, 2, 4, 5, 7, 9, 10)),
        ScaleType("Locrian", intArrayOf(0, 1, 3, 5, 6, 8, 10)),
        ScaleType("Major pentatonic", intArrayOf(0, 2, 4, 7, 9)),
        ScaleType("Minor pentatonic", intArrayOf(0, 3, 5, 7, 10)),
        ScaleType("Blues", intArrayOf(0, 3, 5, 6, 7, 10)),
        ScaleType("Whole tone", intArrayOf(0, 2, 4, 6, 8, 10)),
        ScaleType("Phrygian dominant", intArrayOf(0, 1, 4, 5, 7, 8, 10)),
    )
    fun inScale(pitch: Int, root: Int, type: Int): Boolean { val s = SCALES.getOrNull(type)?.steps ?: return true; if (s.isEmpty()) return true; return ((pitch - root) % 12 + 12) % 12 in s }
    fun isRoot(pitch: Int, root: Int) = ((pitch - root) % 12 + 12) % 12 == 0
    fun label(root: Int, type: Int): String = if (type <= 0) "No scale" else NAMES[root.coerceIn(0, 11)] + " " + SCALES[type].label.substringBefore(" (").lowercase().replace("natural", "").trim()
}
