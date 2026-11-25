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

    /** WINEDEBUG value: a developer file `winedebug` wins, else "err+all" when verbose logging is on in Settings, else silent. */
    private fun debugChannels(): String =
        File(files, "winedebug").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
            ?: if (com.zedokmusic.settings.AppSettings.wineLog.on) "err+all" else "-all"

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
        return startHost(track, pluginPath, 0)
    }

    /** Creates the bridge and starts the Wine process. A host that dies before it is ready (Wine and the X server occasionally fail while several start at once) is started again, twice at most. */
    private fun startHost(track: Int, pluginPath: String, attempt: Int): Boolean {
        val bridge = File(files, "bridge_$track.bin")
        if (!NativeEngine.createPluginBridge(track, bridge.absolutePath)) return false
        val args = arrayOf(toWinPath(File(files, "zhost.exe").absolutePath), toWinPath(pluginPath), toWinPath(bridge.absolutePath))
        // Starting the X server can take a few seconds the first time: keep the UI thread free.
        Thread {
            // Developer switch (file files/guimode): "none" = no X server, "xvfb" = start Xvfb but run hosts without it, default = full GUI.
            val mode = File(files, "guimode").takeIf { it.exists() }?.readText()?.trim() ?: "full"
            val guiRunning = mode != "none" && gui.ensureRunning()
            val guiOn = guiRunning && mode == "full"  // ("kill" mode also ends up here with guiRunning = false)
            val dbg = debugChannels()
            val pb = env.wine(*(if (guiOn) gui.desktopArgs() + args else args), debug = dbg, extraEnv = if (guiOn) ({ e -> gui.wineEnv(e) }) else null)
            val proc = pb.redirectOutput(File(files, "host_$track.log")).start()
            synchronized(processes) { processes[track] = proc }
            // The process started here is only a launcher when the X desktop already exists, so watch the host itself: if no
            // zhost for this bridge is running a few seconds after the start and it never became ready, start it again.
            if (attempt < 2) Thread {
                val t0 = System.currentTimeMillis(); var gone = 0
                while (System.currentTimeMillis() - t0 < 90_000) {
                    Thread.sleep(2000)
                    if (synchronized(processes) { processes[track] !== proc }) return@Thread   // stopped (or replaced)
                    if (NativeEngine.pluginState(track) == 2) return@Thread
                    gone = if (hostRunning(track)) 0 else gone + 1
                    if (gone >= 3 && System.currentTimeMillis() - t0 > 8_000) {
                        synchronized(processes) { processes.remove(track) }
                        startHost(track, pluginPath, attempt + 1); return@Thread
                    }
                }
            }.start()
        }.start()
        return true
    }

    /** Is a zhost for this track's bridge running? (Scans our own processes' command lines.) */
    private fun hostRunning(track: Int): Boolean {
        val tag = "bridge_$track.bin"
        return File("/proc").listFiles()?.any { d ->
            d.name.all { it.isDigit() } && runCatching { File(d, "cmdline").readText().let { it.contains(tag) && it.contains("zhost") } }.getOrDefault(false)
        } == true
    }

    fun stop(track: Int) {
        NativeEngine.detachPlugin(track)  // the bridge sets quit once its last snapshot is retired
        synchronized(processes) { processes.remove(track) }?.let { p -> Thread { if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly() }.start() }
    }

    /** Asks zhost what a plugin is (1 instrument, 2 effect, 0 unknown) without starting it. Blocking, a few seconds. */
    fun probe(pluginPath: String): Int {
        val out = File(files, "probe.txt"); out.delete()
        return try {
            val args = arrayOf(toWinPath(File(files, "zhost.exe").absolutePath), "--probe", toWinPath(pluginPath), toWinPath(out.absolutePath))
            ensureHostExe()
            val p = env.wine(*args).redirectOutput(File(files, "probe.log")).start()
            if (!p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); 0 }
            else out.takeIf { it.exists() }?.readText()?.substringBefore('\t')?.trim()?.toIntOrNull() ?: 0
        } catch (e: Exception) { 0 }
    }

    // ---------- Windows installers (.exe / .msi) ----------
    @Volatile private var installer: Process? = null
    val installerRunning get() = installer?.isAlive == true

    /**
     * Runs a Windows installer from the workspace on its own virtual desktop of the shared X screen (see [GuiServer]); the UI shows
     * and drives that screen. [onStarted] tells whether it launched; [onExit] gets its exit code when it ends. Returns at once.
     */
    fun runInstaller(file: File, onStarted: (Boolean) -> Unit, onExit: (Int) -> Unit) {
        Thread {
            val win = try { toWinPath(file.absolutePath) } catch (e: Exception) { onStarted(false); return@Thread }
            if (!gui.ensureRunning()) { onStarted(false); return@Thread }
            val args = if (file.extension.equals("msi", true)) arrayOf("msiexec", "/i", win) else arrayOf(win)
            val pb = env.wine(*(gui.desktopArgs("zinst") + args), debug = debugChannels(), extraEnv = { e -> gui.wineEnv(e) })
            file.parentFile?.let { pb.directory(it) }
            pb.redirectOutput(File(files, "installer.log"))
            val p = try { pb.start() } catch (e: Exception) { onStarted(false); return@Thread }
            installer = p
            onStarted(true)
            val code = p.waitFor()
            installer = null
            onExit(code)
        }.start()
    }

    /** Ends the running installer. With no plugin running, the whole Wine session is reset so nothing it started lingers. */
    fun stopInstaller() {
        installer?.destroyForcibly()
        Thread { if (synchronized(processes) { processes.isEmpty() }) runCatching { env.wineserver("-k").start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS) } }.start()
    }

    fun stopAll() { synchronized(processes) { processes.keys.toList() }.forEach { stop(it) }; gui.stop() }
}
