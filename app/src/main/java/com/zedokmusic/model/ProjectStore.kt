package com.zedokmusic.model

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where projects live, laid out like a FL Studio project folder: everything a project needs sits in one folder.
 *
 *     Projects/<Name>/<Name>.zedok     the project
 *                    Samples/          imported samples and recordings (Samples/Recordings)
 *                    Exports/          rendered audio
 *                    Presets/<plugin>/ plugin presets (*.zpreset)
 *                    Backup/           automatic copies of earlier saves
 */
class ProjectStore(val workspace: File) {
    class Info(val name: String, val file: File, val modified: Long)

    val root get() = File(workspace, "Projects")
    fun dir(name: String) = File(root, name)
    fun file(name: String) = File(dir(name), "$name.zedok")
    fun samples(name: String) = File(dir(name), "Samples")
    fun exports(name: String) = File(dir(name), "Exports")
    fun presets(name: String) = File(dir(name), "Presets")
    fun backups(name: String) = File(dir(name), "Backup")

    /** Makes a file-system-safe project name (letters, digits, spaces and . _ - ( ) +). */
    fun sanitize(name: String): String = name.trim().replace(Regex("[^A-Za-z0-9 _.()+-]"), "_").trim('.', ' ').take(60)
    fun exists(name: String) = file(name).exists()

    /** [base], or "base (2)", "base (3)"… when taken. */
    fun uniqueName(base: String): String {
        val b = sanitize(base).ifEmpty { "Untitled" }
        if (!exists(b) && !dir(b).exists()) return b
        var n = 2
        while (exists("$b ($n)") || dir("$b ($n)").exists()) n++
        return "$b ($n)"
    }

    /** FL Studio's default name for a new project: "Project_1", else the first "Project_N" (N = 2, 3...) not taken yet. */
    fun nextProjectName(): String { var n = 1; while (exists("Project_$n") || dir("Project_$n").exists()) n++; return "Project_$n" }

    /** "Song" -> "Song_2", "Song_2" -> "Song_3" (FL Studio's Save new version). */
    fun nextVersionName(name: String): String {
        val m = Regex("^(.*)_(\\d+)$").find(name)
        var base = m?.groupValues?.get(1) ?: name
        var n = (m?.groupValues?.get(2)?.toIntOrNull() ?: 1) + 1
        while (exists("${base}_$n") || dir("${base}_$n").exists()) n++
        return "${base}_$n"
    }

    /** All projects, most recently saved first. */
    fun list(): List<Info> = root.listFiles { f -> f.isDirectory }?.mapNotNull { d ->
        val f = File(d, "${d.name}.zedok"); if (f.isFile) Info(d.name, f, f.lastModified()) else null
    }?.sortedByDescending { it.modified } ?: emptyList()

    /** Before projects had folders: Projects/Untitled.zedok sat next to a Projects/Untitled/ folder of samples. Moves it inside. */
    fun migrateLegacy() {
        root.listFiles { f -> f.isFile && f.extension == "zedok" }?.forEach { old ->
            val name = old.nameWithoutExtension
            val target = file(name)
            if (target.exists()) return@forEach
            dir(name).mkdirs()
            if (!old.renameTo(target)) { old.copyTo(target); old.delete() }
        }
    }

    /** Creates the folders of a project. */
    fun create(name: String) { listOf(samples(name), exports(name), presets(name), backups(name)).forEach { it.mkdirs() } }

    /**
     * Keeps a timestamped copy of the current project file in Backup/ (at most one per [minGapMs], newest [keep] kept).
     * Call before overwriting the project file. Returns the copy, or null when none was made.
     */
    fun backup(name: String, now: Long = System.currentTimeMillis(), minGapMs: Long = 10 * 60 * 1000L, keep: Int = 20): File? {
        val f = file(name); if (!f.isFile) return null
        val dir = backups(name); dir.mkdirs()
        val existing = dir.listFiles { x -> x.isFile && x.extension == "zedok" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (existing.isNotEmpty() && now - existing.first().lastModified() < minGapMs) return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        var out = File(dir, "${name}_$stamp.zedok"); var n = 2
        while (out.exists()) out = File(dir, "${name}_${stamp}_${n++}.zedok")
        f.copyTo(out); out.setLastModified(now)
        (existing + out).sortedByDescending { it.lastModified() }.drop(keep).forEach { it.delete() }
        return out
    }
    fun listBackups(name: String): List<File> = backups(name).listFiles { x -> x.isFile && x.extension == "zedok" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Total size in bytes of a folder (0 when missing). */
    fun size(dir: File): Long = if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
