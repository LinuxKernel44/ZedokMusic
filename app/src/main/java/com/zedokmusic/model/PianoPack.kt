package com.zedokmusic.model

import android.content.Context
import com.zedokmusic.engine.NativeEngine
import java.io.File

/**
 * The sound pack of Zedok Piano (assets/piano.zpk: Ogg Vorbis files of the Salamander Grand Piano) is copied once into the app's data
 * folder, where the native plugin memory-maps it. The copy is written under a temporary name and renamed, so the plugin never sees half a file.
 */
object PianoPack {
    @Volatile private var started = false
    fun ensure(ctx: Context) {
        val dir = File(ctx.filesDir, "plugin-data").apply { mkdirs() }
        NativeEngine.setDataDir(dir.path)
        if (started) return
        started = true
        Thread {
            runCatching {
                val len = ctx.assets.openFd("piano.zpk").use { it.length }
                val f = File(dir, "piano.zpk")
                if (!f.exists() || f.length() != len) {
                    val tmp = File(dir, "piano.zpk.tmp")
                    ctx.assets.open("piano.zpk").use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
                    tmp.renameTo(f)
                }
            }
        }.apply { name = "piano-pack"; isDaemon = true }.start()
    }
}
