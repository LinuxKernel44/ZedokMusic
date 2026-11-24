package com.zedokmusic.engine

import android.content.Context
import com.zedokmusic.wine.PrefixManager
import com.zedokmusic.wine.StorageFolder
import com.zedokmusic.wine.WineEnv
import java.io.File

/**
 * Launches and supervises the Wine process that hosts one VST3 plugin for one track.
 * Drives: C: = <workspace>/C, D: = <workspace>, E: = app files dir (bridge files, zhost.exe).
 */
class PluginHost(private val ctx: Context) {
    private val env = WineEnv(ctx)
    private val processes = HashMap<Int, Process>()
    private val files get() = ctx.filesDir

    /** Maps an Android path to the Windows path Wine sees. */
    fun toWinPath(unix: String): String {
        val ws = StorageFolder.saved(ctx)
        if (ws != null && (unix == ws.path || unix.startsWith(ws.path + "/"))) return "D:" + unix.removePrefix(ws.path).replace('/', '\\')
        if (unix.startsWith(files.absolutePath + "/")) return "E:" + unix.removePrefix(files.absolutePath).replace('/', '\\')
        throw IllegalArgumentException("Path not reachable from Wine (must be inside the workspace folder): $unix")
    }

    private fun ensureHostExe(): File {
        val exe = File(files, "zhost.exe")
        val len = ctx.assets.openFd("zhost.exe").use { it.length }
        if (!exe.exists() || exe.length() != len) ctx.assets.open("zhost.exe").use { i -> exe.outputStream().use { o -> i.copyTo(o) } }
        return exe
    }

    /** Starts the host for [track]; returns false if the bridge could not be created. Poll [NativeEngine.pluginState]. */
    fun launch(track: Int, pluginPath: String): Boolean {
        ensureHostExe()
        ctx.let { c -> StorageFolder.saved(c)?.let { PrefixManager.linkDrive(env, "d:", it) }; PrefixManager.linkDrive(env, "e:", c.filesDir) }
        val bridge = File(files, "bridge_$track.bin")
        if (!NativeEngine.createPluginBridge(track, bridge.absolutePath)) return false
        val pb = env.wine(File(files, "zhost.exe").absolutePath.let { toWinPath(it) }, toWinPath(pluginPath), toWinPath(bridge.absolutePath))
        processes[track] = pb.redirectOutput(File(files, "host_$track.log")).start()
        return true
    }

    fun stop(track: Int) {
        NativeEngine.detachPlugin(track)  // the bridge sets quit once its last snapshot is retired
        processes.remove(track)?.let { p -> Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start() }
    }

    fun stopAll() = processes.keys.toList().forEach { stop(it) }
}
