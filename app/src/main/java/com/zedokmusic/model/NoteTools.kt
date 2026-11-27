package com.zedokmusic.model

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Transformations of lists of notes for the piano roll's tools (quantize, humanize, strum, arpeggiate, chords, riff, event LFO).
 * Pure functions on immutable [NoteM]s (no Android), so they are unit-tested; the piano roll applies the result as one undo step.
 * Times are in beats.
 */
object NoteTools {
    private const val MIN_LEN = 1f / 64f

    // ---------------------------------------------------------------- quantize
    enum class QuantTarget { Starts, Ends, Both }

    /**
     * Moves notes towards the grid. [strength] 0..1 = how far (1 = onto the grid line); [window] (beats) leaves alone notes farther than that from a line
     * (0 = no limit); [swing] 0..1 delays every other grid step by that fraction of half a step; [target] picks starts, ends or both.
     */
    fun quantize(notes: List<NoteM>, step: Float, strength: Float = 1f, target: QuantTarget = QuantTarget.Starts, swing: Float = 0f, window: Float = 0f): List<NoteM> {
        if (step <= 0f) return notes
        fun snap(x: Float): Float {
            val k = (x / step).roundToInt()
            val line = k * step + if (k % 2 != 0) swing * step * 0.5f else 0f
            val d = line - x
            if (window > 0f && abs(d) > window) return x
            return x + d * strength
        }
        return notes.map { n ->
            var s = n.start; var e = n.start + n.length
            if (target != QuantTarget.Ends) s = snap(s).coerceAtLeast(0f)
            if (target != QuantTarget.Starts) e = snap(e)
            if (target == QuantTarget.Starts) e = n.start + n.length + (s - n.start) * 0f   // the end stays where it was: the note keeps its length, below
            val len = if (target == QuantTarget.Starts) n.length else max(MIN_LEN, e - s)
            n.copy(start = s, length = len)
        }
    }

    // ---------------------------------------------------------------- humanize
    /** Random but repeatable (same [seed], same result) shifts of start (± [timing] beats), velocity (± [velocity] steps) and length (± [length] fraction). */
    fun humanize(notes: List<NoteM>, timing: Float, velocity: Int, length: Float, seed: Int): List<NoteM> {
        val rnd = java.util.Random(seed.toLong())
        fun r() = rnd.nextFloat() * 2f - 1f
        return notes.map { n ->
            val ds = r() * timing; val dv = (r() * velocity).roundToInt(); val dl = 1f + r() * length
            n.copy(start = max(0f, n.start + ds), velocity = (n.velocity + dv).coerceIn(1, 127), length = max(MIN_LEN, n.length * dl))
        }
    }

    // ---------------------------------------------------------------- chords: grouping, strum, arpeggio
    /** Notes that start together (within [eps] beats) form a chord; groups come back in time order, each sorted by pitch. */
    fun chords(notes: List<NoteM>, eps: Float = 0.02f): List<List<NoteM>> {
        val out = ArrayList<MutableList<NoteM>>()
        for (n in notes.sortedBy { it.start }) {
            val g = out.lastOrNull()
            if (g != null && abs(g[0].start - n.start) <= eps) g.add(n) else out.add(mutableListOf(n))
        }
        return out.map { g -> g.sortedBy { it.pitch } }
    }

    /**
     * Spreads each chord of [notes] over [spread] beats like a strummed guitar: the notes start one after the other (lowest first, or highest first when
     * [down] is false... see [fromTop]), their ends stay where they were. [tension] > 1 bunches the later notes, < 1 the earlier ones.
     */
    fun strum(notes: List<NoteM>, spread: Float, fromTop: Boolean = false, tension: Float = 1f): List<NoteM> {
        val out = ArrayList<NoteM>()
        for (g in chords(notes)) {
            if (g.size < 2) { out.addAll(g); continue }
            val order = if (fromTop) g.sortedByDescending { it.pitch } else g
            order.forEachIndexed { i, n ->
                val t = (i / (order.size - 1).toFloat()).toDouble().pow(tension.toDouble()).toFloat()
                val shift = min(spread * t, max(0f, n.length - MIN_LEN))
                out.add(n.copy(start = n.start + shift, length = n.length - shift))
            }
        }
        return out
    }

