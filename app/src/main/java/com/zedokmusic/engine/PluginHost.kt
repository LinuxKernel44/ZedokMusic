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
            ?: if (com.zedokmusic.settings.AppSettings.wineLog.on) "err+all,fixme+all" else "err+all"  // errors are always kept: they are few and explain failures

    /**
     * DXVK (Direct3D 9/10/11 over Vulkan) into the Windows system folder, from the runtime (opt/dxvk/x64). Wine's own copies are kept in
     * system32/wine-orig and put back when the option is turned off. Done once per version / setting, before a Wine program starts.
     */
    @Synchronized private fun applyDxvk() {
        val ws = StorageFolder.saved(ctx) ?: return
        val src = File(env.root, "opt/dxvk/x64").takeIf { it.isDirectory } ?: return
        val sys = File(ws, "C/windows/system32"); if (!sys.isDirectory) return
        val want = if (com.zedokmusic.settings.AppSettings.dxvk.on) "dxvk-2.4.1-1" else "wine"
        val marker = File(files, "dxvk.applied")
        if (marker.exists() && marker.readText().trim() == want) return
        val orig = File(sys, "wine-orig").apply { mkdirs() }
        for (f in src.listFiles { x -> x.name.endsWith(".dll") } ?: return) {
            val dst = File(sys, f.name); val keep = File(orig, f.name)
            runCatching {
                if (want == "wine") { if (keep.exists()) keep.copyTo(dst, overwrite = true) }
                else { if (!keep.exists() && dst.exists()) dst.copyTo(keep); f.copyTo(dst, overwrite = true) }
            }
        }
        marker.writeText(want)
    }

    /** Where a plugin host's log goes: the workspace's Logs folder (readable with a file manager or adb), else the app's own folder. */
    private fun hostLog(track: Int, pluginPath: String): File {
        val ws = StorageFolder.saved(ctx)
        val name = File(pluginPath).nameWithoutExtension.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val dir = ws?.let { File(it, "Logs") }?.takeIf { it.isDirectory || it.mkdirs() }
        return if (dir != null) File(dir, "host_${track}_$name.log") else File(files, "host_$track.log")
    }
    private fun note(f: File, text: String) { runCatching { f.appendText("[zedok ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $text\n") } }

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

    private val nativeIds = HashSet<Int>()
    companion object { const val NATIVE_PREFIX = "zedok:"; fun isNative(path: String?) = path != null && path.startsWith(NATIVE_PREFIX) }

    /** Starts the host for [track]; returns false if the bridge could not be created. Poll [NativeEngine.pluginState]. Built-in plugins ("zedok:id") need no host. */
    fun launch(track: Int, pluginPath: String): Boolean {
        if (isNative(pluginPath)) {
            val ok = NativeEngine.createNativePlugin(track, pluginPath.removePrefix(NATIVE_PREFIX))
            if (ok) synchronized(nativeIds) { nativeIds.add(track) }
            return ok
        }
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
            applyDxvk()
            val guiRunning = mode != "none" && gui.ensureRunning()
            val guiOn = guiRunning && mode == "full"  // ("kill" mode also ends up here with guiRunning = false)
            val dbg = debugChannels()
            val pb = env.wine(*(if (guiOn) gui.desktopArgs() + args else args), debug = dbg, extraEnv = if (guiOn) ({ e -> gui.wineEnv(e) }) else null)
            val log = hostLog(track, pluginPath); log.writeText("")
            note(log, "starting ${File(pluginPath).name} (track $track, attempt ${attempt + 1}), GUI ${if (guiOn) "on" else "off"}, WINEDEBUG=$dbg, verbose=${com.zedokmusic.settings.AppSettings.wineLog.on}")
            val proc = pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            synchronized(processes) { processes[track] = proc }
            // Say in the log when the host process disappears (a crash leaves no other trace), and how far it had got.
            Thread {
                val t0 = System.currentTimeMillis(); var seen = false; var gone = 0
                while (synchronized(processes) { processes[track] === proc }) {
                    Thread.sleep(1000)
                    if (hostRunning(track)) { seen = true; gone = 0 } else if (seen || System.currentTimeMillis() - t0 > 20000) gone++
                    if (gone >= 3) { note(log, "the host process is gone after ${(System.currentTimeMillis() - t0) / 1000} s (plugin state ${NativeEngine.pluginState(track)}, GUI ${NativeEngine.guiState(track).firstOrNull()})"); break }
                }
            }.start()
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
    fun isRunning(track: Int): Boolean = synchronized(nativeIds) { track in nativeIds } || hostRunning(track)
    private fun hostRunning(track: Int): Boolean {
        val tag = "bridge_$track.bin"
        return File("/proc").listFiles()?.any { d ->
            d.name.all { it.isDigit() } && runCatching { File(d, "cmdline").readText().let { it.contains(tag) && it.contains("zhost") } }.getOrDefault(false)
        } == true
    }

    fun stop(track: Int) {
        synchronized(nativeIds) { nativeIds.remove(track) }
        NativeEngine.detachPlugin(track)  // the bridge sets quit once its last snapshot is retired
        NativeEngine.releaseRetired()
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

    // ---------- full Windows desktop (Settings) ----------
    @Volatile private var desktop: Process? = null
    val desktopRunning get() = desktop?.isAlive == true

    private fun ensureShellExe(): File {
        val exe = File(files, "zshell.exe"); val bundled = ctx.assets.open("zshell.exe").use { it.readBytes() }
        if (!exe.exists() || !exe.readBytes().contentEquals(bundled)) exe.writeBytes(bundled)
        return exe
    }

    /** Starts the desktop (Wine explorer + the taskbar) on an X screen of [w] x [h]. Every plugin host must be stopped first. Blocking. */
    fun startDesktop(w: Int, h: Int, pluginPath: String? = null): Boolean {
        ctx.let { c -> StorageFolder.saved(c)?.let { PrefixManager.linkDrive(env, "d:", it) }; PrefixManager.linkDrive(env, "e:", c.filesDir) }
        gui.reset()
        applyDxvk()
        if (!gui.ensureRunning(w, h)) return false
        val shell = toWinPath(ensureShellExe().absolutePath)
        val extra = if (pluginPath != null) arrayOf("--edit", toWinPath(pluginPath)) else emptyArray()  // open this plugin's editor first
        val pb = env.wine(*(gui.desktopArgs("zdesk", w, h) + arrayOf(shell) + extra), debug = debugChannels(), extraEnv = { e -> gui.wineEnv(e) })
        pb.directory(File(files, "home")); pb.redirectOutput(File(files, "desktop.log"))
        val p = try { pb.start() } catch (e: Exception) { return false }
        desktop = p
        KeepAlive.acquire(ctx, "desktop", "The Windows desktop is running")
        Thread { runCatching { p.waitFor() }; KeepAlive.release(ctx, "desktop") }.start()
        return true
    }

    /** Ends the desktop and everything it started, and frees the X screen so plugins get the standard one again. Blocking. */
    fun stopDesktop() {
        desktop?.destroyForcibly(); desktop = null
        KeepAlive.release(ctx, "desktop")
        gui.reset()
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
            applyDxvk()
            if (!gui.ensureRunning()) { onStarted(false); return@Thread }
            val args = if (file.extension.equals("msi", true)) arrayOf("msiexec", "/i", win) else arrayOf(win)
            val pb = env.wine(*(gui.desktopArgs("zinst") + args), debug = debugChannels(), extraEnv = { e -> gui.wineEnv(e) })
            file.parentFile?.let { pb.directory(it) }
            pb.redirectOutput(File(files, "installer.log"))
            val p = try { pb.start() } catch (e: Exception) { onStarted(false); return@Thread }
            installer = p
            KeepAlive.acquire(ctx, "installer", "Installing ${file.name}")
            onStarted(true)
            val code = p.waitFor()
            installer = null
            KeepAlive.release(ctx, "installer")
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
