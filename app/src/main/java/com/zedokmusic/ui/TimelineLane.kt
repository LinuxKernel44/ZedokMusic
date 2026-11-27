package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import kotlin.math.floor
import kotlin.math.roundToInt

/** Height of the markers / tempo lane at the top of the Tracks view. */
val TimelineLaneH = 34.dp

@Composable
fun TimelineHeader(vm: DawViewModel) {
    Box(Modifier.width(196.dp).height(TimelineLaneH).background(Zk.panel).border(0.5.dp, Zk.line).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text("MARKERS · TEMPO", color = Zk.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Markers (named flags: tap to jump there, long press for more) and tempo changes (♩ = n from that beat on) on one lane. Tap an empty place to add one
 * there: the dialog asks which. The tempo of the first beat is the BPM button of the top bar.
 */
@Composable
fun TimelineLane(vm: DawViewModel, totalBeats: Float, ppb: Float) {
    val density = LocalDensity.current
    var addAt by remember { mutableStateOf<Float?>(null) }
    Box(Modifier.fillMaxWidth().height(TimelineLaneH).background(Zk.panel2.copy(alpha = .5f)).border(0.5.dp, Zk.line)) {
        Canvas(Modifier.matchParentSize().pointerInput(ppb) { detectTapGestures { off -> addAt = floor(off.x / (ppb * density.density)) } }) {
            val pw = ppb * density.density; var b = 0
            while (b <= totalBeats) { if (b % 4 == 0) drawLine(Zk.line, Offset(gridLineX(b * pw), size.height * 0.6f), Offset(gridLineX(b * pw), size.height), 1f); b++ }
        }
        for (m in vm.markers.toList()) FlagBlock(vm, m.beat, m.name, Zk.warn, ppb, { vm.seekBeat(m.beat) }, listOf("Rename…" to { vm.renameMarkerDialog = m }, "Delete" to { vm.removeMarker(m) }))
        for (p in vm.sigChanges.toList()) FlagBlock(vm, p.beat, "${p.num}/${p.den}", Zk.dim, ppb, { vm.seekBeat(p.beat) }, listOf("Change signature…" to { addAt = p.beat }, "Delete" to { vm.removeSigChange(p) }))
        for (p in vm.tempoChanges.toList()) FlagBlock(vm, p.beat, "♩ = " + "%.1f".format(p.bpm).removeSuffix(".0"), Zk.accent, ppb, { vm.seekBeat(p.beat) }, listOf("Change tempo…" to { addAt = p.beat }, "Delete" to { vm.removeTempoChange(p) }))
    }
    addAt?.let { beat -> AddFlagDialog(vm, beat) { addAt = null } }
    vm.renameMarkerDialog?.let { m ->
        var text by remember(m) { mutableStateOf(m.name) }
        AlertDialog(onDismissRequest = { vm.renameMarkerDialog = null }, containerColor = Zk.panel, title = { Text("Rename marker") }, text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameMarker(m, text); vm.renameMarkerDialog = null }) { Text("OK") } }, dismissButton = { TextButton({ vm.renameMarkerDialog = null }) { Text("Cancel") } })
    }
}

@Composable
private fun FlagBlock(vm: DawViewModel, beat: Float, label: String, color: Color, ppb: Float, onJump: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    val density = LocalDensity.current
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.offset { IntOffset((beat * ppb * density.density).roundToInt(), 0) }.fillMaxHeight().padding(vertical = 4.dp)) {
        Row(Modifier.clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(color.copy(alpha = .85f)).pointerInput(beat, label) { detectTapGestures(onTap = { onJump() }, onLongPress = { menu = true }) }.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color(0xFF0B0D10), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Jump here") }, onClick = { menu = false; onJump() })
            for ((l, a) in actions) DropdownMenuItem(text = { Text(l, color = if (l == "Delete") Zk.danger else Zk.text) }, onClick = { menu = false; a() })
        }
    }
}

@Composable
private fun AddFlagDialog(vm: DawViewModel, beat: Float, onDismiss: () -> Unit) {
    val existing = vm.tempoChanges.firstOrNull { it.beat == beat }
    val existingSig = vm.sigChanges.firstOrNull { it.beat == beat }
    var kind by remember { mutableIntStateOf(if (existing != null) 1 else if (existingSig != null) 2 else 0) }   // 0 marker, 1 tempo, 2 time signature
    var sigNum by remember { mutableIntStateOf(existingSig?.num ?: 3) }; var sigDen by remember { mutableIntStateOf(existingSig?.den ?: 4) }
    val tempo = kind == 1
    var name by remember { mutableStateOf("") }
    var bpm by remember { mutableFloatStateOf(existing?.bpm ?: vm.bpm) }
    val bar = (beat / 4f).toInt() + 1; val inBar = beat - (bar - 1) * 4f + 1f
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("At bar $bar" + if (inBar != 1f) ", beat ${"%.0f".format(inBar)}" else "") }, text = {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelButton("Marker", kind == 0, w = 76.dp, h = 34.dp) { kind = 0 }
                LabelButton("Tempo", kind == 1, w = 70.dp, h = 34.dp) { kind = 1 }
                LabelButton("Signature", kind == 2, w = 90.dp, h = 34.dp) { kind = 2 }
            }
            Spacer(Modifier.height(10.dp))
            if (kind == 0) OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            else if (kind == 2) {
                Text("$sigNum/$sigDen from here on (bars are " + "%.2f".format(sigNum * 4f / sigDen).trimEnd('0').trimEnd('.') + " beats long)", color = Zk.text, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (n in listOf(2, 3, 4, 5, 6, 7, 9, 12)) LabelButton("$n", sigNum == n, w = 40.dp, h = 34.dp) { sigNum = n } }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (d in listOf(4, 8, 16)) LabelButton("/$d", sigDen == d, w = 48.dp, h = 34.dp) { sigDen = d } }
                Text("Bars and bar numbers of the ruler follow it. Windows plugins still receive 4/4.", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            } else {
                Text("♩ = ${"%.1f".format(bpm).removeSuffix(".0")} BPM from here on", color = Zk.text, fontSize = 14.sp)
                Slider(bpm, { bpm = Math.round(it * 2f) / 2f }, valueRange = 30f..300f, colors = SliderDefaults.colors(thumbColor = Zk.accent, activeTrackColor = Zk.accent))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (d in listOf(-5f, -1f, 1f, 5f)) LabelButton("%+d".format(d.toInt()), false, w = 44.dp, h = 32.dp) { bpm = (bpm + d).coerceIn(30f, 300f) } }
            }
        }
    }, confirmButton = { TextButton({ when (kind) { 1 -> vm.addTempoChange(beat, bpm); 2 -> vm.addSigChange(beat, sigNum, sigDen); else -> vm.addMarker(beat, name) }; onDismiss() }) { Text("Add") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
