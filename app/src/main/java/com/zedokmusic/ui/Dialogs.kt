package com.zedokmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.PluginInfo
import java.io.File

@Composable
fun PluginPickerDialog(plugins: List<PluginInfo>, folder: String, onRescan: () -> Unit, onPick: (PluginInfo) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel,
        title = { Text("Add instrument") },
        text = {
            Column {
                if (plugins.isEmpty()) Text("No VST3 plugins found.\n\nCopy your Windows .vst3 files into:\n$folder\n\nthen tap Rescan.\n\nOr use Add → Install plugin (.exe) to run the plugin's Windows installer.", color = Zk.dim, fontSize = 13.sp)
                else LazyColumn(Modifier.heightIn(max = dialogListMax(320.dp))) {
                    items(plugins) { p -> Text(p.name, color = Zk.text, modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 12.dp)); HorizontalDivider(color = Zk.line) }
                }
            }
        },
        confirmButton = { TextButton(onRescan) { Text("Rescan") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun SamplePickerDialog(samples: List<File>, folder: String, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel,
        title = { Text("Add sample") },
        text = {
            Column {
                if (samples.isEmpty()) Text("No WAV files found.\n\nCopy .wav files into:\n$folder", color = Zk.dim, fontSize = 13.sp)
                else LazyColumn(Modifier.heightIn(max = dialogListMax(320.dp))) {
                    items(samples) { f -> Text(f.name, color = Zk.text, modifier = Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 12.dp)); HorizontalDivider(color = Zk.line) }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun TempoDialog(bpm: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableFloatStateOf(bpm) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Tempo") },
        text = {
            Column {
                Text("%.0f BPM".format(v), fontSize = 28.sp, color = Zk.accent)
                Slider(v, { v = it }, valueRange = 30f..300f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ v = (v - 1).coerceAtLeast(30f) }) { Text("−1") }; OutlinedButton({ v = (v + 1).coerceAtMost(300f) }) { Text("+1") }
                }
            }
        },
        confirmButton = { TextButton({ onChange(Math.round(v).toFloat()); onDismiss() }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun MidiDialog(midi: com.zedokmusic.engine.MidiInput, onScan: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("MIDI input") },
        text = {
            Column(Modifier.heightIn(max = dialogListMax(380.dp)).verticalScroll(rememberScrollState())) {
                if (midi.devices.isEmpty()) Text("No USB MIDI device detected. Plug in a keyboard (USB-OTG) or scan for a Bluetooth one.", color = Zk.dim, fontSize = 13.sp)
                midi.devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { d.info?.let { midi.connect(it) } }.padding(vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(d.name, color = Zk.text, modifier = Modifier.weight(1f)); Text(if (d.connected) "connected" else "tap to connect", color = if (d.connected) Zk.accent else Zk.dim, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Zk.line)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onScan) { Text(if (midi.scanning) "Scanning…" else "Scan Bluetooth MIDI") }
                midi.bleFound.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { midi.connectBle(d) }.padding(vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(d.name, color = Zk.text, modifier = Modifier.weight(1f)); Text(if (d.connected) "connected" else "tap to connect", color = if (d.connected) Zk.accent else Zk.dim, fontSize = 12.sp)
                    }
                }
                if (midi.status.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(midi.status, color = Zk.dim, fontSize = 12.sp) }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } })
}

@Composable
fun ExportDialog(loopBars: Int, onExport: (range: Int, format: Int, name: String, stems: Boolean) -> Unit, onDismiss: () -> Unit) {
    var range by remember { mutableIntStateOf(0) }
    var format by remember { mutableIntStateOf(com.zedokmusic.settings.AppSettings.exportFormat.value) }
    var name by remember { mutableStateOf("Mix") }
    var stems by remember { mutableStateOf(false) }
    @Composable fun option(label: String, selected: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected, onClick); Text(label, fontSize = 14.sp)
        }
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Export audio") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Range", color = Zk.dim, fontSize = 12.sp)
            option("Whole project", range == 0) { range = 0 }
            option("Loop region ($loopBars bars)", range == 1) { range = 1 }
            Spacer(Modifier.height(4.dp))
            Text("Format", color = Zk.dim, fontSize = 12.sp)
            option("WAV 16-bit", format == 0) { format = 0 }
            option("WAV 24-bit", format == 1) { format = 1 }
            option("WAV 32-bit float", format == 2) { format = 2 }
            option("FLAC 16-bit (lossless)", format == 4) { format = 4 }
            option("FLAC 24-bit (lossless)", format == 5) { format = 5 }
            option("MP3 320 kbps", format == 6) { format = 6 }
            option("MP3 192 kbps", format == 7) { format = 7 }
            option("AAC .m4a (256 kbps)", format == 3) { format = 3 }
            Spacer(Modifier.height(4.dp))
            Text("Content", color = Zk.dim, fontSize = 12.sp)
            option("Full mix", !stems) { stems = false }
            option("Each track separately (stems)", stems) { stems = true }
            Text("Saved in the Exports folder of your workspace.", color = Zk.dim, fontSize = 11.sp)
        }
    }, confirmButton = { TextButton({ com.zedokmusic.settings.AppSettings.exportFormat.set(format); onExport(range, format, name, stems); onDismiss() }) { Text("Export") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun ExportingDialog() {
    AlertDialog(onDismissRequest = {}, containerColor = Zk.panel, title = { Text("Exporting…") }, text = {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp)); Text("Rendering the mix with your plugins. This can take a while.", fontSize = 13.sp)
        }
    }, confirmButton = {})
}

