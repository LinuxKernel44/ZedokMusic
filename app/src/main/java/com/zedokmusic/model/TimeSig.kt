package com.zedokmusic.model

/** A time signature change: from [beat] on, bars are [num]/[den] (a bar is num * 4 / den beats long). */
class SigPoint(val beat: Float, val num: Int, val den: Int)

object TimeSig {
    /** The beats where bars begin up to [total], with their bar numbers (1-based), for a 4/4 start and the changes in [changes] (a change only takes effect at a bar line of the signature before it). */
    fun barStarts(changes: List<SigPoint>, total: Float): List<Pair<Float, Int>> {
        val sorted = changes.filter { it.beat > 0f }.sortedBy { it.beat }
        val out = ArrayList<Pair<Float, Int>>(); var beat = 0f; var bar = 1; var num = 4; var den = 4; var next = 0
        while (beat <= total + 1e-3f && out.size < 20000) {
            while (next < sorted.size && sorted[next].beat <= beat + 1e-3f) { num = sorted[next].num; den = sorted[next].den; next++ }
            out.add(beat to bar)
            val len = num * 4f / den
            beat += len; bar++
        }
        return out
    }
    /** The signature in force at [beat]. */
    fun at(changes: List<SigPoint>, beat: Float): Pair<Int, Int> { var r = 4 to 4; for (c in changes.sortedBy { it.beat }) if (c.beat <= beat + 1e-3f) r = c.num to c.den; return r }
}
