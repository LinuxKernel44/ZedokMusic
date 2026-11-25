package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import kotlin.math.roundToInt

private val RowHTall = 78.dp
private val RowHShort = 62.dp
private val HeaderW = 150.dp
private val RulerH = 28.dp

/** Arrangement: track headers on the left, a scrolling timeline on the right (beats on the x axis). */
@Composable
fun ArrangeView(vm: DawViewModel, pxPerBeat: Float, onZoom: (Float) -> Unit, onEdit: (TrackM) -> Unit, onImport: (TrackM) -> Unit) {
    val RowH = if (LocalShort.current) RowHShort else RowHTall
    val hs = rememberScrollState(); val vs = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    FollowPlayhead(vm, hs, pxPerBeat, viewportPx)
    val density = LocalDensity.current
    val ppb = pxPerBeat
    val totalBeats = remember(vm.tracks.size, vm.bpm) { maxOf(64f, (vm.tracks.maxOfOrNull { it.lengthBeats(vm.bpm) } ?: 0f) + 32f) }
    val totalW = (totalBeats * ppb).dp
    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().height(RulerH).background(Zk.panel)) {
            Row(Modifier.width(HeaderW).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                SquareButton(Glyph.Minus, size = 24.dp) { onZoom((ppb / 1.4f).coerceAtLeast(12f)) }
                Spacer(Modifier.width(6.dp))
                SquareButton(Glyph.Plus, size = 24.dp) { onZoom((ppb * 1.4f).coerceAtMost(160f)) }
                Spacer(Modifier.width(6.dp))
                LabelButton("Follow", vm.followPlayhead, w = 50.dp, h = 24.dp) { vm.followPlayhead = !vm.followPlayhead }
            }
            Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportPx = it.width }.horizontalScroll(hs)) { Ruler(vm, totalBeats, ppb, totalW) }
        }
        Row(Modifier.fillMaxSize().verticalScroll(vs)) {
            Column(Modifier.width(HeaderW)) { vm.tracks.forEach { TrackHeader(vm, it, onImport) } ; if (vm.tracks.isEmpty()) EmptyHint() }
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                Box(Modifier.width(totalW)) {
                    Column {
                        vm.tracks.forEach { t -> TimelineRow(vm, t, totalBeats, ppb, onEdit) }
                    }
                    // matchParentSize: fillMaxHeight measures to nothing inside a vertically scrolling parent.
                    Canvas(Modifier.matchParentSize()) { drawPlayhead(vm.playhead * ppb * density.density, Zk.accent, head = false) }
                }
            }
        }
    }
}

@Composable private fun EmptyHint() {
    val RowH = if (LocalShort.current) RowHShort else RowHTall
    Box(Modifier.width(HeaderW).height(RowH), contentAlignment = Alignment.Center) { Text("Add a track with +", color = Zk.dim, fontSize = 12.sp) }
}

@Composable
private fun Ruler(vm: DawViewModel, totalBeats: Float, ppb: Float, totalW: androidx.compose.ui.unit.Dp) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.width(totalW).fillMaxHeight().scrubRuler(ppb) { vm.seekBeat(it) }) {
        val pw = ppb * density
        var b = 0
        while (b <= totalBeats) {
            val x = b * pw; val bar = b % 4 == 0
            drawLine(if (bar) Zk.dim else Zk.line, Offset(x, if (bar) 8.dp.toPx() else 18.dp.toPx()), Offset(x, size.height), 1f)
            if (bar && pw * 4 > 40) drawText(tm.measure("${b / 4 + 1}", androidx.compose.ui.text.TextStyle(color = Zk.dim, fontSize = 10.sp)), topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx()))
            b++
        }
        if (vm.loop) drawRect(Zk.accent.copy(alpha = .35f), Offset(0f, size.height - 4.dp.toPx()), Size(vm.loopBars * 4 * pw, 4.dp.toPx()))
        drawPlayhead(vm.playhead * pw, Zk.accent, head = true)
    }
}