/** Browser for the Exports folder: play, share with other apps, delete. */
@Composable
fun ExportsDialog(vm: com.zedokmusic.model.DawViewModel, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var files by remember { mutableStateOf(vm.exportedFiles()) }
    var playing by remember { mutableStateOf<File?>(null) }
    var confirm by remember { mutableStateOf<File?>(null) }
    val player = remember { android.media.MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.stop() }; player.release() } }
    fun toggle(f: File) {
        if (playing == f) { runCatching { player.stop() }; playing = null; return }
        runCatching { player.reset(); player.setDataSource(f.absolutePath); player.prepare(); player.setOnCompletionListener { playing = null }; player.start(); playing = f }
            .onFailure { playing = null; vm.message = "Cannot play ${f.name}." }
    }
    fun share(f: File) {
        val mime = when (f.extension.lowercase()) { "wav" -> "audio/wav"; "flac" -> "audio/flac"; "mp3" -> "audio/mpeg"; else -> "audio/mp4" }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType(mime).putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Share ${f.name}")) }.onFailure { vm.message = "No app can receive this file." }
    }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Exported files") }, text = {
        if (files.isEmpty()) Text("Nothing exported yet. Use Add → Export audio….", color = Zk.dim, fontSize = 13.sp)
        else LazyColumn(Modifier.heightIn(max = dialogListMax(420.dp))) {
            items(files, key = { it.absolutePath }) { f ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(f.name, color = Zk.text, fontSize = 14.sp, maxLines = 1)
                    Text("%.1f MB · %s".format(f.length() / 1048576.0, java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(f.lastModified())), color = Zk.dim, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ toggle(f) }) { Text(if (playing == f) "Stop" else "Play") }
                        TextButton({ share(f) }) { Text("Share") }
                        TextButton({ confirm = f }) { Text("Delete", color = Zk.danger) }
                    }
                }
                HorizontalDivider(color = Zk.line)
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
    confirm?.let { f ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Zk.panel, title = { Text("Delete ${f.name}?") },
            confirmButton = { TextButton({ if (playing == f) { runCatching { player.stop() }; playing = null }; f.delete(); files = vm.exportedFiles(); confirm = null }) { Text("Delete", color = Zk.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}
