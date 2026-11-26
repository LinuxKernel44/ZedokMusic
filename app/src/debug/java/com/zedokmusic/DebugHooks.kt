package com.zedokmusic

import android.content.Intent
import android.util.Log
import com.zedokmusic.engine.EngineSelfTest
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.engine.PluginSelfTest
import com.zedokmusic.engine.RecordSelfTest
import java.io.File

/** Development-only hooks driven by adb intent extras. This file exists only in debug builds. */
object DebugHooks {
    /** `--es test_folder /sdcard/ZedokMusic` skips the folder picker. */
    fun autoFolder(intent: Intent?): File? = intent?.getStringExtra("test_folder")?.let { File(it) }

    fun install(activity: MainActivity, intent: Intent?) {
        val files = activity.filesDir
        if (intent?.getBooleanExtra("engine_test", false) == true)
            Thread { File(files, "engine_test.log").writeText(EngineSelfTest.run(files) + "[done]\n") }.start()
        if (intent?.getBooleanExtra("rec_test", false) == true)
            Thread { File(files, "rec_test.log").writeText(try { RecordSelfTest.run(files) } catch (t: Throwable) { "EXCEPTION $t" } + "[done]\n") }.start()
        if (intent?.getBooleanExtra("plugin_test", false) == true)
            Thread {
                File(files, "plugin_test.log").writeText(try { PluginSelfTest.run(activity.applicationContext) } catch (t: Throwable) { "EXCEPTION $t" } + "[done]\n")
            }.start()
        // `--es pinch "x1,y1,x2,y2,X1,Y1,X2,Y2"`: after 6 s, injects a two-finger gesture (from the first pair of points to the second) into the window.
        intent?.getStringExtra("pinch")?.let { spec ->
            val v = spec.split(",").map { it.trim().toFloat() }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ injectPinch(activity, v) }, 6000)
        }
        // `--es kit /sdcard/Download/TestKits/808 Pack`: after 7 s, imports that folder as a drum kit into the first Zedok Drums track (same path as the Load kit button, without the picker).
        intent?.getStringExtra("kit")?.let { dir ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val vm = DawViewModel.current; val t = vm?.tracks?.firstOrNull { vm.nativeId(it) == "drums" }
                if (t == null) Log.w("ZedokDebug", "kit: no Drums track") else { vm.kits.scanFolder(t, File(dir)); Log.i("ZedokDebug", "kit import started: $dir") }
                // a few seconds later: how many sample zones does the engine report per pad, and what did the engine keep as state?
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (t != null) Log.i("ZedokDebug", "kit zones per pad: " + com.zedokmusic.engine.NativeEngine.pluginTelemetry(t.id, 1).joinToString(",") + " | pad4 state lines: " + com.zedokmusic.engine.NativeEngine.getPluginState(t.id, "pad4").lines().size)
                }, 8000)
            }, 7000)
        }
        intent?.getStringExtra("cmd")?.let { cmd ->  // `--es cmd "<shell command>"`: run inside the app's SELinux domain
            Thread {
                val out = File(files, "cmd.log")
                try {
                    val rc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).redirectOutput(out).start().waitFor()
                    out.appendText("\n[exit $rc]\n")
                } catch (e: Exception) { out.appendText("\n[exception $e]\n") }
                Log.i("ZedokDebug", "cmd done")
            }.start()
        }
    }

    private fun injectPinch(activity: MainActivity, v: List<Float>) {
        val h = android.os.Handler(android.os.Looper.getMainLooper()); val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, t: Long, pts: List<Pair<Float, Float>>): android.view.MotionEvent {
            val props = Array(pts.size) { i -> android.view.MotionEvent.PointerProperties().also { it.id = i; it.toolType = android.view.MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(pts.size) { i -> android.view.MotionEvent.PointerCoords().also { it.x = pts[i].first; it.y = pts[i].second; it.pressure = 1f; it.size = 1f } }
            return android.view.MotionEvent.obtain(t0, t, action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val a0 = v[0] to v[1]; val b0 = v[2] to v[3]; val a1 = v[4] to v[5]; val b1 = v[6] to v[7]
        val steps = ArrayList<android.view.MotionEvent>(); var t = t0
        steps.add(ev(android.view.MotionEvent.ACTION_DOWN, t, listOf(a0)))
        t += 16; steps.add(ev(android.view.MotionEvent.ACTION_POINTER_DOWN or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), t, listOf(a0, b0)))
        for (i in 1..12) { t += 16; val f = i / 12f; steps.add(ev(android.view.MotionEvent.ACTION_MOVE, t, listOf(a0.first + (a1.first - a0.first) * f to a0.second + (a1.second - a0.second) * f, b0.first + (b1.first - b0.first) * f to b0.second + (b1.second - b0.second) * f))) }
        t += 16; steps.add(ev(android.view.MotionEvent.ACTION_POINTER_UP or (1 shl android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT), t, listOf(a1, b1)))
        t += 16; steps.add(ev(android.view.MotionEvent.ACTION_UP, t, listOf(a1)))
        steps.forEachIndexed { i, e -> h.postDelayed({ activity.window.decorView.dispatchTouchEvent(e) }, 20L * i) }
        Log.i("ZedokDebug", "pinch injected")
    }
}
