package com.zedokmusic

import android.content.Intent
import android.util.Log
import com.zedokmusic.engine.EngineSelfTest
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
}
