package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

private enum class Panel(val label: String) { Arrange("Tracks"), Piano("Piano roll"), Mixer("Mixer"), Plugin("Plugin"), Auto("Auto") }

@Composable
fun DawScreen(vm: DawViewModel) {
    var panel by rememberSaveable { mutableStateOf(Panel.Arrange) }
    var bottom by rememberSaveable { mutableStateOf(Panel.Piano) }
    var showKeys by rememberSaveable { mutableStateOf(false) }
    var octave by rememberSaveable { mutableIntStateOf(3) }
    var ppb by rememberSaveable { mutableFloatStateOf(44f) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var plugins by remember { mutableStateOf(emptyList<PluginInfo>()) }
    val sel = vm.selected
    val ctx = LocalContext.current
    fun begin() { vm.startRecording()?.let { vm.message = it } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) begin() else vm.message = "Microphone permission is needed to record audio." }
    val locPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.midi.startScan() else vm.message = "Location permission is needed by Android to scan for Bluetooth devices." }
    fun onRecord() {
        if (vm.recording) { vm.stopRecording(); return }
        if (vm.selected?.kind == TrackKind.Audio && ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPermission.launch(Manifest.permission.RECORD_AUDIO) else begin()
    }
    fun onScan() { if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.midi.startScan() else locPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION) }

    Box(Modifier.fillMaxSize().background(Zk.bg).statusBarsPadding().navigationBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Column(Modifier.fillMaxSize()) {
                Transport(vm, !wide, showKeys, onRecord = { onRecord() }, onMidi = { dialog = "midi" }, onKeys0 = {}, { showKeys = !showKeys }, onAddInstrument = { plugins = vm.scanPlugins(); dialog = "plugins" },
                    onAddAudio = { vm.addAudioTrack() }, onTempo = { dialog = "tempo" }, onSave = { vm.save(); vm.message = "Project saved" }, onExport = { dialog = "export" })
                if (wide) {
                    Box(Modifier.weight(1f)) { ArrangeView(vm, ppb, { ppb = it }, { panel = Panel.Piano; bottom = Panel.Piano }, { vm.selected = it; dialog = "samples" }) }
                    HorizontalDivider(color = Zk.line)
                    Column(Modifier.weight(0.9f)) {
                        Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (p in listOf(Panel.Piano, Panel.Mixer, Panel.Plugin, Panel.Auto)) PanelChip(p.label, bottom == p) { bottom = p }
                        }
                        Box(Modifier.weight(1f)) { PanelContent(vm, bottom, ppb, { ppb = it }, {}, {}) }
                    }
                } else {
                    Box(Modifier.weight(1f)) {
                        PanelContent(vm, panel, ppb, { ppb = it }, { panel = Panel.Piano }, { vm.selected = it; dialog = "samples" })
                    }
                    Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (p in Panel.values()) PanelChip(p.label, panel == p, Modifier.weight(1f)) { panel = p }
                    }
                }
                if (showKeys) {
                    val t = if (sel?.kind == TrackKind.Instrument) sel else vm.tracks.firstOrNull { it.kind == TrackKind.Instrument }
                    if (t != null) PianoKeyboard(octave, { octave = it }, t.color, { on, p -> vm.liveNote(t, on, p) }, Modifier.fillMaxWidth().height(if (wide) 150.dp else 130.dp))
                }
            }
            if (vm.message != null) {
                Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton({ vm.message = null }) { Text("OK") } }) { Text(vm.message ?: "") }
            }
        }
    }
    when (dialog) {
        "plugins" -> PluginPickerDialog(plugins, vm.pluginDir()?.path ?: "", { plugins = vm.scanPlugins() }, { vm.addInstrument(it); dialog = null }, { dialog = null })
        "samples" -> SamplePickerDialog(vm.samples(), (vm.workspace?.path ?: "") + "/Samples", { f ->
            val t = vm.selected?.takeIf { it.kind == TrackKind.Audio } ?: vm.addAudioTrack(); if (t != null) vm.importClip(t, f, vm.playhead.let { Math.floor(it.toDouble()).toFloat() }); dialog = null }, { dialog = null })
        "midi" -> MidiDialog(vm.midi, { onScan() }, { dialog = null })
        "tempo" -> TempoDialog(vm.bpm, { vm.changeBpm(it) }, { dialog = null })
        "export" -> ExportDialog(vm.loopBars, { r, f, n -> vm.exportWav(r, f, n) }, { dialog = null })
    }
    if (vm.exporting) ExportingDialog()
}

