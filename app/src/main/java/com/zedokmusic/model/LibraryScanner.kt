package com.zedokmusic.model

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fast scan of a folder chosen with the Storage Access Framework. DocumentFile asks the file provider once per property of every file (name, type...),
 * which takes minutes on thousands of sounds. This asks once per FOLDER for all its children at once (one query returns names and types), and works on
 * several folders at the same time on a pool of threads. No limit on the number of sounds.
 */
object LibraryScanner {
    private const val MAX_DEPTH = 24
    private const val MAX_SOUNDS = 400_000

    class Found(val path: String, val name: String, val ref: String)

    /** Every audio file under [tree] (path relative to it, name, a document URI that finds it again), sorted by path. [onCount] gets the number found so far. */
    fun scan(ctx: Context, tree: Uri, onCount: (Int) -> Unit = {}): List<Found> {
        val out = ConcurrentLinkedQueue<Found>()
        val count = AtomicInteger(0)
        val pending = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(minOf(8, maxOf(4, Runtime.getRuntime().availableProcessors() * 2)))
        val cr = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        lateinit var visit: (String, String, Int) -> Unit
        visit = { docId, prefix, depth ->
            pending.incrementAndGet()
            pool.execute {
                try {
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue; val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                            if (name.startsWith(".")) continue
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < MAX_DEPTH) visit(id, "$prefix$name/", depth + 1) }
                            else if (KitFormats.isAudio(name) && count.get() < MAX_SOUNDS) {
                                out.add(Found(prefix + name, name, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString())); onCount(count.incrementAndGet())
                            }
                        }
                    }
                } catch (e: Exception) { /* a folder that cannot be read is skipped */ }
                finally { pending.decrementAndGet() }
            }
        }
        visit(rootId, "", 0)
        // the scan is over when no folder task is queued or running (tasks add their sub-folders before they finish)
        while (pending.get() > 0) Thread.sleep(20)
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        return out.sortedBy { it.path.lowercase() }
    }
}
