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
    fun wine(vararg args: String, debug: String = "-all", extraEnv: ((MutableMap<String, String>) -> Unit)? = null): ProcessBuilder {
        home.mkdirs(); File(root, "tmp").mkdirs()
        val pb = ProcessBuilder(listOf(box64.absolutePath, wineBin.absolutePath) + args)
        val e = pb.environment()
        e["HOME"] = home.absolutePath
        e["TMPDIR"] = File(root, "tmp").absolutePath
        e["BOX64_LD_LIBRARY_PATH"] = "${root}/opt/wine/lib/wine/x86_64-unix:${root}/usr/lib/x86_64-linux-gnu"
        e["BOX64_PATH"] = "${root}/opt/wine/bin"
        e["BOX64_LOG"] = File(files, "box64log").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null } ?: "0"  // developer aid: a file holding the value (1 = loaded libraries)
        e["BOX64_SHOWSEGV"] = "1"  // a crash in emulated code is then written to the plugin log
        e["BOX64_DYNAREC"] = "1"
        e["WINEPREFIX"] = prefix.absolutePath
        e["WINEDEBUG"] = debug
        e["WINEDLLOVERRIDES"] = "mscoree,mshtml="
        e["WINESERVER"] = File(root, "opt/wine/bin/wineserver").absolutePath
        extraEnv?.invoke(e)
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
