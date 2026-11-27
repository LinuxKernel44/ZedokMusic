package com.zedokmusic.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Witness files in src/test/resources/projects: one project per era of the format. Add one whenever the format changes. */
class ProjectFormatTest {
    private fun witness(name: String) = JSONObject(javaClass.getResource("/projects/$name")!!.readText())

    @Test fun minimalOldProjectIsBroughtToTheCurrentFormatWithDefaults() {
        val src = witness("v1_minimal.zedok")
        val r = ProjectFormat.migrate(witness("v1_minimal.zedok"))
        assertEquals(ProjectFormat.CURRENT, r.getInt("version"))
        assertEquals(128.0, r.getDouble("bpm"), 0.0)                       // what was there is untouched
        assertEquals(src.getJSONArray("tracks").getJSONObject(0).getJSONArray("notes").toString(), r.getJSONArray("tracks").getJSONObject(0).getJSONArray("notes").toString())
        assertEquals(0.0, r.getDouble("masterStereo"), 0.0)               // what the loader used to assume is now written down
        assertEquals(0, r.getJSONObject("scale").getInt("type"))
        val t = r.getJSONArray("tracks").getJSONObject(0)
        assertEquals(1.0, t.getDouble("gain"), 0.0); assertEquals(0.0, t.getDouble("stereo"), 0.0); assertTrue(t.has("fx")); assertTrue(t.has("clips"))
        assertTrue(r.has("masterFx"))
    }

    @Test fun fullOldProjectLosesNothing() {
        val src = witness("v1_full.zedok")
        val r = ProjectFormat.migrate(witness("v1_full.zedok"))
        // every value the old file carried is still there, byte for byte
        for (k in listOf("bpm", "master", "loopBars", "loop", "scale", "view", "masterFx")) assertEquals(k, src.get(k).toString(), r.get(k).toString())
        for (i in 0 until src.getJSONArray("tracks").length()) {
            val a = src.getJSONArray("tracks").getJSONObject(i); val b = r.getJSONArray("tracks").getJSONObject(i)
            for (k in a.keys()) assertEquals("track $i $k", a.get(k).toString(), b.get(k).toString())
        }
        assertEquals(0.0, r.getJSONArray("tracks").getJSONObject(0).getDouble("stereo"), 0.0)   // the only keys added are defaults
        assertEquals(0.0, r.getDouble("masterStereo"), 0.0)
    }

    @Test fun currentFormatIsLeftAsItIs() {
        val src = witness("v2_current.zedok")
        val r = ProjectFormat.migrate(witness("v2_current.zedok"))
        assertEquals(src.toString(), r.toString())
    }

    @Test fun migrationIsIdempotent() {
        val once = ProjectFormat.migrate(witness("v1_full.zedok")).toString()
        assertEquals(once, ProjectFormat.migrate(JSONObject(once)).toString())
    }

    @Test fun fileWithoutVersionIsFormatOne() {
        assertEquals(1, ProjectFormat.versionOf(JSONObject("{\"bpm\":120}")))
        assertEquals(ProjectFormat.CURRENT, ProjectFormat.migrate(JSONObject("{\"bpm\":120,\"tracks\":[]}")).getInt("version"))
    }

    @Test fun newerProjectIsRefusedAndNotModified() {
        val r = witness("v99_future.zedok")
        val before = r.toString()
        try { ProjectFormat.migrate(r); fail("should refuse a newer format") } catch (e: ProjectFormat.TooNew) {
            assertEquals(99, e.found); assertTrue(e.message!!.contains("newer version"))
        }
        assertEquals(before, r.toString())
    }

    @Test fun everyStepHasAWitness() {
        // the number of steps is checked when the object is created; this keeps a witness per format version
        for (v in 1..ProjectFormat.CURRENT) assertNotNull("witness for format $v", javaClass.getResource("/projects/" + (if (v == 1) "v1_full" else "v${v}_current") + ".zedok"))
    }
}
