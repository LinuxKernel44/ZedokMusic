package com.zedokmusic.ui.nplug

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.Preset
import com.zedokmusic.model.TrackM
import com.zedokmusic.ui.LabelButton
import com.zedokmusic.ui.Zk

/** The bar at the top of every built-in plugin: previous / current preset (tap for the browser) / next, A/B, save, and a switch to the generic parameter list. */
@Composable
fun PresetBar(vm: DawViewModel, t: TrackM, color: Color, showParams: Boolean, onParams: () -> Unit) {
    val id = vm.nativeId(t) ?: return
    var browser by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }       // bumped when user presets or favourites change
    val lib = vm.presetLibrary
    val list = remember(id, revision) { lib.all(id) }
    fun step(d: Int) {
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.key == t.presetKey }
        val n = if (i < 0) (if (d > 0) 0 else list.size - 1) else (i + d + list.size) % list.size
        vm.applyPreset(t, list[n])
    }
    val short = com.zedokmusic.ui.LocalShort.current
    val bh = if (short) 28.dp else 34.dp
    Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = if (short) 2.dp else 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LabelButton("◀", false, w = 36.dp, h = bh) { step(-1) }
        val isFav = remember(revision, t.presetKey) { t.presetKey.isNotBlank() && lib.favorites().contains(t.presetKey) }
        Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Zk.panel2).clickable { browser = true }.padding(horizontal = 10.dp, vertical = if (short) 3.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (short) Text(t.presetName.ifBlank { "Init" } + (list.firstOrNull { it.key == t.presetKey }?.category?.let { "  ·  $it" } ?: ""), color = Zk.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else {
                Text(t.presetName.ifBlank { "Init" }, color = Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(list.firstOrNull { it.key == t.presetKey }?.category ?: "Tap to browse presets", color = Zk.dim, fontSize = 10.sp, maxLines = 1)
            }
        }
        if (isFav) Text("★", color = Color(0xFFFFD43B), fontSize = 20.sp, modifier = Modifier.padding(start = 6.dp))   // the loaded preset is one of the favourites
        }
        LabelButton("▶", false, w = 36.dp, h = bh) { step(1) }
        LabelButton(if (t.abSlot == 0) "A" else "B", t.abSlot == 1, color, w = 36.dp, h = bh) { vm.toggleAB(t) }
        LabelButton("Save", false, w = 52.dp, h = bh) { saving = true }
        LabelButton(if (showParams) "UI" else "List", showParams, w = 46.dp, h = bh, onClick = onParams)
    }
    if (browser) PresetBrowser(vm, t, id, color, revision, { revision++ }) { browser = false }
    if (saving) SavePresetDialog(vm, t, id, { revision++; saving = false }) { saving = false }
}

