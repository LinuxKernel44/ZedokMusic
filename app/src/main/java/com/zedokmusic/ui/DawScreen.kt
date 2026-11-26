package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import com.zedokmusic.model.*
import com.zedokmusic.update.Updater

private enum class Panel(val label: String) { Arrange("Tracks"), Piano("Piano"), Mixer("Mixer"), Plugin("Plugin"), Auto("Auto") }

@Composable
fun DawScreen(vm: DawViewModel) {
    var panel by rememberSaveable { mutableStateOf(Panel.Arrange) }
    var bottom by rememberSaveable { mutableStateOf(Panel.Piano) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var octave by rememberSaveable { mutableIntStateOf(3) }
    var ppb by vm::ppb   // saved with the project
    var dialog by remember { mutableStateOf<String?>(null) }
    // A new project starts from the default layout: Tracks tab, no on-screen keys, default octave.
    LaunchedEffect(vm.newProjectRevision) { if (vm.newProjectRevision > 0) { panel = Panel.Arrange; bottom = Panel.Piano; showKeys = false; octave = 3 } }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    // Once per start-up (and at most once a day): look for a newer version quietly, then offer it.
    LaunchedEffect(Unit) { Updater.autoCheck() }
    LaunchedEffect(Updater.phase) { if (Updater.phase == Updater.Phase.Available && !showSettings) showUpdate = true }
    LaunchedEffect(Updater.justUpdatedTo) { Updater.justUpdatedTo?.let { vm.message = "Updated to $it."; Updater.justUpdatedTo = null } }
    var insertsOpen by remember { mutableStateOf(false) }
    var insertsOwner by remember { mutableStateOf<TrackM?>(null) }
    val openInserts: (TrackM?) -> Unit = { owner -> insertsOwner = owner; insertsOpen = true }
    var plugins by remember { mutableStateOf(emptyList<PluginInfo>()) }
    val sel = vm.selected
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) { if (vm.message != null) { kotlinx.coroutines.delay(4500); vm.message = null } }  // notices fade away by themselves
    LaunchedEffect(dialog, vm.pluginsVersion) { if (dialog == "plugins") { plugins = vm.scanPlugins(); vm.probeUnknown(plugins) } }  // tell instruments from effects
    fun begin() { vm.startRecording()?.let { vm.message = it } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) begin() else vm.message = "Microphone permission is needed to record audio." }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.midi.startScan() else vm.message = "Location permission is needed by Android to scan for Bluetooth devices." }
    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importSamples(uris) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importInstaller(uri) }
    fun onRecord() {
        if (vm.recording) { vm.stopRecording(); return }
        if (vm.selected?.kind == TrackKind.Audio && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPermission.launch(Manifest.permission.RECORD_AUDIO) else begin()
    }
    fun onScan() { if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.midi.startScan() else locPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    Box(Modifier.fillMaxSize().background(Zk.bg).windowInsetsPadding(screenInsets()).imePadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            // Landscape on a phone: ~380 dp of height. One-row transport, vertical tab rail, denser panels.
            val short = maxHeight < 520.dp && maxWidth > maxHeight
            // The piano roll has its own keys on its left: the on-screen keyboard is hidden there (and its button greyed out).
            val rollOpen = if (wide && !short) bottom == Panel.Piano else panel == Panel.Piano
            CompositionLocalProvider(LocalShort provides short) {
            Column(Modifier.fillMaxSize()) {
                Transport(vm, !wide && !short, short, showKeys, rollOpen, onRecord = { onRecord() }, onMidi = { dialog = "midi" }, onKeys0 = {}, { showKeys = !showKeys }, onAddInstrument = { plugins = vm.scanPlugins(); dialog = "plugins" },
                    onAddAudio = { vm.addAudioTrack() }, onTempo = { dialog = "tempo" }, onSave = { vm.save(); vm.message = "Project saved" }, onExport = { dialog = "export" }, onExports = { dialog = "exports" }, onInstallers = { dialog = "installers" }, onSettings = { showSettings = true }, onProject = { dialog = it })
                if (short) {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.width(74.dp).fillMaxHeight().background(Zk.panel).verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (p in Panel.values()) PanelChip(p.label, panel == p, Modifier.fillMaxWidth()) { panel = p }
                        }
                        VerticalDivider(color = Zk.line)
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            PanelContent(vm, panel, ppb, { ppb = it }, { panel = Panel.Piano }, { vm.selected = it; dialog = "samples" }, openInserts)
                        }
                    }
                } else if (wide) {
                    val expanded = vm.panelExpanded && bottom == Panel.Plugin
                    if (!expanded) {
                        Box(Modifier.weight(1f)) { ArrangeView(vm, ppb, { ppb = it }, { panel = Panel.Piano; bottom = Panel.Piano }, { vm.selected = it; dialog = "samples" }) }
                        HorizontalDivider(color = Zk.line)
                    }
                    Column(Modifier.weight(if (expanded) 1f else 0.9f)) {
                        Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in listOf(Panel.Piano, Panel.Mixer, Panel.Plugin, Panel.Auto)) PanelChip(p.label, bottom == p) { bottom = p }
                        }
                        Box(Modifier.weight(1f)) { PanelContent(vm, bottom, ppb, { ppb = it }, {}, {}, openInserts) }
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        PanelContent(vm, panel, ppb, { ppb = it }, { panel = Panel.Piano }, { vm.selected = it; dialog = "samples" }, openInserts)
                    }
                    Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in Panel.values()) PanelChip(p.label, panel == p, Modifier.weight(1f)) { panel = p }
                    }
                }
                if (showKeys && !rollOpen) {
                    val t = if (sel?.kind == TrackKind.Instrument) sel else vm.tracks.firstOrNull { it.kind == TrackKind.Instrument }
                    if (t != null) PianoKeyboard(octave, { octave = it }, t.color, { on, p -> vm.liveNote(t, on, p) }, Modifier.fillMaxWidth().height(if (short) 88.dp else if (wide) 150.dp else 130.dp))
                }
            }
            if (vm.message != null) {
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton({ vm.message = null }) { Text("OK") } }) { Text(vm.message ?: "") }
            }
            }  // CompositionLocalProvider
        }
    }
    when (dialog) {
        "plugins" -> PluginPickerDialog(plugins.filter { it.kind != 2 }, vm.pluginDir()?.path ?: "", { plugins = vm.scanPlugins() }, { vm.addInstrument(it); dialog = null }, { dialog = null })
        "samples" -> SamplePickerDialog(vm.projectSamples(), vm.sharedSamples(), vm.projectSamplesDir()?.path ?: "", { samplePicker.launch(arrayOf("audio/*", "application/octet-stream")) }, { f ->
            val t = vm.selected?.takeIf { it.kind == TrackKind.Audio } ?: vm.addAudioTrack(); if (t != null) vm.importClip(t, f, vm.playhead.let { Math.floor(it.toDouble()).toFloat() }); dialog = null }, { dialog = null })
        "midi" -> MidiDialog(vm.midi, { onScan() }, { dialog = null })
        "tempo" -> TempoDialog(vm.bpm, { vm.changeBpm(it) }, { dialog = null })
        "exports" -> ExportsDialog(vm) { dialog = null }
        "new" -> NameDialog("New project", vm.suggestProjectName(), "Create", "Your current project is saved first.", { vm.newProject(it) }, { dialog = null })
        "open" -> ProjectsDialog(vm) { dialog = null }
        "saveas" -> NameDialog("Save as", vm.suggestProjectName(), "Save", "Creates a new project folder with a copy of this project's samples and presets.", { vm.saveAs(it) }, { dialog = null })
        "rename" -> NameDialog("Rename project", vm.projectName, "Rename", null, { vm.renameProject(it) }, { dialog = null })
        "version" -> { LaunchedEffect(Unit) { vm.saveNewVersion(); dialog = null } }
        "collect" -> { LaunchedEffect(Unit) { vm.collectSamples(); dialog = null } }
        "info" -> ProjectInfoDialog(vm) { dialog = null }
        "fxpicker" -> EffectPickerDialog(vm, insertsOwner, { dialog = "installers" }, { dialog = null })
        "installers" -> InstallersDialog(vm, { importPicker.launch(arrayOf("*/*")) }, { dialog = null })
        "export" -> ExportDialog(vm.loopBars, { r, f, n, st -> vm.exportAudio(r, f, n, st) }, { dialog = null })
    }
    if (vm.exporting) ExportingDialog()
    if (vm.projectBusy) AlertDialog(onDismissRequest = {}, containerColor = Zk.panel, title = { Text("Opening project…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
    if (insertsOpen) InsertsDialog(vm, insertsOwner, { fx -> insertsOwner?.let { vm.selected = it }; vm.pluginTarget = fx; panel = Panel.Plugin; bottom = Panel.Plugin; insertsOpen = false }, { dialog = "fxpicker" }, { insertsOpen = false })
    InstallerScreen(vm)
    DesktopScreen(vm)
    if (showSettings) SettingsScreen(vm, { showUpdate = true }) { showSettings = false }
    UpdateDialog(vm, showUpdate) { showUpdate = false; Updater.dismiss() }
    com.zedokmusic.ui.nplug.KitJobDialog(vm.kits, Zk.accent)   // the kit import progress shows wherever you are in the app
    DisplaySettingsEffect(vm.playing)
}

@Composable
private fun PanelContent(vm: DawViewModel, panel: Panel, ppb: Float, setPpb: (Float) -> Unit, openPiano: () -> Unit, importTo: (TrackM) -> Unit, onInserts: (TrackM?) -> Unit) {
    when (panel) {
        Panel.Arrange -> ArrangeView(vm, ppb, setPpb, { openPiano() }, importTo)
        Panel.Piano -> {
            val t = vm.selected?.takeIf { it.kind == TrackKind.Instrument } ?: vm.tracks.firstOrNull { it.kind == TrackKind.Instrument }
            if (t == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Add an instrument track to write notes.", color = Zk.dim) }
            else Column(Modifier.fillMaxSize()) {
                val insts = vm.tracks.filter { it.kind == TrackKind.Instrument }
                if (insts.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(Zk.panel).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    insts.forEach { PanelChip(it.name, it == t) { vm.selected = it } }
                }
                Box(Modifier.weight(1f)) { PianoRollView(vm, t, ppb) }
            }
        }
        Panel.Mixer -> MixerView(vm, onInserts)
        Panel.Plugin -> PluginPanel(vm)
        Panel.Auto -> AutomationView(vm)
    }
}

@Composable
private fun PanelChip(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(if (active) Zk.accent.copy(alpha = .2f) else Zk.panel2).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (active) Zk.accent else Zk.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Transport(vm: DawViewModel, compact: Boolean, oneRow: Boolean, keys: Boolean, rollOpen: Boolean, onRecord: () -> Unit, onMidi: () -> Unit, onKeys0: () -> Unit, onKeys: () -> Unit, onAddInstrument: () -> Unit, onAddAudio: () -> Unit, onTempo: () -> Unit, onSave: () -> Unit, onExport: () -> Unit, onExports: () -> Unit, onInstallers: () -> Unit, onSettings: () -> Unit, onProject: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    // Phones: tighter sizes so the whole transport (stop, play, loop, rec, position, tempo) fits even on dense, narrow screens.
    val bs = if (compact || oneRow) 36.dp else 40.dp
    val ps = if (compact || oneRow) 40.dp else 46.dp
    val core: @Composable RowScope.() -> Unit = {
        SquareButton(Glyph.Stop, size = bs) { vm.stop() }
        SquareButton(if (vm.playing) Glyph.Pause else Glyph.Play, active = vm.playing, size = ps) { vm.togglePlay() }
        SquareButton(Glyph.Loop, active = vm.loop, size = bs) { vm.toggleLoop() }
        SquareButton(Glyph.FitLoop, size = bs) { vm.fitLoopToContent() }  // loop region = everything, ending where the content ends
        SquareButton(Glyph.Rec, active = vm.recording, tint = Zk.danger, activeColor = Zk.danger, size = bs, onClick = onRecord)
        // The box is as wide as the widest possible reading (every digit is as wide as the widest one), so the counter ticking
        // never changes its width and never pushes the buttons to its right around.
        val timeSize = if (compact) 16.sp else 19.sp
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val timeStyle = androidx.compose.ui.text.TextStyle(fontSize = timeSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
        val widest = remember(timeSize) { tm.measure("0000.0.00", timeStyle).size.width }   // up to bar 9999
        val digits = beatLabel(vm.playhead)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.bg).padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 8.dp)
            .width(with(androidx.compose.ui.platform.LocalDensity.current) { widest.toDp() }), contentAlignment = Alignment.CenterEnd) {
            Text(digits, color = Zk.accent, style = timeStyle, maxLines = 1, softWrap = false)
        }
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.panel2).clickable(onClick = onTempo).padding(horizontal = if (compact) 8.dp else 10.dp, vertical = 9.dp)) {
            Text("%.0f".format(vm.bpm) + " BPM", color = Zk.text, fontSize = if (compact) 12.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
    var fileMenu by remember { mutableStateOf(false) }
    val tools: @Composable RowScope.() -> Unit = {
        Box {
            Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.panel2).clickable { fileMenu = true }.padding(horizontal = 12.dp, vertical = 9.dp)) { Text("File", color = Zk.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            DropdownMenu(fileMenu, { fileMenu = false }) {
                DropdownMenuItem(text = { Text("New project…") }, onClick = { fileMenu = false; onProject("new") })
                DropdownMenuItem(text = { Text("Open project…") }, onClick = { fileMenu = false; onProject("open") })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Save") }, onClick = { fileMenu = false; onSave() })
                DropdownMenuItem(text = { Text("Save as…") }, onClick = { fileMenu = false; onProject("saveas") })
                DropdownMenuItem(text = { Text("Save new version") }, onClick = { fileMenu = false; onProject("version") })
                DropdownMenuItem(text = { Text("Rename project…") }, onClick = { fileMenu = false; onProject("rename") })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Export audio…") }, onClick = { fileMenu = false; onExport() })
                DropdownMenuItem(text = { Text("Exported files…") }, onClick = { fileMenu = false; onExports() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Collect samples into project") }, onClick = { fileMenu = false; onProject("collect") })
                DropdownMenuItem(text = { Text("Project info & backups…") }, onClick = { fileMenu = false; onProject("info") })
            }
        }
        Box {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.accent.copy(alpha = .2f)).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(Glyph.Plus, Zk.accent, 16.dp); Text("Add", color = Zk.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Instrument track…") }, onClick = { menu = false; onAddInstrument() })
                DropdownMenuItem(text = { Text("Audio track") }, onClick = { menu = false; onAddAudio() })
                DropdownMenuItem(text = { Text("Install plugin (.exe)…") }, onClick = { menu = false; onInstallers() })
            }
        }
        SquareButton(Glyph.Keys, active = keys, size = 38.dp, enabled = !rollOpen, onClick = onKeys)
        SquareButton(Glyph.Gear, size = 38.dp, onClick = onSettings)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.panel2).clickable(onClick = onMidi).padding(horizontal = 12.dp, vertical = 9.dp)) { Text("MIDI", color = if (vm.midi.devices.any { it.connected }) Zk.accent else Zk.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        if (vm.recording && vm.selected?.kind == TrackKind.Audio) Box(Modifier.width(60.dp).height(10.dp)) { Meter(vm.recLevel, Modifier.fillMaxSize()) }
        if (vm.loop) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SquareButton(Glyph.Minus, size = 30.dp) { vm.changeLoopBars(vm.loopBars - 1) }
            Text("${vm.loopBars} bars", color = Zk.dim, fontSize = 12.sp, maxLines = 1)
            SquareButton(Glyph.Plus, size = 30.dp) { vm.changeLoopBars(vm.loopBars + 1) }
        }
        if (!compact) Text(vm.projectName + " · " + vm.engineInfo, color = Zk.dim, fontSize = 11.sp, maxLines = 1, softWrap = false)
    }
    Column(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = if (oneRow) 3.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            core()
            if (!compact) tools()
        }
        if (compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { tools() }
        // Phones: the engine status gets its own line so it is never cut off.
        if (compact) Text(vm.projectName + " · " + vm.engineInfo, color = Zk.dim, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}
