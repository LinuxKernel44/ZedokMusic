package com.zedokmusic.ui

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.settings.AppSettings
import com.zedokmusic.settings.Setting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private enum class Section(val label: String) { Audio("Audio"), Project("Project"), Plugins("Plugins"), Midi("MIDI"), Display("Display"), Storage("Storage"), About("About") }

/** All user settings. Wide / landscape: a category list on the left and the chosen category on the right; narrow: one scrolling list. */
@Composable
fun SettingsScreen(vm: DawViewModel, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var section by rememberSaveable { mutableStateOf(Section.Audio) }
    Surface(Modifier.fillMaxSize(), color = Zk.bg) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))) {
            Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SquareButton(Glyph.Close, size = 36.dp, onClick = onClose)
                Text("Settings", color = Zk.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                if (maxWidth >= 700.dp) Row(Modifier.fillMaxSize()) {
                    Column(Modifier.width(170.dp).fillMaxHeight().background(Zk.panel).verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Section.values().forEach { s -> CategoryChip(s.label, s == section) { section = s } }
                    }
                    VerticalDivider(color = Zk.line)
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Box(Modifier.widthIn(max = 640.dp)) { Column { SectionContent(section, vm) } }
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp)) {
                    Section.values().forEach { s -> Header(s.label); SectionContent(s, vm) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable private fun CategoryChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (active) Zk.accent.copy(alpha = .2f) else Zk.panel2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp)) {
        Text(text, color = if (active) Zk.accent else Zk.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun Header(text: String) = Text(text.uppercase(), color = Zk.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))

@Composable private fun Row2(title: String, desc: String?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Zk.text, fontSize = 15.sp)
            if (desc != null) Text(desc, color = Zk.dim, fontSize = 12.sp)
        }
        content()
    }
    HorizontalDivider(color = Zk.line.copy(alpha = .6f))
}

@Composable private fun SwitchRow(title: String, desc: String?, s: Setting, onChange: (Boolean) -> Unit = {}) =
    Row2(title, desc) { Switch(s.on, { s.set(it); onChange(it) }, colors = SwitchDefaults.colors(checkedTrackColor = Zk.accent, checkedThumbColor = Zk.bg)) }

/** A row of mutually exclusive choices below the title. */
@Composable private fun ChoiceRow(title: String, desc: String?, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, color = Zk.text, fontSize = 15.sp)
        if (desc != null) Text(desc, color = Zk.dim, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            labels.forEachIndexed { i, l ->
                Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (i == selected) Zk.accent else Zk.panel2).clickable { onSelect(i) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(l, color = if (i == selected) Color(0xFF00201B) else Zk.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
    HorizontalDivider(color = Zk.line.copy(alpha = .6f))
}

@Composable private fun ActionRow(title: String, desc: String?, button: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) =
    Row2(title, desc) { OutlinedButton(onClick, enabled = enabled) { Text(button, color = if (danger && enabled) Zk.danger else Color.Unspecified, fontSize = 13.sp) } }

@Composable private fun InfoRow(label: String, value: String) = Row2(label, null) { Text(value, color = Zk.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) }

private data class Dev(val id: Int, val label: String)

private fun typeName(t: Int) = when (t) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"; AudioDeviceInfo.TYPE_BUILTIN_MIC -> "microphone"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"; AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "line"
    else -> "other"
}

private fun devices(ctx: Context, output: Boolean): List<Dev> {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val skip = setOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX, AudioDeviceInfo.TYPE_FM, AudioDeviceInfo.TYPE_FM_TUNER)
    return am.getDevices(if (output) AudioManager.GET_DEVICES_OUTPUTS else AudioManager.GET_DEVICES_INPUTS)
        .filter { it.type !in skip }.map { Dev(it.id, "${it.productName} (${typeName(it.type)})") }
}

@Composable private fun DeviceRow(title: String, desc: String, output: Boolean, s: Setting, onChange: () -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf(emptyList<Dev>()) }
    val current = if (s.value == 0) "Automatic" else list.firstOrNull { it.id == s.value }?.label ?: devices(ctx, output).firstOrNull { it.id == s.value }?.label ?: "Unavailable (using automatic)"
    Row2(title, desc) {
        Box {
            OutlinedButton({ list = devices(ctx, output); open = true }) { Text(current, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp)) }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("Automatic") }, onClick = { open = false; s.set(0); onChange() })
                list.forEach { d -> DropdownMenuItem(text = { Text(d.label) }, onClick = { open = false; s.set(d.id); onChange() }) }
            }
        }
    }
}

@Composable private fun SectionContent(section: Section, vm: DawViewModel) {
    when (section) {
        Section.Audio -> AudioSection(vm)
        Section.Project -> ProjectSection()
        Section.Plugins -> PluginsSection()
        Section.Midi -> MidiSection(vm)
        Section.Display -> DisplaySection()
        Section.Storage -> StorageSection(vm)
        Section.About -> AboutSection()
    }
}