    enum class ArpPattern { Up, Down, UpDown, Random, AsPlayed }

    /**
     * Replaces every chord of [notes] by an arpeggio over the chord's span (from its first start to its longest end): one note every [stepBeats] through the
     * chord's pitches, repeated over [octaves] octaves, each lasting [gate] of a step. [seed] drives the Random pattern.
     */
    fun arpeggiate(notes: List<NoteM>, pattern: ArpPattern, stepBeats: Float, octaves: Int = 1, gate: Float = 0.8f, seed: Int = 1): List<NoteM> {
        if (stepBeats <= 0f) return notes
        val rnd = java.util.Random(seed.toLong())
        val out = ArrayList<NoteM>()
        for (g in chords(notes)) {
            val start = g.minOf { it.start }; val end = g.maxOf { it.start + it.length }
            val vel = g.map { it.velocity }.average().roundToInt()
            val base = if (pattern == ArpPattern.AsPlayed) g.map { it.pitch } else g.map { it.pitch }.distinct().sorted()
            val pool = (0 until max(1, octaves)).flatMap { o -> base.map { it + 12 * o } }.filter { it in 0..127 }
            if (pool.isEmpty()) continue
            val seq: List<Int> = when (pattern) {
                ArpPattern.Up, ArpPattern.AsPlayed -> pool
                ArpPattern.Down -> pool.reversed()
                ArpPattern.UpDown -> if (pool.size < 3) pool else pool + pool.subList(1, pool.size - 1).reversed()
                ArpPattern.Random -> pool
            }
            var i = 0; var t = start
            while (t < end - 1e-4f) {
                val p = if (pattern == ArpPattern.Random) pool[rnd.nextInt(pool.size)] else seq[i % seq.size]
                out.add(NoteM(p, t, max(MIN_LEN, min(stepBeats * gate.coerceIn(0.05f, 1f), end - t)), vel.coerceIn(1, 127)))
                t += stepBeats; i++
            }
        }
        return out
    }

    // ---------------------------------------------------------------- chord names and stamps
    class ChordType(val label: String, val suffix: String, val intervals: IntArray)
    val CHORDS = listOf(
        ChordType("Major", "", intArrayOf(0, 4, 7)), ChordType("Minor", "m", intArrayOf(0, 3, 7)),
        ChordType("Dominant 7", "7", intArrayOf(0, 4, 7, 10)), ChordType("Major 7", "maj7", intArrayOf(0, 4, 7, 11)), ChordType("Minor 7", "m7", intArrayOf(0, 3, 7, 10)),
        ChordType("Diminished", "dim", intArrayOf(0, 3, 6)), ChordType("Augmented", "aug", intArrayOf(0, 4, 8)),
        ChordType("Sus 2", "sus2", intArrayOf(0, 2, 7)), ChordType("Sus 4", "sus4", intArrayOf(0, 5, 7)),
        ChordType("Major 6", "6", intArrayOf(0, 4, 7, 9)), ChordType("Minor 6", "m6", intArrayOf(0, 3, 7, 9)),
        ChordType("Dominant 9", "9", intArrayOf(0, 4, 7, 10, 14)), ChordType("Major 9", "maj9", intArrayOf(0, 4, 7, 11, 14)), ChordType("Minor 9", "m9", intArrayOf(0, 3, 7, 10, 14)),
        ChordType("Add 9", "add9", intArrayOf(0, 4, 7, 14)), ChordType("Minor 7 b5", "m7b5", intArrayOf(0, 3, 6, 10)), ChordType("Diminished 7", "dim7", intArrayOf(0, 3, 6, 9)),
        ChordType("Power (5)", "5", intArrayOf(0, 7)),
    )

    /** The pitches of chord [type] built on [root] (a MIDI note). */
    fun stamp(root: Int, type: ChordType, inversion: Int = 0): List<Int> {
        val p = type.intervals.map { root + it }.toMutableList()
        repeat(inversion.coerceIn(0, p.size - 1)) { p.add(p.removeAt(0) + 12) }
        return p.filter { it in 0..127 }
    }