@Composable
private fun PanelContent(vm: DawViewModel, panel: Panel, ppb: Float, setPpb: (Float) -> Unit, openPiano: () -> Unit, importTo: (TrackM) -> Unit) {
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
        Panel.Mixer -> MixerView(vm)
        Panel.Plugin -> PluginPanel(vm)
        Panel.Auto -> AutomationView(vm)
    }
}

@Composable
private fun PanelChip(text: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(if (active) Zk.accent.copy(alpha = .2f) else Zk.panel2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (active) Zk.accent else Zk.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Transport(vm: DawViewModel, compact: Boolean, keys: Boolean, onRecord: () -> Unit, onMidi: () -> Unit, onKeys0: () -> Unit, onKeys: () -> Unit, onAddInstrument: () -> Unit, onAddAudio: () -> Unit, onTempo: () -> Unit, onSave: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val core: @Composable RowScope.() -> Unit = {
        SquareButton(Glyph.Stop) { vm.stop() }
        SquareButton(if (vm.playing) Glyph.Pause else Glyph.Play, active = vm.playing, size = 46.dp) { vm.togglePlay() }
        SquareButton(Glyph.Loop, active = vm.loop) { vm.toggleLoop() }
        SquareButton(Glyph.Rec, active = vm.recording, tint = Zk.danger, activeColor = Zk.danger, onClick = onRecord)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.bg).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(beatLabel(vm.playhead), color = Zk.accent, fontSize = 19.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.panel2).clickable(onClick = onTempo).padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text("%.0f".format(vm.bpm) + " BPM", color = Zk.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
    val tools: @Composable RowScope.() -> Unit = {
        Box {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.accent.copy(alpha = .2f)).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(Glyph.Plus, Zk.accent, 16.dp); Text("Add", color = Zk.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Instrument track (VST3)…") }, onClick = { menu = false; onAddInstrument() })
                DropdownMenuItem(text = { Text("Audio track") }, onClick = { menu = false; onAddAudio() })
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Save project") }, onClick = { menu = false; onSave() })
                DropdownMenuItem(text = { Text("Export audio…") }, onClick = { menu = false; onExport() })
            }
        }
        SquareButton(Glyph.Keys, active = keys, size = 38.dp, onClick = onKeys)
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Zk.panel2).clickable(onClick = onMidi).padding(horizontal = 12.dp, vertical = 9.dp)) { Text("MIDI", color = if (vm.midi.devices.any { it.connected }) Zk.accent else Zk.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        if (vm.recording && vm.selected?.kind == TrackKind.Audio) Box(Modifier.width(60.dp).height(10.dp)) { Meter(vm.recLevel, Modifier.fillMaxSize()) }
        if (vm.loop) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SquareButton(Glyph.Minus, size = 30.dp) { vm.changeLoopBars(vm.loopBars - 1) }
            Text("${vm.loopBars} bars", color = Zk.dim, fontSize = 12.sp, maxLines = 1)
            SquareButton(Glyph.Plus, size = 30.dp) { vm.changeLoopBars(vm.loopBars + 1) }
        }
        Text(vm.engineInfo, color = Zk.dim, fontSize = 11.sp, maxLines = 1)
    }
    Column(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            core()
            if (!compact) tools()
        }
        if (compact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { tools() }
    }
}
