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
    /** The format written by this version. 2 = first numbered migration (defaults made explicit: scale, stereo width, view, per-track lists). 3 = a note may carry five more numbers after its velocity (pan, cutoff, resonance, fine pitch, release; a sixth 0/1 for slide). 4 = patterns: the notes of the tracks move into `patterns` (one set of notes per track per pattern) and a `playlist` places them. 5 = tempo changes (`tempoChanges`: [beat, bpm]) and timeline `markers` ({beat, name}). 6 = mixer routing: `buses` ({name, gain, pan, stereo, mute, output}) and per track `output` and `sends` ({bus, gain, pre}); bus references are 1-based positions in `buses`, 0 = the master. */
    const val CURRENT = 7

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
        { r ->   // 3 -> 4: the notes of every track become "Pattern 1", placed once at the start of the playlist (the song plays exactly as before)
            if (!r.has("patterns")) {
                val tracks = r.optJSONArray("tracks") ?: JSONArray()
                val perTrack = JSONObject()
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val notes = t.optJSONArray("notes")
                    if (notes != null && notes.length() > 0) perTrack.put(i.toString(), notes)
                    t.remove("notes")
                }
                r.put("patterns", JSONArray().put(JSONObject().put("id", 1).put("name", "Pattern 1").put("color", 0).put("tracks", perTrack)))
                r.put("playlist", JSONArray().put(JSONObject().put("pattern", 1).put("start", 0.0).put("row", 0)))
                r.put("currentPattern", 1)
            }
        },
        { _ -> },   // 4 -> 5: tempoChanges and markers are optional keys: a project without them has one tempo and no markers
        { _ -> },   // 5 -> 6: buses, output and sends are optional keys: a project without them mixes every track straight into the master
        { r -> if (!r.has("autoCrossfade")) r.put("autoCrossfade", false) },   // 6 -> 7: overlapping clips crossfade only in projects that ask for it (older ones keep summing them); clip fadeIn / fadeOut / fadeInShape / fadeOutShape are optional keys
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
