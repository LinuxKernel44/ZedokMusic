package com.zedokmusic.wine

import android.content.Context
import java.io.File

/** Paths and process environment for the Box64 + Wine runtime installed under files/root. */
class WineEnv(ctx: Context) {
    val files: File = ctx.filesDir
    val root = File(files, "root")
    val prefix = File(files, "pfx")
    val home = File(files, "home")
    val box64 = File(root, "usr/local/bin/box64")
    val wineBin = File(root, "opt/wine/bin/wine")

    /** Builds a process running `wine <args>` under Box64 in this environment. Caller starts it. */
    fun wine(vararg args: String, debug: String = "-all"): ProcessBuilder {
        home.mkdirs(); File(root, "tmp").mkdirs()
        val pb = ProcessBuilder(listOf(box64.absolutePath, wineBin.absolutePath) + args)
        val e = pb.environment()
        e["HOME"] = home.absolutePath
        e["TMPDIR"] = File(root, "tmp").absolutePath
        e["BOX64_LD_LIBRARY_PATH"] = "${root}/opt/wine/lib/wine/x86_64-unix:${root}/usr/lib/x86_64-linux-gnu"
        e["BOX64_PATH"] = "${root}/opt/wine/bin"
        e["BOX64_LOG"] = "0"
        e["BOX64_DYNAREC"] = "1"
        e["WINEPREFIX"] = prefix.absolutePath
        e["WINEDEBUG"] = debug
        e["WINEDLLOVERRIDES"] = "mscoree,mshtml="
        e["WINESERVER"] = File(root, "opt/wine/bin/wineserver").absolutePath
        pb.redirectErrorStream(true)
        return pb
    }

    /** `wineserver <args>` under Box64 (e.g. `-w` waits until the prefix is idle). */
    fun wineserver(vararg args: String): ProcessBuilder {
        val pb = wine()
        pb.command(listOf(box64.absolutePath, File(root, "opt/wine/bin/wineserver").absolutePath) + args)
        return pb
    }
}
