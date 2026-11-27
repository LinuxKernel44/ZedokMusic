package com.zedokmusic.model

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A file or folder of a kit source: a folder picked with the Storage Access Framework, or a plain folder (tests, debug). */
interface KitNode {
    val name: String
    val isDir: Boolean
    /** A string that finds this file again later (a document URI or a path): used for favourites. */
    val ref: String
    fun children(): List<KitNode>
    fun open(): InputStream?
    fun feed(ex: MediaExtractor)
    fun feed(p: MediaPlayer)
}

class SafNode(private val ctx: Context, private val doc: DocumentFile) : KitNode {
    override val name get() = doc.name ?: ""
    override val isDir get() = doc.isDirectory
    override val ref get() = doc.uri.toString()
    override fun children(): List<KitNode> = runCatching { doc.listFiles().map { SafNode(ctx, it) } }.getOrDefault(emptyList())
    override fun open(): InputStream? = runCatching { ctx.contentResolver.openInputStream(doc.uri) }.getOrNull()
    override fun feed(ex: MediaExtractor) { ex.setDataSource(ctx, doc.uri, null) }
    override fun feed(p: MediaPlayer) { p.setDataSource(ctx, doc.uri) }
    companion object {
        fun tree(ctx: Context, uri: Uri): SafNode? = DocumentFile.fromTreeUri(ctx, uri)?.let { SafNode(ctx, it) }
        fun file(ctx: Context, uri: Uri): SafNode? = DocumentFile.fromSingleUri(ctx, uri)?.let { SafNode(ctx, it) }
        /** Finds a file again from [KitNode.ref]. */
        fun fromRef(ctx: Context, ref: String): KitNode? = if (ref.startsWith("content:")) file(ctx, Uri.parse(ref)) else File(ref).takeIf { it.isFile }?.let { FileNode(it) }
    }
}

/** A file known only by its name and [ref] (a document URI or a path): what the library index and the favourites hold, without asking the file provider anything until the file is used. */
class RefNode(private val ctx: Context, override val name: String, override val ref: String) : KitNode {
    override val isDir get() = false
    override fun children(): List<KitNode> = emptyList()
    override fun open(): InputStream? = runCatching { if (ref.startsWith("content:")) ctx.contentResolver.openInputStream(Uri.parse(ref)) else File(ref).inputStream() }.getOrNull()
    override fun feed(ex: MediaExtractor) { if (ref.startsWith("content:")) ex.setDataSource(ctx, Uri.parse(ref), null) else ex.setDataSource(ref) }
    override fun feed(p: MediaPlayer) { if (ref.startsWith("content:")) p.setDataSource(ctx, Uri.parse(ref)) else p.setDataSource(ref) }
}

class FileNode(private val f: File) : KitNode {
    override val name get() = f.name
    override val isDir get() = f.isDirectory
    override val ref get() = f.absolutePath
    override fun children(): List<KitNode> = f.listFiles()?.sortedBy { it.name.lowercase() }?.map { FileNode(it) } ?: emptyList()
    override fun open(): InputStream? = runCatching { f.inputStream() }.getOrNull()
    override fun feed(ex: MediaExtractor) { ex.setDataSource(f.absolutePath) }
    override fun feed(p: MediaPlayer) { p.setDataSource(f.absolutePath) }
}

/** What can be imported from a folder: the files of one kit (path relative to the kit's folder -> file), or an SFZ instrument. */
class KitCandidate(val name: String, val kind: String, val files: List<Pair<String, KitNode>>, val sfz: Pair<String, KitNode>? = null) {
    val count get() = files.size
}

object KitFormats {
    val audio = setOf("wav", "wave", "aif", "aiff", "flac", "mp3", "ogg", "oga", "m4a", "aac", "opus")
    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in audio
    fun isSfz(name: String) = name.endsWith(".sfz", true)
}

object KitScanner {
    private const val MAX_FILES = 6000

    private fun collect(dir: KitNode, prefix: String, depth: Int, out: MutableList<Pair<String, KitNode>>) {
        if (out.size >= MAX_FILES || depth > 6) return
        for (c in dir.children()) {
            if (c.isDir) { if (!c.name.startsWith(".")) collect(c, prefix + c.name + "/", depth + 1, out) }
            else if (KitFormats.isAudio(c.name) && !c.name.startsWith(".")) out.add(prefix + c.name to c)
        }
    }
    private fun audioOf(dir: KitNode): List<Pair<String, KitNode>> = listAudio(dir)
    /** Every audio file under [dir] (path relative to it -> file), sorted by path. */
    fun listAudio(dir: KitNode): List<Pair<String, KitNode>> = ArrayList<Pair<String, KitNode>>().also { collect(dir, "", 0, it) }.sortedBy { it.first.lowercase() }
    private fun directAudio(dir: KitNode) = dir.children().filter { !it.isDir && KitFormats.isAudio(it.name) && !it.name.startsWith(".") }.map { it.name to it }

