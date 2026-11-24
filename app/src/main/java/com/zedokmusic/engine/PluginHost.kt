package com.zedokmusic.engine

import android.content.Context
import com.zedokmusic.wine.GuiServer
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
    val gui = GuiServer(ctx)
    private val files get() = ctx.filesDir

    /** Maps an Android path to the Windows path Wine sees. */
    fun toWinPath(unixPath: String): String {
        // /sdcard, /storage/emulated/0 and /storage/self/primary are aliases of the same folder: compare canonical paths.
        val unix = runCatching { File(unixPath).canonicalPath }.getOrDefault(unixPath)
        val ws = StorageFolder.saved(ctx)?.let { runCatching { it.canonicalFile }.getOrDefault(it) }
        if (ws != null && (unix == ws.path || unix.startsWith(ws.path + "/"))) return "D:" + unix.removePrefix(ws.path).replace('/', '\\')
        val fl = files.canonicalPath
        if (unix.startsWith("$fl/")) return "E:" + unix.removePrefix(fl).replace('/', '\\')
        throw IllegalArgumentException("Path not reachable from Wine (must be inside the workspace folder): $unixPath")
    }

    /** Copies zhost.exe out of the APK whenever its content differs (a size check alone misses same-size rebuilds). */
    private fun ensureHostExe(): File {
        val exe = File(files, "zhost.exe")
        val bundled = ctx.assets.open("zhost.exe").use { it.readBytes() }
        if (!exe.exists() || !exe.readBytes().contentEquals(bundled)) exe.writeBytes(bundled)
        return exe
    }

    /** Starts the host for [track]; returns false if the bridge could not be created. Poll [NativeEngine.pluginState]. */
    fun launch(track: Int, pluginPath: String): Boolean {
        ensureHostExe()
        ctx.let { c -> StorageFolder.saved(c)?.let { PrefixManager.linkDrive(env, "d:", it) }; PrefixManager.linkDrive(env, "e:", c.filesDir) }
        val bridge = File(files, "bridge_$track.bin")
        if (!NativeEngine.createPluginBridge(track, bridge.absolutePath)) return false
        val args = arrayOf(toWinPath(File(files, "zhost.exe").absolutePath), toWinPath(pluginPath), toWinPath(bridge.absolutePath))
        // Starting the X server can take a few seconds the first time: keep the UI thread free.
        Thread {
            // Developer switch (file files/guimode): "none" = no X server, "xvfb" = start Xvfb but run hosts without it, default = full GUI.
            val mode = File(files, "guimode").takeIf { it.exists() }?.readText()?.trim() ?: "full"
            val guiRunning = mode != "none" && gui.ensureRunning()
            val guiOn = guiRunning && mode == "full"  // ("kill" mode also ends up here with guiRunning = false)
            val dbg = File(files, "winedebug").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: "-all"  // developer aid: a file holding a WINEDEBUG value
            val pb = env.wine(*(if (guiOn) gui.desktopArgs() + args else args), debug = dbg, extraEnv = if (guiOn) ({ e -> gui.wineEnv(e) }) else null)
            synchronized(processes) { processes[track] = pb.redirectOutput(File(files, "host_$track.log")).start() }
        }.start()
        return true
    }

    fun stop(track: Int) {
        NativeEngine.detachPlugin(track)  // the bridge sets quit once its last snapshot is retired
        synchronized(processes) { processes.remove(track) }?.let { p -> Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start() }
    }

    fun stopAll() { synchronized(processes) { processes.keys.toList() }.forEach { stop(it) }; gui.stop() }
}