    /** The name of the chord the pitches form ("Cm7", "G/B" for an inversion), or null when they match no known chord. */
    fun chordName(pitches: Collection<Int>): String? {
        val pcs = pitches.map { ((it % 12) + 12) % 12 }.toSortedSet()
        if (pcs.size < 2) return null
        val bass = ((pitches.minOrNull() ?: return null) % 12 + 12) % 12
        var best: String? = null
        for (root in pcs) {
            val rel = pcs.map { (it - root + 12) % 12 }.toSortedSet()
            for (c in CHORDS) {
                val want = c.intervals.map { it % 12 }.toSortedSet()
                if (want == rel) {
                    val name = Theory.NAMES[root] + c.suffix + if (root != bass) "/" + Theory.NAMES[bass] else ""
                    if (root == bass) return name
                    if (best == null) best = name
                }
            }
        }
        return best
    }

    // ---------------------------------------------------------------- riff machine
    /**
     * A random melodic phrase in the scale: [bars] bars of 4 beats, one candidate note every [stepBeats], each played with probability [density],
     * pitches walking inside [lo]..[hi] (a random walk of scale degrees, [leap] = how big the jumps are), repeatable through [seed].
     */
    fun riff(scalePitches: (Int) -> Boolean, lo: Int, hi: Int, startBeat: Float, bars: Int, stepBeats: Float, density: Float, leap: Float, seed: Int, velocity: Int = 100): List<NoteM> {
        val rnd = java.util.Random(seed.toLong())
        val pool = (lo..hi).filter(scalePitches)
        if (pool.isEmpty() || stepBeats <= 0f) return emptyList()
        var idx = pool.size / 2
        val out = ArrayList<NoteM>(); val steps = (bars * 4f / stepBeats).roundToInt()
        var i = 0
        while (i < steps) {
            if (rnd.nextFloat() < density) {
                val jump = if (rnd.nextFloat() < leap) rnd.nextInt(5) - 2 else rnd.nextInt(3) - 1
                idx = (idx + jump).coerceIn(0, pool.size - 1)
                // a note lasts one step, or two or three when the following steps happen to be empty
                var len = 1; while (len < 3 && i + len < steps && rnd.nextFloat() > density + 0.25f) len++
                out.add(NoteM(pool[idx], startBeat + i * stepBeats, len * stepBeats * 0.95f, (velocity + rnd.nextInt(21) - 10).coerceIn(1, 127)))
                i += len
            } else i++
        }
        return out
    }

    // ---------------------------------------------------------------- event LFO
    enum class LfoShape { Sine, Triangle, Ramp, Square, Random }

    /**
     * A wave drawn over the span of [notes] (first start .. last start) as the values of one property: [cycles] periods, [depth] of the property's range around
     * [center] (both in the property's own units). Returns note -> value.
     */
    fun lfo(notes: List<NoteM>, shape: LfoShape, cycles: Float, depth: Float, center: Float, lo: Int, hi: Int, seed: Int = 1): Map<NoteM, Int> {
        if (notes.isEmpty()) return emptyMap()
        val t0 = notes.minOf { it.start }; val t1 = notes.maxOf { it.start }
        val span = max(1e-3f, t1 - t0); val rnd = java.util.Random(seed.toLong())
        val half = (hi - lo) / 2f * depth.coerceIn(0f, 1f)
        return notes.associateWith { n ->
            val ph = ((n.start - t0) / span * cycles).toDouble(); val f = (ph - floor(ph)).toFloat()
            val w = when (shape) {
                LfoShape.Sine -> sin(ph * 2 * Math.PI).toFloat()
                LfoShape.Triangle -> 1f - 4f * abs(f - 0.5f) + 0f - 0f
                LfoShape.Ramp -> 2f * f - 1f
                LfoShape.Square -> if (f < 0.5f) 1f else -1f
                LfoShape.Random -> rnd.nextFloat() * 2f - 1f
            }
            (center + w * half).roundToInt().coerceIn(lo, hi)
        }
    }
}
