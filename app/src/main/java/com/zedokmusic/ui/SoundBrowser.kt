package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import com.zedokmusic.ui.nplug.Dir
import com.zedokmusic.ui.nplug.TreeRow
import com.zedokmusic.ui.nplug.buildTree
import com.zedokmusic.ui.nplug.flatten
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class BTab(val label: String) { Kits("Drumkits"), Project("Project"), Plugins("Plugins") }

/**
 * The sound browser of the Tracks view: the drum kit library (folders in a tree), the sounds of the project, and the plugins. Sounds, folders (kits) and
 * plugins are dragged onto the tracks: long press on a row, or drag the ⋮⋮ handle. Tap rows to select several sounds and drag them together.
 */
@Composable
fun SoundBrowser(vm: DawViewModel, modifier: Modifier, canSwitch: Boolean, onClose: () -> Unit) {
    val kits = vm.kits; val dnd = LocalDnd.current; val color = Zk.accent
    var tab by remember { mutableStateOf(BTab.Kits) }
    var query by remember { mutableStateOf("") }
    var favOnly by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val projOpen = remember { mutableStateMapOf<String, Boolean>() }
    var projItems by remember { mutableStateOf<List<Pair<String, KitNode>>>(emptyList()) }
    val projRev = vm.samplesVersion + kits.revision
    LaunchedEffect(tab, projRev, vm.projectName) {
        if (tab == BTab.Project) projItems = withContext(Dispatchers.IO) { vm.projectDir()?.let { d -> KitScanner.listAudio(FileNode(d)).filter { !it.first.startsWith("Presets/") } } ?: emptyList() }
    }
    val items = if (tab == BTab.Kits) kits.libraryItems else projItems
    val open: (String) -> Boolean = { if (tab == BTab.Kits) kits.expandedDirs[it] == true else projOpen[it] == true }
    val setOpen: (String, Boolean) -> Unit = { p, v -> if (tab == BTab.Kits) kits.expandedDirs[p] = v else projOpen[p] = v }
    val tree = remember(items) { buildTree(items) }
    val flat = favOnly || query.isNotBlank()
    val rows: List<TreeRow> = remember(tab, items, favOnly, query, kits.favorites, kits.expandedDirs.toMap(), projOpen.toMap()) {
        val out = ArrayList<TreeRow>()
        if (tab == BTab.Plugins) return@remember out
        if (flat) (if (favOnly) kits.favoriteItems() else items).filter { query.isBlank() || it.first.contains(query.trim(), true) }.forEach { out.add(TreeRow.F(it.first, it.second, 0)) }
        else flatten(tree, 0, open, out)
        out
    }
    val plugins = remember(vm.pluginsVersion, tab) { if (tab == BTab.Plugins) vm.scanPlugins() else emptyList() }

    Column(modifier.background(Zk.panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sounds", color = Zk.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (canSwitch) LabelButton(if (com.zedokmusic.settings.AppSettings.browserDocked.on) "⇄ Drawer" else "⇄ Dock", false, w = 78.dp, h = 28.dp) { com.zedokmusic.settings.AppSettings.browserDocked.set(!com.zedokmusic.settings.AppSettings.browserDocked.on) }
            LabelButton("✕", false, w = 30.dp, h = 28.dp, onClick = onClose)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (t in BTab.values()) LabelButton(t.label, tab == t, color, w = if (t == BTab.Plugins) 62.dp else 76.dp, h = 28.dp) { tab = t; selected.clear(); kits.preview.stop() }
        }
        if (tab != BTab.Plugins) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp)).background(Zk.panel2).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search", color = Zk.dim, fontSize = 12.sp)
                BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Zk.text, fontSize = 12.sp), cursorBrush = SolidColor(color), modifier = Modifier.fillMaxWidth())
            }
            LabelButton("★", favOnly, Zk.warn, w = 30.dp, h = 30.dp) { favOnly = !favOnly }
            LabelButton("⊟", false, w = 30.dp, h = 30.dp) { if (tab == BTab.Kits) kits.expandedDirs.clear() else projOpen.clear() }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                tab == BTab.Plugins -> PluginList(plugins, dnd, color)
                tab == BTab.Kits && kits.libraryUri() == null -> Hint("Choose your drum kit folder once in Settings → Storage → Drum kit folder. Its sounds then show here.")
                tab == BTab.Kits && kits.libraryScanning && items.isEmpty() -> Hint("Scanning your drum kit folder (done once)…")
                rows.isEmpty() -> Hint(if (favOnly) "No favourite yet. Tap the star of a sound." else if (tab == BTab.Project) "No sound in this project yet." else "No sound found.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(rows, key = { r -> when (r) { is TreeRow.D -> "d:" + r.dir.path; is TreeRow.F -> "f:" + r.node.ref } }) { r ->
                        when (r) {
                            is TreeRow.D -> FolderRow(r, color, dnd, onToggle = { setOpen(r.dir.path, !r.open) }, payload = {
                                DragPayload(r.dir.name, folderImport = if (tab == BTab.Kits) ({ t: TrackM -> kits.importLibraryFolder(t, r.dir.path) }) else ({ t: TrackM -> vm.projectDir()?.let { kits.importFolder(t, FileNode(File(it, r.dir.path))) }; Unit }))
                            })
                            is TreeRow.F -> SoundRow(r, flat, color, dnd, kits, selected, payload = {
                                val sel = rows.filterIsInstance<TreeRow.F>().filter { it.node.ref in selected }.map { it.node }
                                DragPayload(r.node.name, if (r.node.ref in selected && sel.size > 1) sel else listOf(r.node))
                            })
                        }
                    }
                }
            }
        }
        Text(if (selected.isEmpty()) "Long press a sound, or drag ⋮⋮, onto a track." else "${selected.size} selected · drag one to move them all", color = Zk.dim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
    }
    // a finished drop clears the selection
    DisposableEffect(dnd) { val l: (Boolean) -> Unit = { ok -> if (ok) selected.clear() }; dnd.finishListeners.add(l); onDispose { dnd.finishListeners.remove(l) } }
}

