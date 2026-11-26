package com.zedokmusic.ui.nplug

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.KitManager
import com.zedokmusic.model.KitNode
import com.zedokmusic.ui.LabelButton
import com.zedokmusic.ui.Zk
import com.zedokmusic.ui.dialogListMax

/** The kit tools of Zedok Drums: load a kit folder (one-shots, multikit, SFZ), add samples to the selected pad, switch between the kits of the project. */
@Composable
fun KitSection(ps: PluginState, pad: Int, color: Color) {
    val vm = ps.vm; val t = ps.t; val kits = vm.kits
    var projectKits by remember { mutableStateOf(false) }
    var needFolder by remember { mutableStateOf(false) }
    val name = kits.kitName(t); val padsUsed = (0 until 16).count { kits.zones(t, it).isNotEmpty() }
    Section("Sample kit", color) {
        Text(if (name.isNotBlank() || padsUsed > 0) "${name.ifBlank { "Kit" }} · $padsUsed pad${if (padsUsed == 1) "" else "s"} with samples" else "No sample kit. Load a folder of drum samples, or add samples to a pad.", color = Zk.dim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelButton("Load kit…", true, color, w = 92.dp, h = 34.dp) { if (!kits.openLibrary(t)) needFolder = true }
            LabelButton("Project kits", false, w = 100.dp, h = 34.dp) { projectKits = true }
            LabelButton("Load to pad ${pad + 1}…", false, w = 136.dp, h = 34.dp) { if (!kits.openLibrary(t)) needFolder = true }
            LabelButton("Favourites", false, w = 92.dp, h = 34.dp) { kits.browseFavorites(t) }
            LabelButton("Clear pad", false, w = 84.dp, h = 34.dp) { kits.clearPad(t, pad) }
            LabelButton("Clear kit", false, w = 84.dp, h = 34.dp) { kits.clearKit(t) }
        }
        val z = remember(t.states["pad${pad + 1}"]) { kits.zones(t, pad) }
        if (z.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Pad ${pad + 1}: ${z.size} sample${if (z.size > 1) "s" else ""}", color = Zk.text, fontSize = 12.sp)
            z.forEachIndexed { i, zone ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(zone.entry.name, color = Zk.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (zone.vlo > 1 || zone.vhi < 127) "vel ${zone.vlo}–${zone.vhi}" else "all", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    Text("✕", color = Zk.danger, fontSize = 14.sp, modifier = Modifier.clickable { kits.removeZone(t, pad, i) }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Text("Samples that share a velocity range play in turn (round robin).", color = Zk.dim, fontSize = 10.sp)
        }
    }
    if (projectKits) ProjectKitsDialog(kits, ps, color) { projectKits = false }
    if (kits.browse != null) SampleBrowser(kits, pad, color)
    if (needFolder) AlertDialog(onDismissRequest = { needFolder = false }, containerColor = Zk.panel, title = { Text("Choose your drum kit folder") },
        text = { Text("Pick the folder that holds all your drum kits and samples once, in Settings → Storage → Drum kit folder. It is remembered and scanned one time; after that this browser always shows its sounds.", color = Zk.dim, fontSize = 13.sp) },
        confirmButton = { TextButton({ needFolder = false }) { Text("OK") } })
}

@Composable
fun KitJobDialog(kits: KitManager, color: Color) {
    when (kits.phase) {
        KitManager.Phase.Idle -> {}
        KitManager.Phase.Scanning -> AlertDialog(onDismissRequest = {}, containerColor = Zk.panel, title = { Text("Reading the folder") },
            text = { Column { Text(kits.status, color = Zk.dim, fontSize = 13.sp); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } },
            confirmButton = {}, dismissButton = { TextButton({ kits.cancel() }) { Text("Cancel") } })
        KitManager.Phase.Choose -> AlertDialog(onDismissRequest = { kits.dismiss() }, containerColor = Zk.panel, title = { Text("Which kit?") },
            text = {
                Column {
                    Text("This folder holds several kits. Pick one: its files are copied into your project.", color = Zk.dim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = dialogListMax(320.dp))) {
                        items(kits.candidates) { c ->
                            Column(Modifier.fillMaxWidth().clickable { kits.import(c) }.padding(vertical = 10.dp)) {
                                Text(c.name, color = Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.kind} · ${c.count} file${if (c.count == 1) "" else "s"}", color = Zk.dim, fontSize = 11.sp)
                            }
                            HorizontalDivider(color = Zk.line)
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ kits.dismiss() }) { Text("Cancel") } })
        KitManager.Phase.Importing -> AlertDialog(onDismissRequest = {}, containerColor = Zk.panel, title = { Text("Importing the kit") },
            text = { Column { Text(kits.status, color = Zk.dim, fontSize = 13.sp); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(progress = { kits.progress }, Modifier.fillMaxWidth(), color = color) } },
            confirmButton = {}, dismissButton = { TextButton({ kits.cancel() }) { Text("Cancel") } })
        KitManager.Phase.Failed -> AlertDialog(onDismissRequest = { kits.dismiss() }, containerColor = Zk.panel, title = { Text("Kit import") },
            text = { Text(kits.status, color = Zk.dim, fontSize = 13.sp) }, confirmButton = { TextButton({ kits.dismiss() }) { Text("OK") } })
    }
}

@Composable
private fun ProjectKitsDialog(kits: KitManager, ps: PluginState, color: Color, onDismiss: () -> Unit) {
    val list = remember(kits.revision) { kits.projectKits() }
    var confirmDelete by remember { mutableStateOf<java.io.File?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Kits in this project") }, text = {
        Column {
            Text("Every kit you import is copied into the project's Kits folder. Tap one to put it on the pads.", color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) Text("No kit yet. Use Load kit… to import one.", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            else LazyColumn(Modifier.heightIn(max = dialogListMax(300.dp))) {
                items(list, key = { it.second.absolutePath }) { (name, dir) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { kits.loadProjectKit(ps.t, dir); onDismiss() }) {
                            Text(name, color = if (name == kits.kitName(ps.t)) color else Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${dir.walkTopDown().count { it.isFile && it.extension != "zkit" }} files", color = Zk.dim, fontSize = 11.sp)
                        }
                        TextButton({ confirmDelete = dir }) { Text("Delete", color = Zk.danger) }
                    }
                    HorizontalDivider(color = Zk.line)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
    confirmDelete?.let { d ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, containerColor = Zk.panel, title = { Text("Delete this kit?") },
            text = { Text("Its audio files are removed from the project folder. A track that still uses them goes silent on those pads.", color = Zk.dim, fontSize = 13.sp) },
            confirmButton = { TextButton({ kits.deleteProjectKit(d); confirmDelete = null }) { Text("Delete", color = Zk.danger) } }, dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
}

/** One folder of the library tree: its sub-folders and the sounds directly in it. */
internal class Dir(val name: String, val path: String) { val dirs = java.util.TreeMap<String, Dir>(String.CASE_INSENSITIVE_ORDER); val files = ArrayList<Pair<String, KitNode>>(); var total = 0 }
internal sealed class TreeRow { class D(val dir: Dir, val depth: Int, val open: Boolean) : TreeRow(); class F(val path: String, val node: KitNode, val depth: Int) : TreeRow() }

internal fun buildTree(items: List<Pair<String, KitNode>>): Dir {
    val root = Dir("", "")
    for (it in items) {
        val parts = it.first.split('/'); var d = root; d.total++
        for (k in 0 until parts.size - 1) { val p = parts.subList(0, k + 1).joinToString("/"); d = d.dirs.getOrPut(parts[k]) { Dir(parts[k], p) }; d.total++ }
        d.files.add(it)
    }
    return root
}
internal fun flatten(d: Dir, depth: Int, open: (String) -> Boolean, out: MutableList<TreeRow>) {
    for (sub in d.dirs.values) { val o = open(sub.path); out.add(TreeRow.D(sub, depth, o)); if (o) flatten(sub, depth + 1, open, out) }
    for (f in d.files) out.add(TreeRow.F(f.first, f.second, depth))
}

/**
 * The sound browser: the drum kit library as a folder tree (tap a folder to fold or unfold it, "Collapse all" folds everything), or the favourites, or search
 * results. Each sound has a preview button and a star. Tap sounds to select, then Load to a pad (replaces the pad) or Add (round robin / layer); files are copied into the project.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SampleBrowser(kits: KitManager, startPad: Int, color: Color) {
    val b = kits.browse ?: return
    var pad by remember(b) { mutableIntStateOf(startPad) }
    var favOnly by remember(b) { mutableStateOf(b.favoritesFirst) }
    var query by remember(b) { mutableStateOf("") }
    val selected = remember(b) { mutableStateListOf<String>() }
    val tree = remember(kits.libraryItems) { buildTree(kits.libraryItems) }
    val flat = favOnly || query.isNotBlank()          // favourites and search results are flat lists (the folder is shown under each name)
    val rows: List<TreeRow> = remember(b, favOnly, query, kits.favorites, kits.libraryItems, kits.expandedDirs.toMap()) {
        val out = ArrayList<TreeRow>()
        if (flat) (if (favOnly) kits.favoriteItems() else kits.libraryItems).filter { query.isBlank() || it.first.contains(query.trim(), true) }.forEach { out.add(TreeRow.F(it.first, it.second, 0)) }
        else flatten(tree, 0, { kits.expandedDirs[it] == true }, out)
        out
    }
    LaunchedEffect(rows, kits.preview.playing) { val pl = kits.preview.playing; if (pl != null && rows.none { it is TreeRow.F && it.node.ref == pl }) kits.preview.stop() }
    androidx.compose.ui.window.Dialog(onDismissRequest = { kits.closeBrowser() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Zk.bg).statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(if (favOnly) "Favourite sounds" else kits.libraryName.ifEmpty { "Drum kits" }, color = Zk.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (!favOnly) (if (kits.libraryScanning) "Scanning the folder…" else "${kits.libraryItems.size} sound${if (kits.libraryItems.size == 1) "" else "s"}") else "${kits.favorites.size} favourite${if (kits.favorites.size == 1) "" else "s"}", color = Zk.dim, fontSize = 11.sp)
                }
                LabelButton("‹", false, w = 30.dp, h = 32.dp) { pad = (pad + 15) % 16 }
                Text("Pad ${pad + 1}", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                LabelButton("›", false, w = 30.dp, h = 32.dp) { pad = (pad + 1) % 16 }
                LabelButton("Close", true, color, w = 62.dp, h = 32.dp) { kits.closeBrowser() }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(Zk.panel2).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search", color = Zk.dim, fontSize = 13.sp)
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Zk.text, fontSize = 13.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(color), modifier = Modifier.fillMaxWidth())
                }
                LabelButton("Folders", !favOnly, color, w = 66.dp, h = 34.dp) { favOnly = false }
                LabelButton("★ Favourites", favOnly, color, w = 108.dp, h = 34.dp) { favOnly = true }
                if (!favOnly && query.isBlank()) {
                    LabelButton("Collapse all", false, w = 96.dp, h = 34.dp) { kits.expandedDirs.clear() }
                    LabelButton("Expand all", false, w = 88.dp, h = 34.dp) { fun all(d: Dir) { d.dirs.values.forEach { kits.expandedDirs[it.path] = true; all(it) } }; all(tree) }
                    LabelButton("Import as kit", false, w = 108.dp, h = 34.dp) { kits.importBrowsed("") }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!favOnly && kits.libraryScanning && kits.libraryItems.isEmpty()) Text("Scanning your drum kit folder (done once)…", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                else if (rows.isEmpty()) Text(if (favOnly) "No favourite yet. Tap the star of a sound in a folder to keep it here." else "No sound found.", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
                else LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { r -> when (r) { is TreeRow.D -> "d:" + r.dir.path; is TreeRow.F -> "f:" + r.node.ref } }) { r ->
                        when (r) {
                            is TreeRow.D -> {
                                Row(Modifier.fillMaxWidth().clickable { kits.expandedDirs[r.dir.path] = !r.open }.padding(start = (r.depth * 18).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (r.open) "▾" else "▸", color = color, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
                                    Text("📁 ", fontSize = 13.sp)
                                    Text(r.dir.name, color = Zk.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                                    Text("${r.dir.total}", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                                    Text("Import kit", color = color, fontSize = 11.sp, modifier = Modifier.clickable { kits.importBrowsed(r.dir.path) }.padding(horizontal = 10.dp, vertical = 12.dp))
                                }
                                HorizontalDivider(color = Zk.line)
                            }
                            is TreeRow.F -> {
                                val node = r.node; val on = node.ref in selected; val playing = kits.preview.playing == node.ref; val fav = kits.isFavorite(node)
                                Row(Modifier.fillMaxWidth().background(if (on) color.copy(alpha = 0.16f) else Color.Transparent).clickable { if (on) selected.remove(node.ref) else selected.add(node.ref) }.padding(start = (r.depth * 18).dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (playing) "■" else "▶", color = if (playing) color else Zk.text, fontSize = 18.sp, textAlign = TextAlign.Center,
                                        modifier = Modifier.width(48.dp).clickable { kits.preview.toggle(node) }.padding(vertical = 12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(node.name, color = Zk.text, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1200, repeatDelayMillis = 1200, velocity = 36.dp))
                                        if (flat && r.path != node.name) Text(r.path.substringBeforeLast('/'), color = Zk.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(if (on) "✓" else "", color = color, fontSize = 16.sp, modifier = Modifier.width(26.dp), textAlign = TextAlign.Center)
                                    Text(if (fav) "★" else "☆", color = if (fav) Color(0xFFFFC857) else Zk.dim, fontSize = 22.sp, textAlign = TextAlign.Center,
                                        modifier = Modifier.width(48.dp).clickable { kits.toggleFavorite(node, r.path.substringBeforeLast('/', "")) }.padding(vertical = 8.dp))
                                }
                                HorizontalDivider(color = Zk.line)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (kits.browseStatus.isNotEmpty()) kits.browseStatus else if (selected.isEmpty()) "Tap sounds to select them." else "${selected.size} selected", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                val picked = { rows.filterIsInstance<TreeRow.F>().filter { it.node.ref in selected }.map { it.node } }
                LabelButton("Load to pad ${pad + 1}", selected.isNotEmpty(), color, w = 130.dp, h = 38.dp) {
                    if (selected.isNotEmpty()) kits.loadToPad(pad, picked(), false) { selected.clear(); pad = (pad + 1) % 16 }
                }
                LabelButton("Add to pad ${pad + 1}", false, w = 120.dp, h = 38.dp) {
                    if (selected.isNotEmpty()) kits.loadToPad(pad, picked(), true) { selected.clear() }
                }
            }
        }
    }
}