@Composable
fun PresetBrowser(vm: DawViewModel, t: TrackM, id: String, color: Color, revision: Int, changed: () -> Unit, onDismiss: () -> Unit) {
    val lib = vm.presetLibrary; val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var rev by remember { mutableIntStateOf(0) }
    val all = remember(id, revision, rev) { lib.all(id) }
    val favs = remember(rev) { lib.favorites().toSet() }
    val cats = remember(all) { all.map { it.category }.distinct() }
    val shown = remember(all, query, filter, favs) {
        all.filter { p ->
            (query.isBlank() || p.name.contains(query, true) || p.category.contains(query, true) || p.tags.any { it.contains(query, true) }) &&
                when (filter) { "All" -> true; "★" -> favs.contains(p.key); "User" -> !p.factory; else -> p.category == filter }
        }
    }
    var confirmInit by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Preset?>(null) }
    var renaming by remember { mutableStateOf<Preset?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            val p = text?.let { lib.import(id, it) }
            vm.message = if (p != null) "Imported \"${p.name}\"." else "That file is not a preset for this plugin."
            if (p != null) { rev++; changed() }
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopPreview() } }   // closing the browser ends a preview that is still playing
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val short = com.zedokmusic.ui.LocalShort.current
        val state = rememberLazyListState()
        LaunchedEffect(Unit) { val i = shown.indexOfFirst { it.key == t.presetKey }; if (i > 0) state.scrollToItem(i) }
        val chipOf: @Composable (String) -> Unit = { c ->
            val on = c == filter
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) color else Zk.panel2).clickable { filter = c }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(if (c == "★") "★ Favourites" else c, color = if (on) Color(0xFF0B0D10) else Zk.dim, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
            }
        }
        val allCats = listOf("All", "★", "User") + cats.filter { it != "User" }
        val titleRow: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${t.pluginName.ifBlank { t.name }} · presets", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LabelButton("Import", false, w = 64.dp, h = 32.dp) { importer.launch("*/*") }
                LabelButton("Close", true, color, w = 60.dp, h = 32.dp, onClick = onDismiss)
            }
        }
        val searchBox: @Composable () -> Unit = { OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search name, category or tag") }, modifier = Modifier.fillMaxWidth()) }
        val presetList: @Composable (Modifier) -> Unit = { m ->
            Column(m) {
                Text("${shown.size} presets", color = Zk.dim, fontSize = 11.sp)
                LazyColumn(Modifier.fillMaxSize(), state) {
                    item(key = "init") {
                        Row(Modifier.fillMaxWidth().clickable { requestInit(vm, t) { confirmInit = true } }.padding(vertical = if (short) 5.dp else 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Init preset", color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("Every control back to its default, nothing loaded", color = Zk.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        HorizontalDivider(color = Zk.line)
                    }
                    items(shown, key = { it.key }) { p ->
                        val cur = p.key == t.presetKey
                        Row(Modifier.fillMaxWidth().background(if (cur) color.copy(alpha = .14f) else Color.Transparent).clickable { vm.applyPreset(t, p, preview = true) }.padding(vertical = if (short) 5.dp else 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = if (cur) color else Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.category + (if (p.tags.isNotEmpty()) " · " + p.tags.joinToString(", ") else "") + if (!p.factory) " · yours" else "", color = Zk.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val fav = favs.contains(p.key)
                            Text(if (fav) "★" else "☆", color = if (fav) Zk.warn else Zk.dim, fontSize = 22.sp, modifier = Modifier.clickable { lib.toggleFavorite(p.key); rev++; changed() }.padding(horizontal = 10.dp))
                            if (!p.factory) Box { Text("⋮", color = Zk.dim, fontSize = 20.sp, modifier = Modifier.clickable { menu = p }.padding(horizontal = 8.dp)) }
                        }
                        HorizontalDivider(color = Zk.line)
                    }
                }
            }
        }
        if (short) Row(Modifier.fillMaxSize().background(Zk.bg).statusBarsPadding().navigationBarsPadding().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // landscape: title, search and categories on the left, the list takes the whole height on the right
            Column(Modifier.width(260.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                titleRow(); searchBox()
                @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { allCats.forEach { chipOf(it) } }
            }
            presetList(Modifier.weight(1f).fillMaxHeight())
        } else Column(Modifier.fillMaxSize().background(Zk.bg).statusBarsPadding().navigationBarsPadding().padding(10.dp)) {
            titleRow()
            Spacer(Modifier.height(6.dp)); searchBox(); Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { allCats.forEach { chipOf(it) } }
            Spacer(Modifier.height(6.dp))
            presetList(Modifier.fillMaxSize())
        }
        if (confirmInit) InitConfirmDialog(vm, t, { confirmInit = false })
        menu?.let { p ->
            AlertDialog(onDismissRequest = { menu = null }, containerColor = Zk.panel, title = { Text(p.name) }, text = {
                Column {
                    TextButton({ renaming = p; menu = null }) { Text("Rename") }
                    TextButton({ val f = lib.export(id, p); vm.message = if (f != null) "Saved to ${f.path}" else "Could not export."; menu = null }) { Text("Export to the Exports folder") }
                    TextButton({ lib.delete(p); if (t.presetKey == p.key) t.presetKey = ""
                        rev++; changed(); menu = null }) { Text("Delete", color = Zk.danger) }
                }
            }, confirmButton = { TextButton({ menu = null }) { Text("Close") } })
        }
        renaming?.let { p ->
            var nm by remember(p) { mutableStateOf(p.name) }
            AlertDialog(onDismissRequest = { renaming = null }, containerColor = Zk.panel, title = { Text("Rename preset") },
                text = { OutlinedTextField(nm, { nm = it }, singleLine = true) },
                confirmButton = { TextButton({ val r = lib.rename(id, p, nm); if (r == null) vm.message = "Could not rename (name taken?)."; renaming = null; rev++; changed() }) { Text("Rename") } },
                dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } })
        }
    }
}

@Composable
fun SavePresetDialog(vm: DawViewModel, t: TrackM, id: String, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val lib = vm.presetLibrary
    var name by remember { mutableStateOf(if (lib.factory(id).any { it.key == t.presetKey }) "" else t.presetName) }
    val cats = remember { (lib.all(id).map { it.category } + listOf("User")).distinct() }
    var cat by remember { mutableStateOf(lib.all(id).firstOrNull { it.key == t.presetKey }?.category ?: "User") }
    var custom by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Save preset") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            Spacer(Modifier.height(8.dp))
            Text("Category", color = Zk.dim, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                cats.forEach { c -> val on = c == cat && custom.isBlank(); Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) Zk.accent else Zk.panel2).clickable { cat = c; custom = "" }.padding(horizontal = 10.dp, vertical = 6.dp)) { Text(c, color = if (on) Color(0xFF0B0D10) else Zk.dim, fontSize = 12.sp, maxLines = 1, softWrap = false) } }
            }
            OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("or a new category") })
            Text("Saved in your ZedokMusic folder (Presets), available in every project.", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }, confirmButton = {
        TextButton({
            val p = lib.save(id, name, custom.ifBlank { cat }, emptyList(), vm.changedValues(t), emptyMap())
            if (p == null) vm.message = "Give the preset a name." else { t.presetName = p.name; t.presetKey = p.key; vm.message = "Saved \"${p.name}\"."; onSaved() }
        }, enabled = name.isNotBlank()) { Text("Save") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** "Init preset": resets at once when the sound was not touched, otherwise [ask] is called to show the confirmation. */
fun requestInit(vm: DawViewModel, t: TrackM, ask: () -> Unit) { if (vm.isTouched(t)) ask() else vm.initPlugin(t) }

/** The clear message shown before Init throws away what the user did to the current sound. */
@Composable
fun InitConfirmDialog(vm: DawViewModel, t: TrackM, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Reset to Init?") }, text = {
        Text("You changed this sound (a knob turned, a sound or sample added…).\n\nInit puts every control back to its default and removes everything loaded in ${t.pluginName.ifBlank { t.name }}, such as the samples on the drum pads. The audio files stay in your project folder.\n\nThis cannot be undone. Save the sound as a preset first if you want to keep it.", color = Zk.dim, fontSize = 13.sp)
    }, confirmButton = { TextButton({ vm.initPlugin(t); onDismiss() }) { Text("Reset to Init", color = Zk.danger) } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
