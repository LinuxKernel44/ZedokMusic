package com.zedokmusic.wine

import android.content.Context
import com.zedokmusic.engine.NativeEngine
import java.io.File

/** Extracts the bundled Wine/Box64 rootfs (assets/root.tar.zst) into files/root on first run. */
object RootfsInstaller {
    private const val ASSET = "root.tar.zst"
    private fun marker(ctx: Context) = File(ctx.filesDir, "root/.zedok-rootfs")
    private fun bundledVersion(ctx: Context) = ctx.assets.open("root.version").bufferedReader().use { it.readText().trim() }

    fun isInstalled(ctx: Context): Boolean =
        marker(ctx).let { it.exists() && it.readText().trim() == bundledVersion(ctx) } && WineEnv(ctx).box64.canExecute()

    /** Blocking. [progress] receives 0..1. Returns null on success or an error message. */
    fun install(ctx: Context, progress: (Float) -> Unit): String? {
        val env = WineEnv(ctx)
        env.root.deleteTree()
        val afd = try { ctx.assets.openFd(ASSET) } catch (e: Exception) { return "Bundled runtime is missing from this build." }
        val total = afd.length.toFloat()
        val watcher = Thread {
            try { while (!Thread.currentThread().isInterrupted) { progress((NativeEngine.extractProgress() / total).coerceIn(0f, 1f)); Thread.sleep(150) } }
            catch (_: InterruptedException) {}
        }.also { it.start() }
        val err = try { NativeEngine.extractTarZst(afd.parcelFileDescriptor.fd, afd.startOffset, afd.length, env.root.absolutePath) }
        finally { watcher.interrupt(); afd.close() }
        if (err.isNotEmpty()) { env.root.deleteTree(); return err }
        if (!env.box64.setExecutable(true) || !env.box64.canExecute()) return "Runtime extracted but Box64 is not executable."
        marker(ctx).writeText(bundledVersion(ctx))
        progress(1f)
        return null
    }
}