@Composable private fun AudioSection(vm: DawViewModel) {
    var status by remember { mutableStateOf(vm.audioStatus()) }
    fun restart() { vm.restartAudio(); status = vm.audioStatus() }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); status = vm.audioStatus() } }
    DeviceRow("Output device", "Where the sound plays: the phone, a USB audio interface or headphones.", true, AppSettings.outputDevice) { restart() }
    DeviceRow("Input device", "Used when recording an audio track.", false, AppSettings.inputDevice) {}
    ChoiceRow("Latency", "Lowest has the least delay; use a safer setting if you hear crackles or the xruns counter climbs.", listOf("Lowest", "Balanced", "Safe"), AppSettings.latency.value) { AppSettings.latency.set(it); restart() }
    SwitchRow("Exclusive mode", "Asks Android for direct access to the audio hardware for lower latency. Falls back to shared when unavailable.", AppSettings.exclusive) { restart() }
    Row2("Current stream", null) {}
    Text(status, color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
    ActionRow("Restart audio", "Re-opens the output with these settings. Playback stops.", "Restart") { restart() }
}

@Composable private fun ProjectSection() {
    SwitchRow("Autosave", "Saves the project a moment after each change when the transport is stopped. Off: use Add → Save project.", AppSettings.autosave)
    SwitchRow("Follow the play cursor", "Timelines scroll along with the cursor while playing.", AppSettings.follow)
    ChoiceRow("Piano roll grid", "Notes snap to this step (as a note value) when you add or move them.", AppSettings.SNAP_LABELS, AppSettings.snap.value) { AppSettings.snap.set(it) }
    ChoiceRow("Default note length", "Length of a note added with a tap in the piano roll.", AppSettings.NOTE_LABELS, AppSettings.noteLength.value) { AppSettings.noteLength.set(it) }
    ChoiceRow("Default export format", "Preselected in Export audio.", listOf("WAV 16", "WAV 24", "WAV float", "FLAC 16", "FLAC 24", "MP3 320", "MP3 192", "AAC"),
        listOf(0, 1, 2, 4, 5, 6, 7, 3).indexOf(AppSettings.exportFormat.value).coerceAtLeast(0)) { AppSettings.exportFormat.set(listOf(0, 1, 2, 4, 5, 6, 7, 3)[it]) }
}

@Composable private fun PluginsSection() {
    ChoiceRow("Editor refresh rate", "How often a plugin's window is redrawn on screen. Lower saves battery and CPU.", listOf("10 fps", "15 fps", "30 fps", "60 fps"),
        listOf(10, 15, 30, 60).indexOf(AppSettings.editorFps.value).coerceAtLeast(0)) { AppSettings.editorFps.set(listOf(10, 15, 30, 60)[it]) }
    SwitchRow("Verbose Wine log", "Records Wine's error messages for plugins and installers (applies the next time one starts). Use Storage → Share diagnostics to send it.", AppSettings.wineLog)
}

@Composable private fun MidiSection(vm: DawViewModel) {
    SwitchRow("Connect MIDI devices automatically", "Opens USB MIDI keyboards as soon as they are plugged in (and at startup).", AppSettings.midiAuto)
    ChoiceRow("MIDI channel", "Only notes on this channel are played. All = every channel.", listOf("All") + (1..16).map { "$it" }, AppSettings.midiChannel.value) { AppSettings.midiChannel.set(it) }
    InfoRow("Connected", vm.midi.devices.filter { it.connected }.joinToString { it.name }.ifEmpty { "none" })
}

@Composable private fun DisplaySection() {
    ChoiceRow("Keep the screen on", "Stops the screen from turning off.", listOf("Never", "While playing", "Always"), AppSettings.keepOn.value) { AppSettings.keepOn.set(it) }
    ChoiceRow("Orientation", "Landscape gives the arrangement and the plugin windows more room.", listOf("Automatic", "Portrait", "Landscape"), AppSettings.orientation.value) { AppSettings.orientation.set(it) }
    SwitchRow("Full screen", "Hides the status and navigation bars (swipe from the edge to bring them back).", AppSettings.immersive)
}

private fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
private fun fmt(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else if (b >= 1L shl 20) "%.1f MB".format(b / 1048576.0) else "%d KB".format(b / 1024)

@Composable private fun StorageSection(vm: DawViewModel) {
    val ctx = LocalContext.current
    val ws = vm.workspace
    var refresh by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<List<Pair<String, Long>>?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh) {
        sizes = null
        sizes = withContext(Dispatchers.IO) {
            if (ws == null) emptyList() else listOf("Projects (with their samples)" to "Projects", "Shared samples" to "Samples", "Exports" to "Exports", "Installers" to "Installers", "Plugins" to "Plugins", "Windows C: drive" to "C")
                .map { (label, dir) -> label to dirSize(File(ws, dir)) }
        }
    }
    InfoRow("Workspace folder", ws?.path ?: "not set")
    sizes?.forEach { (l, b) -> InfoRow(l, fmt(b)) } ?: Text("Measuring…", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
    ActionRow("Delete exported files", "Empties the Exports folder.", "Delete…", danger = true) { confirm = "Exports" }
    ActionRow("Delete installers", "Empties the Installers folder. Installed plugins are kept.", "Delete…", danger = true) { confirm = "Installers" }
    ActionRow("Share diagnostics", "Sends a text report (versions, audio status, recent Wine logs) to an app you choose. Nothing is sent by ZedokMusic itself.", "Share") { shareDiagnostics(ctx, vm) }
    confirm?.let { dir ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Zk.panel, title = { Text("Delete everything in $dir?") },
            text = { Text("This cannot be undone.", color = Zk.dim) },
            confirmButton = { TextButton({ ws?.let { File(it, dir).listFiles()?.forEach { f -> f.deleteRecursively() } }; confirm = null; refresh++ }) { Text("Delete", color = Zk.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

@Composable private fun AboutSection() {
    val ctx = LocalContext.current
    var licenses by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    InfoRow("ZedokMusic", version)
    InfoRow("License", "MIT, free and open source")
    ActionRow("Project page", "Source code, releases and bug reports on GitHub.", "Open") {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/LinuxKernel44/ZedokMusic")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    ActionRow("Open-source licenses", "Wine, Box64, Oboe, LAME and the other components inside the app.", "View") { licenses = true }
    ActionRow("Reset all settings", "Puts every setting back to its default. Your projects are not touched.", "Reset…", danger = true) { confirmReset = true }
    if (licenses) AlertDialog(onDismissRequest = { licenses = false }, containerColor = Zk.panel, title = { Text("Open-source licenses") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("ZedokMusic is MIT-licensed. It bundles, as separate programs or libraries:", color = Zk.dim, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            listOf("Wine 10.10 — LGPL-2.1+", "Box64 0.4.4 — MIT", "GNU C Library (aarch64) — LGPL-2.1+", "Winlator 11.2 runtime — MIT", "Oboe 1.10 — Apache-2.0", "Zstandard — BSD-3-Clause",
                "LAME 3.100 (MP3) — LGPL-2.1", "Steinberg VST 3 interfaces — MIT", "Xvfb, xkbcomp and X.Org libraries — MIT/BSD-style", "AndroidX, Jetpack Compose, Material 3 — Apache-2.0")
                .forEach { Text("• $it", color = Zk.text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            Spacer(Modifier.height(8.dp))
            Text("Full list, sources and license texts: THIRD_PARTY_NOTICES.md in the project repository. VST is a trademark of Steinberg Media Technologies GmbH.", color = Zk.dim, fontSize = 12.sp)
        }
    }, confirmButton = { TextButton({ licenses = false }) { Text("Close") } })
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, containerColor = Zk.panel, title = { Text("Reset all settings?") },
        confirmButton = { TextButton({ AppSettings.resetAll(); confirmReset = false }) { Text("Reset", color = Zk.danger) } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

/** Builds a plain-text report and offers it to the system share sheet. */
private fun shareDiagnostics(ctx: Context, vm: DawViewModel) {
    fun tail(f: File, lines: Int = 120): String = if (!f.exists()) "(none)" else runCatching { f.readLines().takeLast(lines).joinToString("\n").take(24_000) }.getOrDefault("(unreadable)")
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
    val sb = StringBuilder()
    sb.append("ZedokMusic $version\nAndroid ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n")
    sb.append("Audio: ${vm.audioStatus()}\n")
    sb.append("Settings: latency=${AppSettings.latency.value} exclusive=${AppSettings.exclusive.value} fps=${AppSettings.editorFps.value} midiAuto=${AppSettings.midiAuto.value} midiChannel=${AppSettings.midiChannel.value}\n")
    sb.append("Tracks: ${vm.tracks.joinToString { "${it.name}[${it.pluginState}]" }}\n")
    val files = ctx.filesDir
    listOf("xvfb.log", "installer.log", "wineboot.log").forEach { sb.append("\n=== $it ===\n").append(tail(File(files, it))).append('\n') }
    files.listFiles { f -> f.name.startsWith("host_") && f.name.endsWith(".log") }?.sortedBy { it.name }?.forEach { sb.append("\n=== ${it.name} ===\n").append(tail(it, 60)).append('\n') }
    val out = File(files, "diagnostics.txt"); out.writeText(sb.toString())
    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", out)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, "ZedokMusic diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Applies the Display settings to the activity window: keep-awake, orientation and full screen. */
@Composable
fun DisplaySettingsEffect(playing: Boolean) {
    val activity = LocalContext.current as? android.app.Activity ?: return
    val keep = AppSettings.keepOn.value; val orient = AppSettings.orientation.value; val full = AppSettings.immersive.on
    DisposableEffect(keep, playing) {
        val on = keep == 2 || (keep == 1 && playing)
        if (on) activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(orient) {
        activity.requestedOrientation = when (orient) { 1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; 2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(full) {
        val c = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (full) { c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
        else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
}
