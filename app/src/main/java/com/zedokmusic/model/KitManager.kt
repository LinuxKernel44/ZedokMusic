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
    /** Puts the picked files on [pad] (0-based): velocity layers and round robins are recognised from the names. Files are copied into the project. */
    fun assignFiles(t: TrackM, pad: Int, uris: List<Uri>) {
        val kitsRoot = kitsDir() ?: return
        worker.execute {
            try {
                val kitName = t.states["kit"]?.takeIf { it.isNotBlank() } ?: "Custom kit"
                val folderName = KitImporter.safeName(kitName)
                val dest = File(kitsRoot, folderName + "/Pad ${pad + 1}").apply { mkdirs() }
                val nodes = uris.mapNotNull { u -> SafNode.file(ctx, u) }.filter { KitFormats.isAudio(it.name) }
                val written = ArrayList<Pair<KitNode, File>>()
                for (n in nodes) { val out = File(dest, KitImporter.safeName(n.name)); KitImporter.copyOrConvert(n, out)?.let { written.add(n to it) } }
                if (written.isEmpty()) { ui { vm.message = "None of those files could be read." }; return@execute }
                val zones = KitMapper.padPlan(written.map { KitMapper.Entry(it.second.name) }).map { z ->
                    KitMapper.Zone(KitMapper.Entry("Kits/$folderName/Pad ${pad + 1}/${z.entry.path}"), z.vlo, z.vhi)
                }
                setPad(t, pad, zones); ensureSampleType(t, pad)
                ui { t.states["kit"] = kitName; vm.message = "${zones.size} sample${if (zones.size > 1) "s" else ""} on pad ${pad + 1}, copied into the project."; revision++ }
                run { NativeEngine.setPluginState(t.id, "kit", kitName) }
                saveKitFile(t)
            } catch (e: Exception) { ui { vm.message = "Could not add the samples: ${e.message}" } }
        }
    }

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
}
