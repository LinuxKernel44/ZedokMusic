package com.zedokmusic.wine

import android.content.Context
import com.zedokmusic.engine.XDisplay
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The X server behind plugin editors: an Xvfb from the rootfs overlay (see docs/gui-spike.md). Wine runs in a virtual
 * desktop on it; the app reads its framebuffer (a memory-mappable file) and injects pointer events with XTEST.
 */
class GuiServer(private val ctx: Context) {
    private val env = WineEnv(ctx)
    private var xvfb: Process? = null
    private val fbDir = File(ctx.filesDir, "fb")
    private val socket = File(env.root, "tmp/.X11-unix/X0")
    private val xvfbBin = File(env.root, "usr/bin/Xvfb")

    companion object {
        const val WIDTH = 1600; const val HEIGHT = 3000; const val SLOT_HEIGHT = 1000  // three editors fit, one per slot
        // The installer gets its own full-size desktop (Wine only accepts a desktop as big as the X screen, otherwise the monitor
        // metrics are wrong). Windows centre themselves on it, i.e. around the middle of the screen: that is the part we show.
        const val INSTALLER_VIEW_W = 1100; const val INSTALLER_VIEW_H = 820        // the whole middle area
        const val INSTALLER_ZOOM_W = 760; const val INSTALLER_ZOOM_H = 570         // closer, easier to touch on a phone
    }  // three editors fit, one per slot

    /** Size of the X screen now running (the plugin editors' layout, or the Windows desktop's). */
    @Volatile var screenW = WIDTH; @Volatile var screenH = HEIGHT

    val available get() = xvfbBin.exists() && File(env.root, "usr/lib/libxshim.so").exists()

    /** Environment additions that let Wine (through Box64) reach the X server. */
    fun wineEnv(e: MutableMap<String, String>) {
        e["DISPLAY"] = ":0"
        e["LD_PRELOAD"] = File(env.root, "usr/lib/libxshim.so").absolutePath
        e["ZSHIM_ROOT"] = env.root.absolutePath
        e["ZSHIM_KEEP"] = "1"
        e["BOX64_LD_PRELOAD"] = File(env.root, "usr/lib/libzshmstub.so").absolutePath
        // Direct3D 9-11 through DXVK on Vulkan (software lavapipe from the runtime): plugin editors that draw with Direct3D 11 need it.
        e["VK_ICD_FILENAMES"] = File(env.root, "usr/share/vulkan/icd.d/lvp_icd.json").absolutePath
        if (com.zedokmusic.settings.AppSettings.dxvk.on) {
            e["WINEDLLOVERRIDES"] = "mscoree,mshtml=;d3d11,d3d10core,dxgi,d3d9,d3d8=n,b"
            e["DXVK_LOG_LEVEL"] = if (com.zedokmusic.settings.AppSettings.wineLog.on) "info" else "warn"
            e["DXVK_LOG_PATH"] = "E:\\"
        }
    }

    /** Starts Xvfb if needed and opens the display. Blocking (a few seconds the first time). Returns false when GUIs are unavailable. */
    @Synchronized fun ensureRunning(w: Int = WIDTH, h: Int = HEIGHT): Boolean {
        if (!available) return false
        if (xvfb?.isAlive == true && socket.exists() && screenW == w && screenH == h) return true
        return try { start(w, h) } catch (e: Exception) { false }
    }

    private fun start(w: Int, h: Int): Boolean {
        screenW = w; screenH = h
        XDisplay.inputClose()
        xvfb?.destroyForcibly(); xvfb = null
        // A wineserver left over from an earlier run still refers to the old X screen's desktop window: end it first.
        runCatching { env.wineserver("-k").start().waitFor(10, TimeUnit.SECONDS) }
        runCatching { ProcessBuilder("pkill", "Xvfb").start().waitFor(2, TimeUnit.SECONDS) }
        val devMode = File(ctx.filesDir, "guimode").takeIf { it.exists() }?.readText()?.trim()
        if (devMode == "kill") return false  // developer bisect aid: stop after the clean-up above
        val bin = File(env.root, "usr/bin")
        listOf("Xvfb", "xkbcomp").forEach { File(bin, it).setExecutable(true) }
        listOf(".tX0-lock", ".X0-lock").forEach { File(bin, it).delete() }
        socket.delete(); File(env.root, "tmp/.X11-unix").apply { mkdirs(); setReadable(true, false); setWritable(true, false); setExecutable(true, false) }
        File(fbDir, "Xvfb_screen0").delete(); fbDir.mkdirs(); env.home.mkdirs()
        val pb = ProcessBuilder("./Xvfb", ":0", "-screen", "0", "${w}x${h}x24", "-fp", "built-ins",
            "-xkbdir", File(env.root, "usr/share/X11/xkb").absolutePath, "-nolisten", "tcp", "-extension", "MIT-SHM", "-fbdir", fbDir.absolutePath)
        pb.directory(bin)
        pb.environment().apply {
            put("LD_PRELOAD", File(env.root, "usr/lib/libxshim.so").absolutePath); put("ZSHIM_ROOT", env.root.absolutePath); put("HOME", env.home.absolutePath)
        }
        pb.redirectErrorStream(true).redirectOutput(File(ctx.filesDir, "xvfb.log"))
        xvfb = pb.start()
        val fb = File(fbDir, "Xvfb_screen0")
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 15000) {
            if (xvfb?.isAlive != true) return false
            if (socket.exists() && fb.exists() && fb.length() > 0) break
            Thread.sleep(100)
        }
        if (!socket.exists() || !fb.exists()) return false
        if (!XDisplay.fbOpen(fb.absolutePath)) return false
        // The X server accepts connections slightly after creating its socket.
        for (i in 0 until 20) { if (XDisplay.inputConnect(socket.absolutePath)) break; Thread.sleep(100) }
        clearDesktopRegistry()
        return true
    }

    /** Wine arguments that put a program on the shared virtual desktop (no window manager is needed there). */
    fun desktopArgs(name: String = "zedok", w: Int = screenW, h: Int = screenH) = arrayOf("explorer", "/desktop=$name,${w}x$h")

    /** An earlier experiment set the desktop in the registry for every process; remove it (explicit launch is used instead). Once per prefix. */
    private fun clearDesktopRegistry() {
        val marker = File(env.prefix, ".zedok-desktop-cleared")
        if (marker.exists()) return
        env.wine("reg", "delete", "HKCU\\Software\\Wine\\Explorer", "/v", "Desktop", "/f").start().waitFor(60, TimeUnit.SECONDS)
        env.wine("reg", "delete", "HKCU\\Software\\Wine\\Explorer\\Desktops", "/f").start().waitFor(60, TimeUnit.SECONDS)
        env.wineserver("-w").start().waitFor(30, TimeUnit.SECONDS)
        marker.writeText("1")
    }

    /** Ends the X server and everything Wine runs on it (the next plugin or installer start brings the standard screen back). */
    @Synchronized fun reset() {
        XDisplay.inputClose(); xvfb?.destroyForcibly(); xvfb = null
        runCatching { env.wineserver("-k").start().waitFor(10, TimeUnit.SECONDS) }
        screenW = WIDTH; screenH = HEIGHT
    }

    fun stop() { XDisplay.inputClose(); xvfb?.destroyForcibly(); xvfb = null }
}
