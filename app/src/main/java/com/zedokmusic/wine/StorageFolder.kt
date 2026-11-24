package com.zedokmusic.wine

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * The user-chosen workspace folder (picked with the Storage Access Framework).
 * Wine needs real POSIX paths, so the picked tree URI is resolved to its file path; this works because the app
 * targets SDK 28 (legacy storage access). Layout inside the folder: C/ (Wine's C: drive), Plugins/, Projects/, Samples/, Exports/.
 */
object StorageFolder {
    private const val PREFS = "zedok"
    private const val KEY = "workspace"

    fun saved(ctx: Context): File? = ctx.getSharedPreferences(PREFS, 0).getString(KEY, null)?.let { File(it) }
    fun save(ctx: Context, dir: File) = ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY, dir.absolutePath).apply()

    /** Resolves a SAF tree URI (from ACTION_OPEN_DOCUMENT_TREE) to a file path, or null if it is not a local volume. */
    fun resolve(treeUri: Uri): File? {
        val docId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (e: Exception) { return null }
        val (volume, rel) = docId.split(":", limit = 2).let { it[0] to (it.getOrNull(1) ?: "") }
        val base = if (volume == "primary") File("/storage/emulated/0") else File("/storage/$volume")
        if (!base.exists()) return null
        return if (rel.isEmpty()) base else File(base, rel)
    }

    /** Creates the workspace layout; returns an error message or null. */
    fun prepare(dir: File): String? {
        if (!dir.exists() && !dir.mkdirs()) return "Cannot create ${dir.path}"
        if (!dir.canWrite()) return "ZedokMusic cannot write to ${dir.path}. Choose another folder."
        listOf("C", "Plugins/VST3", "Plugins/VST2", "Projects", "Samples", "Exports").forEach { File(dir, it).mkdirs() }
        return null
    }
}