@Composable private fun Hint(text: String) = Text(text, color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(14.dp))

@Composable
private fun FolderRow(r: TreeRow.D, color: Color, dnd: DndState, onToggle: () -> Unit, payload: () -> DragPayload) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = (r.depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (r.open) "▾" else "▸", color = color, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.width(26.dp))
        Text(r.dir.name, color = Zk.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 11.dp))
        Text("${r.dir.total}", color = Zk.dim, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp))
        Handle(dnd, payload)
    }
    HorizontalDivider(color = Zk.line)
}

@Composable
private fun SoundRow(r: TreeRow.F, flat: Boolean, color: Color, dnd: DndState, kits: KitManager, selected: androidx.compose.runtime.snapshots.SnapshotStateList<String>, payload: () -> DragPayload) {
    val node = r.node; val on = node.ref in selected; val playing = kits.preview.playing == node.ref; val fav = kits.isFavorite(node)
    Row(Modifier.fillMaxWidth().background(if (on) color.copy(alpha = .16f) else Color.Transparent).clickable { if (on) selected.remove(node.ref) else selected.add(node.ref) }
        .dragSource(dnd, immediate = false, payload).padding(start = (r.depth * 14).dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (playing) "■" else "▶", color = if (playing) color else Zk.text, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp).clickable { kits.preview.toggle(node) }.padding(vertical = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, color = Zk.text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (flat && r.path != node.name) Text(r.path.substringBeforeLast('/'), color = Zk.dim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(if (fav) "★" else "☆", color = if (fav) Zk.warn else Zk.dim, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp).clickable { kits.toggleFavorite(node, r.path.substringBeforeLast('/', "")) }.padding(vertical = 6.dp))
        Handle(dnd, payload)
    }
    HorizontalDivider(color = Zk.line)
}

/** The ⋮⋮ grip: drags from the first movement. */
@Composable
private fun Handle(dnd: DndState, payload: () -> DragPayload) {
    Text("⋮⋮", color = Zk.dim, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp).dragSource(dnd, immediate = true, payload).padding(vertical = 10.dp))
}

@Composable
private fun PluginList(plugins: List<PluginInfo>, dnd: DndState, color: Color) {
    val inst = plugins.filter { it.kind != 2 }; val fx = plugins.filter { it.kind == 2 || it.kind == 0 || it.kind == 3 }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text("INSTRUMENTS", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp)) }
        items(inst, key = { "i" + it.path }) { p -> PluginRow(p, dnd) }
        item { Text("EFFECTS", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp)) }
        items(fx, key = { "e" + it.path }) { p -> PluginRow(p, dnd) }
        item { Text("Drop an instrument in the empty zone for a new track, an effect on a track to add it.", color = Zk.dim, fontSize = 10.sp, modifier = Modifier.padding(10.dp)) }
    }
}

@Composable
private fun PluginRow(p: PluginInfo, dnd: DndState) {
    Row(Modifier.fillMaxWidth().dragSource(dnd, immediate = false) { DragPayload(p.name, plugin = p) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(p.name, color = Zk.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 11.dp))
        Handle(dnd) { DragPayload(p.name, plugin = p) }
    }
    HorizontalDivider(color = Zk.line)
}