    /** Walks down a relative path ("Samples/kick.wav") from [base]; folder and file names are matched ignoring case. */
    fun resolve(base: KitNode, path: String): KitNode? {
        var cur: KitNode = base
        for (seg in path.split('/').filter { it.isNotEmpty() && it != "." }) {
            cur = cur.children().firstOrNull { it.name.equals(seg, true) } ?: return null
        }
        return cur
    }

    private fun findSfz(dir: KitNode, prefix: String, depth: Int, out: MutableList<Pair<String, KitNode>>) {
        if (depth > 3) return
        for (c in dir.children()) { if (c.isDir) findSfz(c, prefix + c.name + "/", depth + 1, out) else if (KitFormats.isSfz(c.name)) out.add(prefix + c.name to c) }
    }

    /** Finds the kits in a picked folder: SFZ instruments; one flat pack of one-shots (also when sorted into Kicks/Snares/... folders); or a multikit (a folder of kit folders). */
    fun scan(root: KitNode): List<KitCandidate> {
        val out = ArrayList<KitCandidate>()
        val sfzs = ArrayList<Pair<String, KitNode>>().also { findSfz(root, "", 0, it) }
        for ((rel, node) in sfzs) {
            val text = node.open()?.use { String(it.readBytes(), Charsets.UTF_8) } ?: continue
            val base = rel.substringBeforeLast('/', "")
            val sfzDir = if (base.isEmpty()) root else resolve(root, base) ?: root
            val files = KitMapper.parseSfz(text).mapNotNull { r -> resolve(sfzDir, r.sample)?.takeIf { !it.isDir }?.let { (if (base.isEmpty()) r.sample else "$base/${r.sample}") to it } }.distinctBy { it.first }
            if (files.isNotEmpty()) out.add(KitCandidate(node.name.substringBeforeLast('.'), "SFZ instrument", files, rel to node))
        }
        if (out.isNotEmpty()) return out
        val rootName = root.name.ifEmpty { "Kit" }
        val direct = directAudio(root); val subdirs = root.children().filter { it.isDir && !it.name.startsWith(".") }
        val categoryDirs = subdirs.count { KitMapper.roleOf(it.name + "/x") != KitMapper.Role.Other }
        if (direct.isNotEmpty() || categoryDirs >= 2) {
            val all = audioOf(root); if (all.isNotEmpty()) out.add(KitCandidate(rootName, "One-shot kit", all))
            if (direct.isNotEmpty()) for (d in subdirs) { val a = audioOf(d); if (a.size >= 4 && KitMapper.roleOf(d.name + "/x") == KitMapper.Role.Other) out.add(KitCandidate(d.name, "Kit in a sub-folder", a)) }
        } else {
            for (d in subdirs) { val a = audioOf(d); if (a.isNotEmpty()) out.add(KitCandidate(d.name, "Multikit entry", a)) }
        }
        return out.sortedBy { it.name.lowercase() }
    }
}

