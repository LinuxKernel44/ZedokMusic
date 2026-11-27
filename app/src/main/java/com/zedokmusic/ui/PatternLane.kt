package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
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

/** Height of one row of the playlist's pattern lane. */
val PatRowH = 42.dp
/** How many rows the pattern lane shows: one more than the lowest used, at least 2, at most 6. */
fun patternRows(vm: DawViewModel): Int = ((vm.playlist.maxOfOrNull { it.row } ?: 0) + 2).coerceIn(2, 6)

/** The left part of the pattern lane: the current pattern and the menu to change, create, copy, rename, resize and delete patterns. */
@Composable
fun PatternHeader(vm: DawViewModel) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<PatternM?>(null) }
    val cur = vm.currentPattern
    Box(Modifier.width(196.dp).height(PatRowH * patternRows(vm)).background(Zk.panel).border(0.5.dp, Zk.line).padding(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("PATTERNS", color = Zk.dim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Zk.panel2).clickable { menu = true }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(cur?.color ?: Zk.dim))
                Spacer(Modifier.width(6.dp))
                Text((cur?.name ?: "—") + " ▾", color = Zk.text, fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.SemiBold)
            }
            Text(if (vm.songMode) "Tap the lane to place it" else "PATTERN MODE: loops this pattern", color = if (vm.songMode) Zk.dim else Zk.warn, fontSize = 10.sp)
        }
        DropdownMenu(menu, { menu = false }) {
            for (p in vm.patterns) DropdownMenuItem(text = { Text(p.name, color = if (p === cur) Zk.accent else Zk.text) }, onClick = { menu = false; vm.selectPattern(p) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text((if (vm.songMode) "Play only this pattern (Pattern mode)" else "Play the song (Song mode)")) }, onClick = { menu = false; vm.switchSongMode(!vm.songMode) })
            DropdownMenuItem(text = { Text("New pattern") }, onClick = { menu = false; vm.newPattern() })
            if (cur != null) {
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicatePattern(cur) })
                DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; rename = cur })
                for ((label, beats) in listOf("Length: automatic" to null, "Length: 1 bar" to 4f, "Length: 2 bars" to 8f, "Length: 4 bars" to 16f, "Length: 8 bars" to 32f))
                    DropdownMenuItem(text = { Text(label + if (cur.fixedLength == beats) " ✓" else "") }, onClick = { menu = false; vm.setPatternLength(cur, beats) })
                DropdownMenuItem(text = { Text("Delete pattern", color = Zk.danger) }, onClick = { menu = false; vm.deletePattern(cur) })
            }
        }
    }
    rename?.let { p ->
        var text by remember(p) { mutableStateOf(p.name) }
        AlertDialog(onDismissRequest = { rename = null }, containerColor = Zk.panel, title = { Text("Rename pattern") }, text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renamePattern(p, text); rename = null }) { Text("OK") } }, dismissButton = { TextButton({ rename = null }) { Text("Cancel") } })
    }
}

/**
 * The pattern lane of the playlist: placements of patterns on a few rows. Tap an empty place to put the current pattern there (on the beat);
 * tap a placement to edit its pattern; drag it to move it (or to another row); drag its right end to repeat or cut the pattern; long press for more.
 * Every placement of a pattern plays the same notes: editing the pattern changes them all (Make unique gives a placement its own copy).
 */
@Composable
fun PatternTimeline(vm: DawViewModel, totalBeats: Float, ppb: Float) {
    val density = LocalDensity.current
    val rows = patternRows(vm)
    Box(Modifier.fillMaxWidth().height(PatRowH * rows).background(Zk.bg)) {
        Canvas(Modifier.matchParentSize().pointerInput(ppb, rows) {
            detectTapGestures { off ->
                val cur = vm.currentPattern ?: return@detectTapGestures
                val beat = floor(off.x / (ppb * density.density)); val row = (off.y / with(density) { PatRowH.toPx() }).toInt().coerceIn(0, rows - 1)
                vm.selectedPatternClip = vm.addPatternClip(cur, beat, row)
            }
        }) {
            val pw = ppb * density.density; val rh = PatRowH.toPx()
            var b = 0
            while (b <= totalBeats) { val gx = gridLineX(b * pw); drawLine(if (b % 4 == 0) Zk.line else Zk.line.copy(alpha = .3f), Offset(gx, 0f), Offset(gx, size.height), 1f); b++ }
            for (r in 0..rows) drawLine(Zk.line, Offset(0f, r * rh), Offset(size.width, r * rh), 1f)
        }
        for (c in vm.playlist.toList()) PatternClipBlock(vm, c, ppb)
    }
}

