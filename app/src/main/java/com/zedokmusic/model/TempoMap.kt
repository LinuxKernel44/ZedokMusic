package com.zedokmusic.model

/** A tempo change: from [beat] on, the tempo is [bpm]. */
class TempoPoint(val beat: Float, val bpm: Float)

/**
 * Converts between song time in beats and engine time in frames when the tempo changes along the song: [base] is the tempo from beat 0, [changes] the later
 * changes (a point at beat 0 or before replaces nothing: use [base]). With no change it is the plain `frames = beats * 48000 * 60 / bpm` the app always used.
 * Pure Kotlin, unit-tested; the engine has the same piecewise maths for the tempo it gives to plugins.
 */
class TempoMap(base: Float, changes: List<TempoPoint> = emptyList(), private val rate: Double = 48000.0) {
    private val starts: DoubleArray; private val bpms: DoubleArray; private val framesAt: DoubleArray
    init {
        val pts = changes.filter { it.beat > 0f && it.bpm > 0f }.sortedBy { it.beat }.fold(ArrayList<TempoPoint>()) { acc, p -> if (acc.isNotEmpty() && acc.last().beat == p.beat) acc[acc.size - 1] = p else acc.add(p); acc }
        starts = DoubleArray(pts.size + 1); bpms = DoubleArray(pts.size + 1); framesAt = DoubleArray(pts.size + 1)
        bpms[0] = base.toDouble().coerceIn(20.0, 400.0)
        for (i in pts.indices) { starts[i + 1] = pts[i].beat.toDouble(); bpms[i + 1] = pts[i].bpm.toDouble().coerceIn(20.0, 400.0) }
        for (i in 1 until starts.size) framesAt[i] = framesAt[i - 1] + (starts[i] - starts[i - 1]) * rate * 60.0 / bpms[i - 1]
    }
    val segments: Int get() = starts.size
    val isConstant: Boolean get() = starts.size == 1
    fun startBeat(i: Int) = starts[i].toFloat()
    fun bpmOf(i: Int) = bpms[i].toFloat()
    fun startFrame(i: Int) = framesAt[i].toLong()

    private fun segOfBeat(beat: Double): Int { var i = starts.size - 1; while (i > 0 && starts[i] > beat) i--; return i }
    private fun segOfFrame(f: Double): Int { var i = starts.size - 1; while (i > 0 && framesAt[i] > f) i--; return i }

    /** The engine frame of [beat]. */
    fun frames(beat: Float): Double { val b = beat.toDouble().coerceAtLeast(0.0); val i = segOfBeat(b); return framesAt[i] + (b - starts[i]) * rate * 60.0 / bpms[i] }
    /** The beat of engine frame [frame]. */
    fun beat(frame: Double): Double { val f = frame.coerceAtLeast(0.0); val i = segOfFrame(f); return starts[i] + (f - framesAt[i]) * bpms[i] / (rate * 60.0) }
    fun bpmAt(beat: Float): Float = bpms[segOfBeat(beat.toDouble())].toFloat()
    /** How many beats an audio clip of [lengthFrames] frames covers when it starts at [startBeat]. */
    fun beatsFor(startBeat: Float, lengthFrames: Long): Float = (beat(frames(startBeat) + lengthFrames) - startBeat).toFloat()
}
