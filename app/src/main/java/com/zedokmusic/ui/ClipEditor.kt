package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.AudioFx
import com.zedokmusic.model.ClipM
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.TrackM
import com.zedokmusic.model.Theory
import kotlinx.coroutines.launch
import java.io.File

/**
 * Editor of one audio clip: gain (heard at once), pitch, speed (with "fit to the project BPM") and the way pitch / speed are changed.
 * Pitch and speed render a processed copy of the sample in the background; the original file is kept and "Reset" goes back to it.
 */
@Composable
fun ClipEditDialog(vm: DawViewModel, t: TrackM, c: ClipM, group: List<ClipM> = listOf(c), onDismiss: () -> Unit) {
    var semis by remember(c) { mutableFloatStateOf(c.semis) }
    var speed by remember(c) { mutableFloatStateOf(c.speed) }
    var mode by remember(c) { mutableIntStateOf(c.mode) }
    var bpmText by remember(c) { mutableStateOf(if (c.srcBpm > 0f) "%.1f".format(c.srcBpm).removeSuffix(".0") else "") }
    val srcBpm = bpmText.replace(',', '.').toFloatOrNull()?.takeIf { it in 20f..400f } ?: 0f
    val color = t.color
    val srcLen = produceState(0f, c.src) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AudioFx.wavSeconds(File(c.src)) } }.value
    // output duration: Stretch = source / speed; Resample = source / (speed x pitch ratio), because pitch and speed move together
    val pitchRatio = if (mode == AudioFx.MODE_RESAMPLE) Math.pow(2.0, semis / 12.0).toFloat() else 1f
    val outSec = if (srcLen > 0f) srcLen / (speed * pitchRatio) else 0f
    val endBeat = c.start + outSec * vm.bpm / 60f
    val onBar = outSec > 0f && Math.abs(endBeat / 4f - Math.round(endBeat / 4f)) * 4f < 0.004f
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text(if (group.size > 1) "${group.size} clips" else File(c.src).nameWithoutExtension, fontSize = 17.sp, maxLines = 3) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Section("Gain")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(c.gainDb, { g -> vm.setClipsGain(group, g) }, Modifier.weight(1f), valueRange = -24f..12f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
                Text("%+.1f dB".format(c.gainDb), color = Zk.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                Chip("0", false) { vm.setClipsGain(group, 0f) }
            }
            Section("Pitch")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("−12", false) { semis = (semis - 12f).coerceIn(-48f, 48f) }; Chip("−1", false) { semis = (semis - 1f).coerceIn(-48f, 48f) }
                Text("%+.1f st".format(semis), color = Zk.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp), maxLines = 1)
                Chip("+1", false) { semis = (semis + 1f).coerceIn(-48f, 48f) }; Chip("+12", false) { semis = (semis + 12f).coerceIn(-48f, 48f) }; Chip("0", false) { semis = 0f }
            }
            Slider(semis, { semis = (it * 10f).toInt() / 10f }, valueRange = -24f..24f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Section("Time"); Spacer(Modifier.weight(1f))
                // stretches or shrinks the clip so that it ends exactly on the nearest bar line (4/4)
                Chip("Fit to bar", onBar, enabled = srcLen > 0f) {
                    var target = Math.round(endBeat / 4f) * 4f
                    while (target < c.start + 1f) target += 4f
                    val sec = (target - c.start) * 60f / vm.bpm
                    speed = (srcLen / (sec * pitchRatio)).coerceIn(0.25f, 4f)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(speed, { speed = (it * 1000f).toInt() / 1000f }, Modifier.weight(1f), valueRange = 0.25f..4f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
                Text("×%.3f".format(speed), color = Zk.text, fontSize = 12.sp, modifier = Modifier.width(66.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (v in listOf(0.5f, 0.75f, 1f, 1.5f, 2f)) Chip(if (v == 1f) "1×" else "$v×", speed == v) { speed = v } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample BPM", color = Zk.dim, fontSize = 12.sp)
                Box(Modifier.width(70.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(Zk.panel2).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (bpmText.isEmpty()) Text("e.g. 90", color = Zk.dim, fontSize = 12.sp)
                    BasicTextField(bpmText, { bpmText = it.take(6) }, singleLine = true, textStyle = TextStyle(color = Zk.text, fontSize = 13.sp), cursorBrush = SolidColor(color),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                Chip("Fit to ${vm.bpm.toInt()} BPM", false, enabled = srcBpm > 0f) { speed = (vm.bpm / srcBpm).coerceIn(0.25f, 4f) }
            }
            if (srcLen > 0f) Text("Length: %.2f s → %.2f s".format(srcLen, outSec) + if (onBar) " · ends on a bar line (bar ${Math.round(endBeat / 4f) + 1})" else "", color = if (onBar) color else Zk.dim, fontSize = 11.sp)
            Section("Method")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Stretch", mode == AudioFx.MODE_STRETCH) { mode = AudioFx.MODE_STRETCH }
                Chip("Resample", mode == AudioFx.MODE_RESAMPLE) { mode = AudioFx.MODE_RESAMPLE }
            }
            Text(if (mode == AudioFx.MODE_STRETCH) "Stretch: speed changes the length without changing the pitch, pitch changes the pitch without changing the length." else "Resample: speed and pitch move together, like a record played faster or slower (clean for drums and one-shots).", color = Zk.dim, fontSize = 11.sp)
            if (c.busy) Text("Processing…", color = color, fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton({ if (group.size > 1) vm.applyClipEdits(group, semis, speed, mode, srcBpm) else vm.applyClipEdit(t, c, semis, speed, mode, srcBpm); onDismiss() }, enabled = group.none { it.busy }) { Text("Apply") }
    }, dismissButton = {
        Row { TextButton({ semis = 0f; speed = 1f; vm.setClipsGain(group, 0f); vm.applyClipEdits(group, 0f, 1f, mode, srcBpm); onDismiss() }) { Text("Reset", color = Zk.danger) }; TextButton(onDismiss) { Text("Cancel") } }
    })
}

@Composable private fun Section(text: String) = Text(text.uppercase(), color = Zk.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

@Composable private fun Chip(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Zk.accent else Zk.panel2).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(text, color = if (!enabled) Zk.dim.copy(alpha = .4f) else if (on) Color(0xFF00201B) else Zk.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * Edits the sample of a Zedok Sampler (the clip editor's pitch / time / gain, for a sound that is not a clip): a sample that is not a C can be moved to the nearest C,
 * stretched to the tempo, and so on. "Use in sampler" renders the new version into the project's Samples folder and loads it in the sampler.
 */
@Composable
fun SampleEditDialog(vm: DawViewModel, t: TrackM, path: String, onDismiss: () -> Unit) {
    var semis by remember { mutableFloatStateOf(0f) }; var speed by remember { mutableFloatStateOf(1f) }; var gainDb by remember { mutableFloatStateOf(0f) }
    var mode by remember { mutableIntStateOf(AudioFx.MODE_STRETCH) }
    var bpmText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val srcBpm = bpmText.replace(',', '.').toFloatOrNull()?.takeIf { it in 20f..400f } ?: 0f
    val color = com.zedokmusic.ui.nplug.accentOf("sampler")
    val srcLen by produceState(0f, path) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AudioFx.wavSeconds(File(path)) } }
    val hz by produceState(0f, path) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AudioFx.readWav(File(path))?.let { AudioFx.detectPitchHz(it) } ?: 0f } }
    val scope = rememberCoroutineScope()
    val midi = if (hz > 0f) 69f + 12f * (Math.log((hz / 440f).toDouble()) / Math.log(2.0)).toFloat() else Float.NaN
    val pitchRatio = if (mode == AudioFx.MODE_RESAMPLE) Math.pow(2.0, semis / 12.0).toFloat() else 1f
    val outSec = if (srcLen > 0f) srcLen / (speed * pitchRatio) else 0f
    val changed = Math.abs(semis) > 1e-3f || Math.abs(speed - 1f) > 1e-4f || Math.abs(gainDb) > 1e-3f
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, containerColor = Zk.panel, title = { Text(File(path).nameWithoutExtension, fontSize = 17.sp, maxLines = 3) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!midi.isNaN()) Text("Pitch of the sample: ${Theory.name(Math.round(midi))} (${"%+d".format(Math.round((midi - Math.round(midi)) * 100f))} cents)", color = Zk.text, fontSize = 13.sp)
            else Text("No clear pitch found (drums, noise or a chord).", color = Zk.dim, fontSize = 12.sp)
            Section("Pitch")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("−12", false) { semis = (semis - 12f).coerceIn(-48f, 48f) }; Chip("−1", false) { semis = (semis - 1f).coerceIn(-48f, 48f) }
                Text("%+.1f st".format(semis), color = Zk.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp), maxLines = 1)
                Chip("+1", false) { semis = (semis + 1f).coerceIn(-48f, 48f) }; Chip("+12", false) { semis = (semis + 12f).coerceIn(-48f, 48f) }; Chip("0", false) { semis = 0f }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("To nearest C", false, enabled = !midi.isNaN()) { val c = Math.round(midi / 12f) * 12f; semis = (Math.round((c - midi) * 10f) / 10f).coerceIn(-48f, 48f) }
                Text("moves the sample to a C, so the root note C is right", color = Zk.dim, fontSize = 11.sp)
            }
            Slider(semis, { semis = (it * 10f).toInt() / 10f }, valueRange = -24f..24f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
            Section("Time")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(speed, { speed = (it * 1000f).toInt() / 1000f }, Modifier.weight(1f), valueRange = 0.25f..4f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
                Text("×%.3f".format(speed), color = Zk.text, fontSize = 12.sp, modifier = Modifier.width(66.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { for (v in listOf(0.5f, 0.75f, 1f, 1.5f, 2f)) Chip(if (v == 1f) "1×" else "$v×", speed == v) { speed = v } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sample BPM", color = Zk.dim, fontSize = 12.sp)
                Box(Modifier.width(70.dp).height(34.dp).clip(RoundedCornerShape(8.dp)).background(Zk.panel2).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (bpmText.isEmpty()) Text("e.g. 90", color = Zk.dim, fontSize = 12.sp)
                    BasicTextField(bpmText, { bpmText = it.take(6) }, singleLine = true, textStyle = TextStyle(color = Zk.text, fontSize = 13.sp), cursorBrush = SolidColor(color), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                Chip("Fit to ${vm.bpm.toInt()} BPM", false, enabled = srcBpm > 0f) { speed = (vm.bpm / srcBpm).coerceIn(0.25f, 4f) }
            }
            if (srcLen > 0f) Text("Length: %.2f s → %.2f s".format(srcLen, outSec), color = Zk.dim, fontSize = 11.sp)
            Section("Gain")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(gainDb, { gainDb = (it * 10f).toInt() / 10f }, Modifier.weight(1f), valueRange = -24f..12f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
                Text("%+.1f dB".format(gainDb), color = Zk.text, fontSize = 12.sp, modifier = Modifier.width(64.dp)); Chip("0", false) { gainDb = 0f }
            }
            Section("Method")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("Stretch", mode == AudioFx.MODE_STRETCH) { mode = AudioFx.MODE_STRETCH }; Chip("Resample", mode == AudioFx.MODE_RESAMPLE) { mode = AudioFx.MODE_RESAMPLE } }
            if (busy) Text("Processing…", color = color, fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton({
            busy = true
            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val dir = vm.projectSamplesDir()
                val pcm = AudioFx.readWav(File(path))
                var file: File? = null; var err: String? = null
                if (dir == null) err = "There is no project folder." else if (pcm == null) err = "Could not read ${File(path).name}."
                else runCatching {
                    dir.mkdirs()
                    val base = File(path).nameWithoutExtension.replace(Regex(" edited( \\(\\d+\\))?$"), "")
                    var out = File(dir, "$base edited.wav"); var k = 2
                    while (out.exists()) out = File(dir, "$base edited ($k).wav").also { k++ }
                    AudioFx.writeWav(out, AudioFx.process(pcm!!, gainDb, semis, speed, mode)); file = out
                }.onFailure { err = "Could not process the sample: ${it.message}" }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    busy = false
                    if (err != null) vm.message = err else { vm.setPluginState(t, "sample", file!!.absolutePath); vm.samplesChanged(); vm.message = "New version loaded in ${t.pluginName.ifBlank { t.name }}: ${file!!.name}"; onDismiss() }
                }
            }
        }, enabled = changed && !busy) { Text("Use in sampler") }
    }, dismissButton = { TextButton({ if (!busy) onDismiss() }) { Text("Cancel") } })
}
