package com.zedokmusic.wine

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Creates the Wine prefix. Wine's own first-run init is ~15x slower on shared storage (FUSE) than on internal storage,
 * so: 1) initialise the prefix internally, 2) copy drive_c into <workspace>/C, 3) point drive_c there.
 * Registry files stay internal; every file the user sees as C: lives in their chosen folder.
 */
object PrefixManager {
    private fun marker(ctx: Context) = File(ctx.filesDir, "pfx/.zedok-prefix")

    fun isReady(ctx: Context, workspace: File): Boolean {
        val c = File(ctx.filesDir, "pfx/drive_c")
        return marker(ctx).exists() && Files.isSymbolicLink(c.toPath()) && File(workspace, "C/windows").isDirectory
    }

    /** Blocking. [progress] gets (0..1, message). Returns null on success or an error. */
    fun setup(ctx: Context, workspace: File, progress: (Float, String) -> Unit): String? {
        val env = WineEnv(ctx)
        progress(0.02f, "Creating Windows environment…")
        env.prefix.deleteTree()  // never deleteRecursively: the prefix links d: to the whole workspace
        env.prefix.mkdirs()
        // 1) internal init
        val p = env.wine("wineboot", "-i").redirectOutput(File(ctx.filesDir, "wineboot.log")).start()
        val t0 = System.currentTimeMillis()
        while (p.isAlive) {
            Thread.sleep(300)
            val elapsed = (System.currentTimeMillis() - t0) / 1000f
            progress((0.02f + 0.4f * (elapsed / 45f)).coerceAtMost(0.42f), "Creating Windows environment…")
            if (elapsed > 300) { p.destroyForcibly(); return "Windows environment setup timed out." }
        }
        if (p.exitValue() != 0) return "Windows environment setup failed (exit ${p.exitValue()})."
        // let wineserver finish writing the registry before we touch the tree
        env.wineserver("-w").start().waitFor()
        val internalC = File(env.prefix, "drive_c")
        if (!File(internalC, "windows").isDirectory) return "Windows environment is incomplete."
        // 2) copy to the user's folder
        val target = File(workspace, "C")
        // Never delete an existing C: (installed plugins, licences, saved settings): the new files are merged over it.
        target.mkdirs()
        val total = internalC.walkTopDown().count().coerceAtLeast(1)
        var n = 0
        for (src in internalC.walkTopDown()) {
            val rel = src.relativeTo(internalC).path
            val dst = if (rel.isEmpty()) target else File(target, rel)
            if (Files.isSymbolicLink(src.toPath())) {  // shared storage cannot hold symlinks: materialise the target when it is a file/dir
                val real = try { src.canonicalFile } catch (e: Exception) { null }
                if (real != null && real.isFile) real.copyTo(dst, overwrite = true)
                else if (real != null && real.isDirectory) dst.mkdirs()
            } else if (src.isDirectory) dst.mkdirs() else src.copyTo(dst, overwrite = true)
            if (++n % 40 == 0) progress(0.42f + 0.56f * (n / total.toFloat()), "Copying Windows files to your folder…")
        }
        // 3) repoint drive_c
        internalC.deleteTree()
        Files.createSymbolicLink(File(env.prefix, "drive_c").toPath(), target.toPath())
        linkDrives(env, ctx, workspace)
        marker(ctx).writeText("1")
        progress(1f, "Ready")
        return null
    }

    /** d: = the ZedokMusic folder, e: = the app's private files, f: = the whole phone storage (/storage/emulated/0). */
    fun linkDrives(env: WineEnv, ctx: Context, workspace: File?) {
        if (workspace != null) linkDrive(env, "d:", workspace)
        linkDrive(env, "e:", ctx.filesDir)
        linkDrive(env, "f:", File("/storage/emulated/0"))
    }

    fun linkDrive(env: WineEnv, letter: String, target: File) {
        val dos = File(env.prefix, "dosdevices").apply { mkdirs() }
        val l = File(dos, letter).toPath()
        if (!Files.isSymbolicLink(l) || Files.readSymbolicLink(l).toString() != target.absolutePath) {
            Files.deleteIfExists(l); Files.createSymbolicLink(l, target.toPath())
        }
    }
}