@Composable
private fun TrackHeader(vm: DawViewModel, t: TrackM, onImport: (TrackM) -> Unit) {
    val RowH = if (LocalShort.current) RowHShort else RowHTall
    val sel = vm.selected == t
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(HeaderW).height(RowH).background(if (sel) Zk.panel2 else Zk.panel).border(0.5.dp, Zk.line).clickable { vm.selected = t }) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(t.color))
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.kind == TrackKind.Instrument) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when (t.pluginState) { 2 -> Zk.accent; 3 -> Zk.danger; else -> Zk.warn }))
                    Spacer(Modifier.width(6.dp))
                }
                Text(t.name, color = Zk.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Box {
                    Text("⋮", color = Zk.dim, fontSize = 18.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 6.dp))
                    DropdownMenu(menu, { menu = false }) {
                        if (t.kind == TrackKind.Audio) DropdownMenuItem(text = { Text("Add sample…") }, onClick = { menu = false; onImport(t) })
                        DropdownMenuItem(text = { Text("Delete track") }, onClick = { menu = false; vm.removeTrack(t) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelButton("M", t.mute, Zk.warn) { vm.setMute(t, !t.mute) }
                LabelButton("S", t.solo, Zk.accent) { vm.setSolo(t, !t.solo) }
                Text(when (t.pluginState) { 1 -> "loading…"; 3 -> "error"; else -> "" }, color = if (t.pluginState == 3) Zk.danger else Zk.dim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun TimelineRow(vm: DawViewModel, t: TrackM, totalBeats: Float, ppb: Float, onEdit: (TrackM) -> Unit) {
    val RowH = if (LocalShort.current) RowHShort else RowHTall
    val density = LocalDensity.current
    val sel = vm.selected == t
    Box(Modifier.height(RowH).fillMaxWidth().background(if (sel) Zk.panel2.copy(alpha = .55f) else Zk.bg).border(0.5.dp, Zk.line)) {
        Canvas(Modifier.matchParentSize()) {
            val pw = ppb * density.density
            var b = 0
            while (b <= totalBeats) { drawLine(if (b % 4 == 0) Zk.line else Zk.line.copy(alpha = .35f), Offset(b * pw, 0f), Offset(b * pw, size.height), 1f); b++ }
        }
        if (t.kind == TrackKind.Instrument) {
            val len = t.lengthBeats(vm.bpm)
            Box(Modifier.padding(vertical = 8.dp).width((len * ppb).dp).fillMaxHeight().clip(RoundedCornerShape(8.dp))
                .background(t.color.copy(alpha = .28f)).border(1.dp, t.color.copy(alpha = .8f), RoundedCornerShape(8.dp))
                .clickable { vm.selected = t; onEdit(t) }) {
                Canvas(Modifier.matchParentSize().padding(6.dp)) {
                    if (t.notes.isEmpty()) return@Canvas
                    val lo = t.notes.minOf { it.pitch }; val hi = t.notes.maxOf { it.pitch }; val span = maxOf(1, hi - lo)
                    val ph = size.height / (span + 1); val pw = ppb * density.density
                    for (n in t.notes) drawRoundRect(t.color, Offset(n.start * pw, (hi - n.pitch) * ph), Size(maxOf(2f, n.length * pw), maxOf(2f, ph * .8f)), CornerRadius(2f))
                }
                if (t.notes.isEmpty()) Text("Tap to write notes", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        for (c in t.clips) ClipBlock(vm, t, c, ppb)
    }
}

@Composable
private fun ClipBlock(vm: DawViewModel, t: TrackM, c: ClipM, ppb: Float) {
    val density = LocalDensity.current
    var drag by remember(c) { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val lenBeats = c.lengthFrames / 48000f * vm.bpm / 60f
    val x = with(density) { ((c.start * ppb) + drag).dp.roundToPx() }
    Box(Modifier.offset { IntOffset(x, 0) }.padding(vertical = 8.dp).width((lenBeats * ppb).dp.coerceAtLeast(12.dp)).fillMaxHeight()
        .clip(RoundedCornerShape(8.dp)).background(t.color.copy(alpha = .35f)).border(1.dp, t.color, RoundedCornerShape(8.dp))
        .pointerInput(c, ppb, vm.bpm) {
            detectDragGestures(onDragEnd = {
                val b = (c.start + drag / density.density / ppb); drag = 0f
                vm.moveClip(c, (b * 4).roundToInt() / 4f)  // snap to 1/16 note
            }, onDragCancel = { drag = 0f }) { ch, d -> ch.consume(); drag += d.x }
        }.pointerInput(c) { detectTapGestures(onLongPress = { menu = true }) }) {
        Text(c.name, color = Zk.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Remove clip") }, onClick = { menu = false; vm.removeClip(t, c) }) }
    }
}
