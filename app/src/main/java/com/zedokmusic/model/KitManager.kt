package com.zedokmusic.model

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zedokmusic.engine.NativeEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Drum kits for Zedok Drums: import a kit folder (one-shot pack, multikit, SFZ) or single files with the Storage Access Framework,
 * lay it out on the 16 pads, and COPY every file the kit uses into the project's own Kits/<kit>/ folder so the project is self-contained.
 * A kit is stored in the plugin's state as one text per pad (project-relative paths) and, for switching, as Kits/<kit>/kit.zkit.
 */
class KitManager(private val vm: DawViewModel, private val ctx: Context) {
    enum class Phase { Idle, Scanning, Choose, Importing, Failed }
    var phase by mutableStateOf(Phase.Idle); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf(""); private set
    var candidates by mutableStateOf<List<KitCandidate>>(emptyList()); private set
    /** Bumped when the project's list of kits changes. */
    var revision by mutableStateOf(0); private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "zedok-kits").also { it.isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var target: TrackM? = null
    @Volatile private var cancelled = false
    /** Runs [block] on the kit worker (the plugin loads the audio there, off the UI and audio threads). */
    fun run(block: () -> Unit) { worker.execute { runCatching(block) } }

    private fun ui(block: () -> Unit) { main.post(block) }
    private fun kitsDir(): File? = vm.store?.kits(vm.projectName)
    private fun projectDir(): File? = vm.projectDir()

    // ------------------------------------------------------------------ import from a picked folder
    fun scanTree(t: TrackM, uri: Uri) { val root = SafNode.tree(ctx, uri); if (root == null) fail("That folder cannot be read.") else scan(t, root) }
    fun scanFolder(t: TrackM, dir: File) = scan(t, FileNode(dir))   // plain folders (developer hook)

    private fun scan(t: TrackM, root: KitNode) {
        target = t; cancelled = false; phase = Phase.Scanning; progress = 0f; status = "Looking for kits in ${root.name}…"; candidates = emptyList()
        worker.execute {
            val found = try { KitScanner.scan(root) } catch (e: Exception) { emptyList() }
            ui {
                when {
                    cancelled -> phase = Phase.Idle
                    found.isEmpty() -> fail("No audio files or SFZ instruments found in that folder.")
                    found.size == 1 -> import(found[0])
                    else -> { candidates = found; phase = Phase.Choose; status = "${found.size} kits found" }
                }
            }
        }
    }

    fun cancel() { cancelled = true; if (phase != Phase.Importing) phase = Phase.Idle }
    fun dismiss() { if (phase != Phase.Importing) { phase = Phase.Idle; candidates = emptyList() } }
    private fun fail(msg: String) { status = msg; phase = Phase.Failed }

    fun import(c: KitCandidate) {
        val t = target ?: return; val s = vm.store ?: return fail("There is no workspace folder.")
        phase = Phase.Importing; progress = 0f; status = "Planning ${c.name}…"; cancelled = false
        worker.execute {
            try {
                val kitsRoot = kitsDir() ?: throw IllegalStateException("no project folder")
                val base = ImportBase(c)
                val plan = base.plan()
                val used = plan.pads.flatMap { it }.map { it.entry.path }.distinct()
                var folder = KitImporter.safeName(c.name); var n = 2
                while (File(kitsRoot, folder).exists()) folder = KitImporter.safeName(c.name) + " ($n)".also { n++ }
                val dest = File(kitsRoot, folder).apply { mkdirs() }
                val rename = HashMap<String, String>()   // original relative path -> path relative to the project
                var done = 0
                for (p in used) {
                    if (cancelled) throw InterruptedException()
                    val node = base.nodeOf(p)
                    val target = File(dest, KitImporter.safePath(p))
                    val written = if (node != null) KitImporter.copyOrConvert(node, target) else null
                    if (written != null) rename[p] = "Kits/$folder/" + written.relativeTo(dest).path.replace(File.separatorChar, '/')
                    done++; val d = done; ui { progress = d / used.size.toFloat(); status = "Copying into the project… $d / ${used.size}" }
                }
                val pads = Array(16) { i -> plan.pads[i].mapNotNull { z -> rename[z.entry.path]?.let { KitMapper.Zone(KitMapper.Entry(it), z.vlo, z.vhi) } } }
                if (pads.all { it.isEmpty() }) throw IllegalStateException("none of the files could be copied or decoded")
                val missing = used.size - rename.size
                writeKitFile(dest, c.name, pads)
                applyPads(t, c.name, pads)
                val msg = "Kit \"${c.name}\": ${pads.count { it.isNotEmpty() }} pads, ${rename.size} files copied into the project" + (if (missing > 0) ", $missing could not be read" else "") + (if (plan.note.isNotEmpty()) ". ${plan.note}" else "") + "."
                ui { phase = Phase.Idle; candidates = emptyList(); vm.message = msg; revision++ }
            } catch (e: InterruptedException) { ui { phase = Phase.Idle } }
            catch (e: Exception) { ui { fail("Could not import the kit: ${e.message}") } }
        }
    }

    /** Maps a candidate's files to pads and finds the source file of each path (for SFZ instruments the regions decide). */
    private inner class ImportBase(val c: KitCandidate) {
        val byPath = c.files.toMap()
        fun nodeOf(path: String): KitNode? = byPath[path]
        fun plan(): KitMapper.Plan {
            val sfz = c.sfz ?: return KitMapper.plan(c.name, c.files.map { KitMapper.Entry(it.first) })
            val text = sfz.second.open()?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            val prefix = sfz.first.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val p = KitMapper.planFromSfz(c.name, KitMapper.parseSfz(text))
            return KitMapper.Plan(p.name, Array(16) { i -> p.pads[i].map { KitMapper.Zone(KitMapper.Entry(prefix + it.entry.path), it.vlo, it.vhi) } }, p.dropped, p.note)
        }
    }

    // ------------------------------------------------------------------ single files on one pad
    fun assignFiles(t: TrackM, pad: Int, uris: List<Uri>) = assignNodes(t, pad, uris.mapNotNull { SafNode.file(ctx, it) }, append = false)

    /** Puts the files on [pad] (0-based), replacing what is there or ([append]) adding to it: velocity layers and round robins are recognised from the names. Files are copied into the project. */
    fun assignNodes(t: TrackM, pad: Int, files: List<KitNode>, append: Boolean, done: (Boolean) -> Unit = {}) {
        val kitsRoot = kitsDir() ?: return
        worker.execute {
            try {
                val kitName = t.states["kit"]?.takeIf { it.isNotBlank() } ?: "Custom kit"
                val folderName = KitImporter.safeName(kitName)
                val dest = File(kitsRoot, folderName + "/Pad ${pad + 1}").apply { mkdirs() }
                val nodes = files.filter { KitFormats.isAudio(it.name) }
                val written = ArrayList<Pair<KitNode, File>>()
                for (n in nodes) {
                    var out = File(dest, KitImporter.safeName(n.name)); var i = 2
                    while (out.exists() && append) { out = File(dest, KitImporter.safeName(n.name.substringBeforeLast('.')) + " ($i)." + n.name.substringAfterLast('.', "wav")); i++ }
                    KitImporter.copyOrConvert(n, out)?.let { written.add(n to it) }
                }
                if (written.isEmpty()) { ui { vm.message = "None of those files could be read."; done(false) }; return@execute }
                val fresh = KitMapper.padPlan(written.map { KitMapper.Entry(it.second.name) }).map { z ->
                    KitMapper.Zone(KitMapper.Entry("Kits/$folderName/Pad ${pad + 1}/${z.entry.path}"), z.vlo, z.vhi)
                }
                val zones = if (append) zones(t, pad) + fresh else fresh
                setPad(t, pad, zones); ensureSampleType(t, pad)
                ui { t.states["kit"] = kitName; vm.message = "${fresh.size} sample${if (fresh.size > 1) "s" else ""} ${if (append) "added to" else "on"} pad ${pad + 1}, copied into the project."; revision++; done(true) }
                run { NativeEngine.setPluginState(t.id, "kit", kitName) }
                saveKitFile(t)
            } catch (e: Exception) { ui { vm.message = "Could not add the samples: ${e.message}"; done(false) } }
        }
    }

    // ------------------------------------------------------------------ sound browser: preview, favourites, load to a pad
    /** The sound browser: the drum kit library (the folder chosen in Settings, scanned once) or only the favourites. */
    class BrowseState(val t: TrackM, val favoritesFirst: Boolean)
    var browse by mutableStateOf<BrowseState?>(null); private set
    var browseStatus by mutableStateOf(""); private set
    val preview = SamplePreview(ctx)
    private val prefs = ctx.getSharedPreferences("kits", Context.MODE_PRIVATE)

    // ---- the drum kit library: ONE folder, chosen in Settings, scanned once (the index is stored) and scanned again only when the folder is changed there
    var libraryItems by mutableStateOf<List<Pair<String, KitNode>>>(emptyList()); private set
    var libraryName by mutableStateOf(prefs.getString("libraryName", "") ?: ""); private set
    var libraryScanning by mutableStateOf(false); private set
    /** Which folders of the library tree are unfolded in the browser (kept while the app runs; reset when the library changes). */
    val expandedDirs = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var libraryStatus by mutableStateOf(""); private set
    fun libraryUri(): Uri? = prefs.getString("libraryUri", null)?.let { Uri.parse(it) }
    private val indexFile get() = File(ctx.filesDir, "kit_library.json")

    /** Loads the stored index at start (instant); scans when there is a folder but no usable index. */
    fun loadLibrary() {
        val uri = libraryUri() ?: return
        worker.execute {
            val items = runCatching {
                val o = JSONObject(indexFile.readText())
                if (o.getString("uri") != uri.toString()) null
                else o.getJSONArray("items").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { x -> x.getString(0) to RefNode(ctx, x.getString(1), x.getString(2)) as KitNode } } }
            }.getOrNull()
            if (items != null) ui { libraryItems = items; libraryStatus = "${items.size} sounds" } else ui { rescanLibrary() }
        }
    }
    /** Called from Settings when the user picks the folder: remembered, access kept, and scanned (again). */
    fun setLibrary(uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val old = libraryUri(); if (old != null && old != uri) runCatching { ctx.contentResolver.releasePersistableUriPermission(old, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = SafNode.tree(ctx, uri)?.name ?: "Drum kits"
        prefs.edit().putString("libraryUri", uri.toString()).putString("libraryName", name).apply()
        libraryName = name; libraryItems = emptyList(); expandedDirs.clear(); rescanLibrary()
    }
    fun forgetLibrary() {
        libraryUri()?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        prefs.edit().remove("libraryUri").remove("libraryName").apply(); runCatching { indexFile.delete() }
        libraryName = ""; libraryItems = emptyList(); libraryStatus = ""; closeBrowser()
    }
    fun rescanLibrary() {
        val uri = libraryUri() ?: return
        if (libraryScanning) return
        libraryScanning = true; libraryStatus = "Scanning…"
        worker.execute {
            try {
                val root = SafNode.tree(ctx, uri) ?: throw IllegalStateException("the folder cannot be read")
                val found = KitScanner.listAudio(root).map { (p, n) -> p to RefNode(ctx, n.name, n.ref) as KitNode }
                val arr = JSONArray(); found.forEach { (p, n) -> arr.put(JSONArray().put(p).put(n.name).put(n.ref)) }
                runCatching { indexFile.writeText(JSONObject().put("uri", uri.toString()).put("items", arr).toString()) }
                ui { libraryItems = found; libraryStatus = "${found.size} sounds"; libraryScanning = false }
            } catch (e: Exception) { ui { libraryStatus = "Could not scan the folder: ${e.message}"; libraryScanning = false } }
        }
    }

    /** True when the library can be browsed; false when no folder was chosen yet (the UI tells the user to choose it in Settings). */
    fun openLibrary(t: TrackM): Boolean { if (libraryUri() == null) return false; browseStatus = ""; browse = BrowseState(t, false); return true }
    fun browseFavorites(t: TrackM) { browseStatus = ""; browse = BrowseState(t, true) }
    fun closeBrowser() { preview.stop(); browse = null; browseStatus = "" }
    /** Imports the whole browsed folder as a kit (the one-shot / multikit / SFZ detection of Load kit). */
    fun importBrowsed(folder: String) {
        val b = browse ?: return; val uri = libraryUri() ?: return
        val root = SafNode.tree(ctx, uri) ?: return
        closeBrowser(); scan(b.t, if (folder.isEmpty()) root else KitScanner.resolve(root, folder) ?: root)
    }
    fun loadToPad(pad: Int, nodes: List<KitNode>, append: Boolean, done: () -> Unit) {
        val b = browse ?: return
        browseStatus = "Copying ${nodes.size} sound${if (nodes.size > 1) "s" else ""} into the project…"
        assignNodes(b.t, pad, nodes, append) { ok -> browseStatus = if (ok) "${if (append) "Added to" else "Loaded on"} pad ${pad + 1}." else "Could not read those files."; if (ok) done() }
    }

    // favourites: <workspace>/Presets/sample_favorites.json  [{ref, name, folder}]
    class Fav(val ref: String, val name: String, val folder: String)
    private val favFile get() = vm.workspace?.let { File(it, "Presets/sample_favorites.json") }
    var favorites by mutableStateOf(loadFavs()); private set
    private fun loadFavs(): List<Fav> = runCatching {
        val a = JSONArray(favFile?.takeIf { it.isFile }?.readText() ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Fav(o.getString("ref"), o.optString("name"), o.optString("folder")) } }
    }.getOrDefault(emptyList())
    fun isFavorite(node: KitNode) = favorites.any { it.ref == node.ref }
    fun toggleFavorite(node: KitNode, folder: String) {
        favorites = if (isFavorite(node)) favorites.filter { it.ref != node.ref } else favorites + Fav(node.ref, node.name, folder)
        runCatching { favFile?.let { f -> f.parentFile?.mkdirs(); f.writeText(JSONArray().also { a -> favorites.forEach { a.put(JSONObject().put("ref", it.ref).put("name", it.name).put("folder", it.folder)) } }.toString()) } }
    }
    /** The favourites as browser rows (files that moved or lost their access are left out). */
    fun favoriteItems(): List<Pair<String, KitNode>> = favorites.map { f -> (if (f.folder.isNotEmpty()) f.folder + "/" else "") + f.name to (RefNode(ctx, f.name, f.ref) as KitNode) }

    // ------------------------------------------------------------------ pad state helpers
    fun zones(t: TrackM, pad: Int): List<KitMapper.Zone> = KitMapper.parseZoneText(t.states["pad${pad + 1}"] ?: "")
    fun padLabel(t: TrackM, pad: Int): String? = zones(t, pad).firstOrNull()?.entry?.let { KitMapper.label(it) }
    fun hasKit(t: TrackM) = (0 until 16).any { zones(t, it).isNotEmpty() }
    fun kitName(t: TrackM): String = t.states["kit"] ?: ""

    private fun setPad(t: TrackM, pad: Int, zones: List<KitMapper.Zone>) {
        val text = KitMapper.zoneText(zones)
        ui { t.states["pad${pad + 1}"] = text; vm.markDirty() }
        vm.sendPluginState(t, "pad${pad + 1}", text)
    }
    private fun applyPads(t: TrackM, name: String, pads: Array<List<KitMapper.Zone>>) {
        for (i in 0 until 16) setPad(t, i, pads[i])
        NativeEngine.setPluginState(t.id, "kit", name)
        ui { t.states["kit"] = name; for (i in 0 until 16) ensureSampleType(t, i) }
    }
    /** Switches pad [pad] to the Sample sound (the last choice of its Type parameter). */
    private fun ensureSampleType(t: TrackM, pad: Int) {
        ui { val p = vm.loadParams(t).firstOrNull { it.name == "P${pad + 1} Type" } ?: return@ui; vm.setParam(t, p.index, 1f); vm.bumpParams() }
    }
    fun clearPad(t: TrackM, pad: Int) { run { setPad(t, pad, emptyList()); saveKitFile(t) } }
    fun removeZone(t: TrackM, pad: Int, index: Int) { val z = zones(t, pad).toMutableList(); if (index in z.indices) { z.removeAt(index); run { setPad(t, pad, z); saveKitFile(t) } } }
    fun clearKit(t: TrackM) { run { for (i in 0 until 16) setPad(t, i, emptyList()); NativeEngine.setPluginState(t.id, "kit", ""); ui { t.states["kit"] = "" } } }

    // ------------------------------------------------------------------ kits stored in the project
    private fun writeKitFile(dir: File, name: String, pads: Array<List<KitMapper.Zone>>) {
        val o = JSONObject().put("format", 1).put("name", name)
        val po = JSONObject(); for (i in 0 until 16) if (pads[i].isNotEmpty()) po.put("${i + 1}", KitMapper.zoneText(pads[i])); o.put("pads", po)
        File(dir, "kit.zkit").writeText(o.toString())
    }
    /** Re-writes the kit file of the kit the track currently uses (after a pad was edited). */
    fun saveKitFile(t: TrackM) {
        val kitsRoot = kitsDir() ?: return; val name = t.states["kit"]?.takeIf { it.isNotBlank() } ?: return
        val dir = File(kitsRoot, KitImporter.safeName(name)).apply { mkdirs() }
        writeKitFile(dir, name, Array(16) { zones(t, it) })
        ui { revision++ }
    }
    fun projectKits(): List<Pair<String, File>> = kitsDir()?.listFiles { f -> f.isDirectory && File(f, "kit.zkit").isFile }?.sortedBy { it.name.lowercase() }
        ?.map { (runCatching { JSONObject(File(it, "kit.zkit").readText()).optString("name", it.name) }.getOrDefault(it.name)) to it } ?: emptyList()

    fun loadProjectKit(t: TrackM, dir: File) {
        worker.execute {
            try {
                val o = JSONObject(File(dir, "kit.zkit").readText()); val po = o.optJSONObject("pads")
                val pads = Array(16) { i -> po?.optString("${i + 1}", "")?.let { KitMapper.parseZoneText(it) } ?: emptyList() }
                applyPads(t, o.optString("name", dir.name), pads)
                ui { vm.message = "Loaded kit \"${o.optString("name", dir.name)}\" from the project." }
            } catch (e: Exception) { ui { vm.message = "Could not load that kit: ${e.message}" } }
        }
    }
    fun deleteProjectKit(dir: File) { runCatching { dir.deleteRecursively() }; revision++ }

    init { loadLibrary() }   // last: it uses the properties declared above
}

/** Plays a sound for the browser's preview (Android's own player: it works with any file the folder picker gives access to). */
class SamplePreview(private val ctx: Context) {
    private var mp: android.media.MediaPlayer? = null
    var playing by mutableStateOf<String?>(null); private set
    fun toggle(node: KitNode) {
        val ref = node.ref
        if (playing == ref) { stop(); return }
        stop()
        try {
            val p = android.media.MediaPlayer()
            p.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            node.feed(p)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { if (playing == ref) stop() }
            p.setOnErrorListener { _, _, _ -> if (playing == ref) stop(); true }
            mp = p; playing = ref; p.prepareAsync()
        } catch (e: Exception) { stop() }
    }
    fun stop() { runCatching { mp?.release() }; mp = null; playing = null }
}