@Composable
private fun PatternClipBlock(vm: DawViewModel, c: PatternClipM, ppb: Float) {
    val density = LocalDensity.current
    var dx by remember(c) { mutableFloatStateOf(0f) }; var dy by remember(c) { mutableFloatStateOf(0f) }; var dw by remember(c) { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val pwPx = ppb * density.density; val rowPx = with(density) { PatRowH.toPx() }
    val selected = vm.selectedPatternClip === c
    val len = vm.clipLength(c)
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.offset { IntOffset((c.start * pwPx + dx).roundToInt(), (c.row * rowPx + dy).roundToInt()) }
        .width(with(density) { (len * pwPx + dw).coerceAtLeast(10.dp.toPx()).toDp() }).height(PatRowH).padding(1.dp)
        .clip(shape).background(c.pattern.color.copy(alpha = if (selected) .95f else .7f)).border(if (selected) 2.dp else 1.dp, if (selected) Color.White else c.pattern.color, shape)
        .pointerInput(c, ppb) { detectTapGestures(onTap = { vm.selectedPatternClip = c; vm.selectPattern(c.pattern) }, onLongPress = { vm.selectedPatternClip = c; menu = true }) }
        .pointerInput(c, ppb) {
            detectDragGestures(onDragStart = { vm.selectedPatternClip = c }, onDrag = { ch, d -> ch.consume(); dx += d.x; dy += d.y }, onDragEnd = {
                val start = Math.round((c.start * pwPx + dx) / pwPx).toFloat(); val row = ((c.row * rowPx + dy) / rowPx).roundToInt().coerceIn(0, 5)
                dx = 0f; dy = 0f; vm.movePatternClip(c, start, row)
            }, onDragCancel = { dx = 0f; dy = 0f })
        }) {
        Text(c.pattern.name, color = Color(0xFF0B0D10), fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp, end = 16.dp))
        // the right end: drag to repeat the pattern (longer) or cut it (shorter)
        Box(Modifier.align(Alignment.CenterEnd).width(16.dp).fillMaxHeight().background(Color.Black.copy(alpha = .22f))
            .pointerInput(c, ppb) {
                detectDragGestures(onDragStart = { vm.selectedPatternClip = c }, onDrag = { ch, d -> ch.consume(); dw += d.x }, onDragEnd = {
                    val newLen = (Math.round((len * pwPx + dw) / pwPx)).toFloat().coerceAtLeast(1f); dw = 0f
                    vm.resizePatternClip(c, if (newLen == vm.patternLength(c.pattern)) null else newLen)
                }, onDragCancel = { dw = 0f })
            })
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Edit pattern") }, onClick = { menu = false; vm.selectPattern(c.pattern) })
            DropdownMenuItem(text = { Text("Make unique (own copy)") }, onClick = { menu = false; vm.makeClipUnique(c)?.let { vm.selectPattern(it) } })
            if (c.length != null) DropdownMenuItem(text = { Text("Back to the pattern's length") }, onClick = { menu = false; vm.resizePatternClip(c, null) })
            for ((label, beats) in listOf("1 bar" to 4f, "2 bars" to 8f, "4 bars" to 16f, "8 bars" to 32f)) DropdownMenuItem(text = { Text("Length: $label") }, onClick = { menu = false; vm.resizePatternClip(c, beats) })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Clone after it") }, onClick = { menu = false; vm.addPatternClip(c.pattern, c.start + len, c.row, c.length) })
            DropdownMenuItem(text = { Text("Delete", color = Zk.danger) }, onClick = { menu = false; vm.removePatternClip(c); if (vm.selectedPatternClip === c) vm.selectedPatternClip = null })
        }
    }
}