object KitImporter {
    private fun le(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    /** True when the engine's own WAV reader can read this file as it is: PCM 16/24/32 or float32, mono or stereo. */
    fun isPlainWav(head: ByteArray): Boolean {
        if (head.size < 44 || String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return false
        var pos = 12
        while (pos + 8 <= head.size) {
            val id = String(head, pos, 4); val len = le(head, pos + 4) or (le(head, pos + 6) shl 16)
            if (id == "fmt " && pos + 24 <= head.size) {
                var tag = le(head, pos + 8); val ch = le(head, pos + 10); val bits = le(head, pos + 22)
                if (tag == 0xFFFE && len >= 26 && pos + 8 + 26 <= head.size) tag = le(head, pos + 32)
                return (ch == 1 || ch == 2) && ((tag == 1 && bits in intArrayOf(16, 24, 32)) || (tag == 3 && bits == 32))
            }
            pos += 8 + len + (len and 1)
        }
        return false
    }

    /** Copies [node] to [out]; a WAV the engine can read is copied byte for byte, anything else is decoded to a 16-bit WAV (out gets a .wav name). Returns the file written. */
    fun copyOrConvert(node: KitNode, out: File): File? {
        out.parentFile?.mkdirs()
        val ext = node.name.substringAfterLast('.', "").lowercase()
        if (ext == "wav" || ext == "wave") {
            val head = node.open()?.use { s -> ByteArray(8192).let { b -> val n = s.read(b); if (n > 0) b.copyOf(n) else ByteArray(0) } } ?: return null
            if (isPlainWav(head)) { node.open()?.use { i -> out.outputStream().use { o -> i.copyTo(o) } } ?: return null; return out }
        }
        val wavOut = File(out.parentFile, out.nameWithoutExtension + ".wav")
        return if (decodeToWav(node, wavOut)) wavOut else null
    }

    /** Decodes any audio format Android can play (mp3, flac, ogg, aiff, m4a, odd WAVs) into a 16-bit WAV, mixing more than two channels down to stereo. */
    fun decodeToWav(node: KitNode, out: File): Boolean {
        val ex = MediaExtractor()
        try {
            node.feed(ex)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return false
            ex.selectTrack(track); val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val pcm = java.io.ByteArrayOutputStream(); val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false; var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT); var floatOut = false
            var guard = 0
            while (!outDone && guard++ < 2_000_000) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!; val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true } else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val b = codec.getOutputBuffer(o)!!; val bytes = ByteArray(info.size); b.position(info.offset); b.get(bytes, 0, info.size); pcm.write(bytes)
                    codec.releaseOutputBuffer(o, false); if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat; rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatOut = nf.containsKey(MediaFormat.KEY_PCM_ENCODING) && nf.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                }
            }
            codec.stop(); codec.release()
            val raw = ByteBuffer.wrap(pcm.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val sampleBytes = if (floatOut) 4 else 2; val frames = raw.remaining() / (sampleBytes * ch)
            if (frames <= 0 || ch < 1) return false
            val outCh = if (ch == 1) 1 else 2; val data = ByteBuffer.allocate(frames * outCh * 2).order(ByteOrder.LITTLE_ENDIAN)
            val tmp = FloatArray(ch)
            for (f in 0 until frames) {
                for (c in 0 until ch) tmp[c] = if (floatOut) raw.getFloat() else raw.getShort() / 32768f
                val l: Float; val r: Float
                if (ch <= 2) { l = tmp[0]; r = if (ch == 2) tmp[1] else tmp[0] } else { l = (tmp[0] + tmp[2] * 0.7f) / 1.7f; r = (tmp[1] + tmp[2] * 0.7f) / 1.7f }
                data.putShort((l.coerceIn(-1f, 1f) * 32767f).toInt().toShort()); if (outCh == 2) data.putShort((r.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { o ->
                val hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN); val bytes = data.capacity()
                hdr.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(outCh.toShort()).putInt(rate).putInt(rate * outCh * 2).putShort((outCh * 2).toShort()).putShort(16)
                hdr.put("data".toByteArray()).putInt(bytes); o.write(hdr.array()); o.write(data.array())
            }
            return true
        } catch (e: Exception) { return false }
        finally { runCatching { ex.release() } }
    }

    /** Length in seconds of a PCM/float WAV file (0 when it cannot be read). */
    fun wavSeconds(f: File): Double = runCatching {
        f.inputStream().buffered().use { s ->
            val h = ByteArray(12); if (s.read(h) < 12) return 0.0
            var byteRate = 0L
            while (true) {
                val ch = ByteArray(8); if (s.read(ch) < 8) return 0.0
                val len = (le(ch, 4).toLong() and 0xFFFF) or ((le(ch, 6).toLong() and 0xFFFF) shl 16)
                val id = String(ch, 0, 4)
                if (id == "fmt ") { val b = ByteArray(len.toInt().coerceAtMost(40)); s.read(b); byteRate = (le(b, 8).toLong() and 0xFFFF) or ((le(b, 10).toLong() and 0xFFFF) shl 16); if (len > b.size) s.skip(len - b.size) }
                else if (id == "data") return if (byteRate > 0) len.toDouble() / byteRate else 0.0
                else s.skip(len + (len and 1L))
                if (id == "fmt " && (len and 1L) == 1L) s.skip(1)
            }
            0.0
        }
    }.getOrDefault(0.0)
    /** Is [out] already a copy of [node] (same name, same size)? Dropping the same sound twice then reuses the file. */
    fun sameSource(node: KitNode, out: File): Boolean = runCatching { node.open()?.use { it.available() >= 0 && out.length() > 0 && node.name.substringAfterLast('.', "").lowercase() in setOf("wav", "wave") && it.available().toLong() == out.length() } == true }.getOrDefault(false)

    fun safeName(s: String) = s.trim().replace(Regex("[^A-Za-z0-9 _.()+&'-]"), "_").trim('.', ' ').take(60).ifEmpty { "Kit" }
    /** A folder name for [seg] that is safe on every file system. */
    fun safePath(rel: String) = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { safeName(it) }
}
