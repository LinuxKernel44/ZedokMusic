package com.zedokmusic.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioning of the `.zedok` project file.
 *
 * The file is JSON with an integer `version` at the top. Every change that adds or reshapes saved data bumps [CURRENT] and adds one
 * step to [steps] (the step that turns format N into N+1). [migrate] runs the steps in order, so a project saved by any earlier
 * version is brought to the current shape before the loader reads it and the loader never has to guess what an old file lacked.
 *
 * Rules for a step: it only adds or renames keys (never drops data), is safe to run on a file that already has the key, and has a test
 * with a witness file in `src/test/resources/projects`.
 *
 * A file written by a *newer* app is refused ([TooNew]) instead of being half read and then overwritten by the next autosave.
 */
object ProjectFormat {
    /** The format written by this version. 2 = first numbered migration (defaults made explicit: scale, stereo width, view, per-track lists). 3 = a note may carry five more numbers after its velocity (pan, cutoff, resonance, fine pitch, release). */
    const val CURRENT = 3

    class TooNew(val found: Int) : Exception("This project was saved by a newer version of ZedokMusic (project format $found; this version reads up to $CURRENT). Update the app to open it.")

    /** steps[n] migrates format (n + 1) to (n + 2): index 0 is 1 -> 2. */
    private val steps: List<(JSONObject) -> Unit> = listOf(
        { r ->   // 1 -> 2: write down what the loader used to assume for a missing key, so later steps can rely on the keys
            if (!r.has("masterStereo")) r.put("masterStereo", 0.0)
            if (!r.has("master")) r.put("master", 1.0)
            if (!r.has("loopBars")) r.put("loopBars", 4)
            if (!r.has("loop")) r.put("loop", false)
            if (r.optJSONObject("scale") == null) r.put("scale", JSONObject().put("root", 0).put("type", 0))
            if (!r.has("masterFx")) r.put("masterFx", JSONArray())
            val tracks = r.optJSONArray("tracks") ?: JSONArray().also { r.put("tracks", it) }
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                if (!t.has("gain")) t.put("gain", 1.0)
                if (!t.has("pan")) t.put("pan", 0.0)
                if (!t.has("stereo")) t.put("stereo", 0.0)
                if (!t.has("mute")) t.put("mute", false)
                if (!t.has("solo")) t.put("solo", false)
                if (!t.has("notes")) t.put("notes", JSONArray())
                if (!t.has("clips")) t.put("clips", JSONArray())
                if (!t.has("fx")) t.put("fx", JSONArray())
            }
        },
        { _ -> },   // 2 -> 3: nothing to rewrite: notes saved before this are [pitch, start, length, velocity], the loader reads the five optional numbers when there are some
    )

    init { check(steps.size == CURRENT - 1) { "ProjectFormat.CURRENT ($CURRENT) needs ${CURRENT - 1} migration steps, found ${steps.size}" } }

    /** The format number of [root] (files that predate numbering, or carry none, are format 1). */
    fun versionOf(root: JSONObject): Int = root.optInt("version", 1).coerceAtLeast(1)

    /** Brings [root] to [CURRENT] in place and returns it. Throws [TooNew] for a file from a newer app. */
    fun migrate(root: JSONObject): JSONObject {
        val v = versionOf(root)
        if (v > CURRENT) throw TooNew(v)
        for (from in v until CURRENT) steps[from - 1](root)
        root.put("version", CURRENT)
        return root
    }
}
