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
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text(if (group.size > 1) "${group.size} clips" else File(c.src).nameWithoutExtension, fontSize = 17.sp, maxLines = 3) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Section("Gain")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(c.gainDb, { g -> for (x in group) vm.setClipGainDb(x, g) }, Modifier.weight(1f), valueRange = -24f..12f, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color))
                Text("%+.1f dB".format(c.gainDb), color = Zk.text, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                Chip("0", false) { for (x in group) vm.setClipGainDb(x, 0f) }
            }
            Section("Pitch")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Chip("−12", false) { semis = (semis - 12f).coerceIn(-48f, 48f) }; Chip("−1", false) { semis = (semis - 1f).coerceIn(-48f, 48f) }
                Text("%+.1f st".format(semis), color = Zk.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp), maxLines = 1)
                Chip("+1", false) { semis = (semis + 1f).coerceIn(-48f, 48f) }; Chip("+12", false) { semis = (semis + 12f).coerceIn(-48f, 48f) }; Chip("0", false) { semis = 0f }
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
                    BasicTextField(bpmText, { bpmText = it.take(6) }, singleLine = true, textStyle = TextStyle(color = Zk.text, fontSize = 13.sp), cursorBrush = SolidColor(color),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                }
                Chip("Fit to ${vm.bpm.toInt()} BPM", false, enabled = srcBpm > 0f) { speed = (vm.bpm / srcBpm).coerceIn(0.25f, 4f) }
            }
            if (srcLen > 0f) Text("Length: %.2f s → %.2f s".format(srcLen, srcLen / speed), color = Zk.dim, fontSize = 11.sp)
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
        Row { TextButton({ semis = 0f; speed = 1f; for (x in group) vm.setClipGainDb(x, 0f); vm.applyClipEdits(group, 0f, 1f, mode, srcBpm); onDismiss() }) { Text("Reset", color = Zk.danger) }; TextButton(onDismiss) { Text("Cancel") } }
    })
}

@Composable private fun Section(text: String) = Text(text.uppercase(), color = Zk.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

@Composable private fun Chip(text: String, on: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Zk.accent else Zk.panel2).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(text, color = if (!enabled) Zk.dim.copy(alpha = .4f) else if (on) Color(0xFF00201B) else Zk.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
