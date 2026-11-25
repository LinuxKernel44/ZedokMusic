package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TOP = 96
private const val BOTTOM = 36
private val RowPx = 18.dp
private val KeysW = 54.dp
private const val SNAP = 0.25f
private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun isBlack(p: Int) = (p % 12) in intArrayOf(1, 3, 6, 8, 10)

/** Piano roll for an instrument track. Tap = add/remove a note, drag a note = move, drag its right edge = resize. */
@Composable
fun PianoRollView(vm: DawViewModel, track: TrackM, pxPerBeat: Float) {
    val hs = rememberScrollState(); val vs = rememberScrollState(rememberStartScroll())
    var viewportPx by remember { mutableIntStateOf(0) }
    FollowPlayhead(vm, hs, pxPerBeat, viewportPx)
    val density = LocalDensity.current
    val ppb = pxPerBeat
    val rows = TOP - BOTTOM + 1
    val beats = maxOf(32f, track.lengthBeats(vm.bpm) + 8f)
    val totalW = (beats * ppb).dp
    val tm = rememberTextMeasurer()
    var defaultLen by remember { mutableFloatStateOf(1f) }
    var preview by remember { mutableStateOf<Int?>(null) }
    // drag state
    var dragOld by remember { mutableStateOf<NoteM?>(null) }
    var dragNew by remember { mutableStateOf<NoteM?>(null) }
    var resizing by remember { mutableStateOf(false) }

    LaunchedEffect(preview) { preview?.let { p -> vm.liveNote(track, true, p); delay(280); vm.liveNote(track, false, p); preview = null } }

    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().height(34.dp).background(Zk.panel), verticalAlignment = Alignment.CenterVertically) {
            Text(track.name, color = track.color, fontSize = 13.sp, modifier = Modifier.width(KeysW).padding(start = 4.dp), maxLines = 1)
            Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportPx = it.width }.horizontalScroll(hs)) {
                Canvas(Modifier.width(totalW).fillMaxHeight().scrubRuler(ppb) { vm.seekBeat(it) }) {
                    val pw = ppb * density.density; var b = 0
                    while (b <= beats) {
                        val x = b * pw; val bar = b % 4 == 0
                        drawLine(if (bar) Zk.dim else Zk.line, Offset(x, if (bar) 10.dp.toPx() else 20.dp.toPx()), Offset(x, size.height), 1f)
                        if (bar) drawText(tm.measure("${b / 4 + 1}", TextStyle(color = Zk.dim, fontSize = 10.sp)), topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx())); b++
                    }
                    drawPlayhead(vm.playhead * pw, Zk.accent, head = true)
                }
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Len", color = Zk.dim, fontSize = 11.sp)
                for ((label, v) in listOf("¼" to 0.25f, "½" to 0.5f, "1" to 1f, "2" to 2f)) LabelButton(label, defaultLen == v) { defaultLen = v }
            }
        }
        Row(Modifier.fillMaxSize().verticalScroll(vs)) {
            // keyboard column
            Column(Modifier.width(KeysW)) {
                for (p in TOP downTo BOTTOM) {
                    Box(Modifier.fillMaxWidth().height(RowPx).background(if (isBlack(p)) Color(0xFF15171C) else Color(0xFFD9DCE2)).border(0.5.dp, Zk.line)
                        .pointerInput(p) { detectTapGestures(onPress = { vm.liveNote(track, true, p); tryAwaitRelease(); vm.liveNote(track, false, p) }) },
                        contentAlignment = Alignment.CenterEnd) {
                        if (p % 12 == 0) Text("C${p / 12 - 1}", fontSize = 9.sp, color = Color(0xFF333843), modifier = Modifier.padding(end = 4.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                Box(Modifier.width(totalW).height(RowPx * rows)) {
                    Canvas(Modifier.matchParentSize()
                        .pointerInput(ppb, track) {
                            detectTapGestures { o ->
                                val rowPx = RowPx.toPx(); val pitch = TOP - floor(o.y / rowPx).toInt()
                                val beat = o.x / density.density / ppb
                                val hit = track.notes.firstOrNull { it.pitch == pitch && beat >= it.start && beat <= it.start + it.length }
                                if (hit != null) vm.removeNote(track, hit)
                                else { val s = floor(beat / SNAP) * SNAP; vm.addNote(track, pitch, s, defaultLen); preview = pitch }
                            }
                        }
                        .pointerInput(ppb, track) {
                            detectDragGestures(
                                onDragStart = { o ->
                                    val rowPx = RowPx.toPx(); val pitch = TOP - floor(o.y / rowPx).toInt(); val beat = o.x / density.density / ppb
                                    val hit = track.notes.firstOrNull { it.pitch == pitch && beat >= it.start && beat <= it.start + it.length }
                                    dragOld = hit; dragNew = hit
                                    resizing = hit != null && beat > hit.start + hit.length * 0.7f
                                },
                                onDragEnd = { val o = dragOld; val n = dragNew; if (o != null && n != null) vm.replaceNote(track, o, n); dragOld = null; dragNew = null },
                                onDragCancel = { dragOld = null; dragNew = null },
                            ) { ch, _ ->
                                ch.consume()
                                val o = dragOld ?: return@detectDragGestures
                                val rowPx = RowPx.toPx(); val beat = ch.position.x / density.density / ppb
                                val pitch = (TOP - floor(ch.position.y / rowPx).toInt()).coerceIn(BOTTOM, TOP)
                                dragNew = if (resizing) NoteM(o.pitch, o.start, (((beat - o.start) / SNAP).roundToInt() * SNAP).coerceAtLeast(SNAP), o.velocity)
                                else NoteM(pitch, (((beat - o.length / 2f) / SNAP).roundToInt() * SNAP).coerceAtLeast(0f), o.length, o.velocity)
                            }
                        }) {
                        val rowPx = RowPx.toPx(); val pw = ppb * density.density
                        for (p in TOP downTo BOTTOM) {
                            val y = (TOP - p) * rowPx
                            drawRect(if (isBlack(p)) Color(0xFF121418) else Color(0xFF181B20), Offset(0f, y), Size(size.width, rowPx))
                            drawLine(if (p % 12 == 0) Zk.dim.copy(alpha = .5f) else Zk.line.copy(alpha = .5f), Offset(0f, y), Offset(size.width, y), 1f)
                        }
                        var b = 0f
                        while (b <= beats) { drawLine(if (b % 4 == 0f) Zk.dim.copy(alpha = .5f) else if (b % 1f == 0f) Zk.line else Zk.line.copy(alpha = .35f), Offset(b * pw, 0f), Offset(b * pw, size.height), 1f); b += if (ppb > 70) 0.25f else 1f }
                        for (n in track.notes) {
                            val shown = if (n === dragOld) dragNew ?: n else n
                            val a = if (n === dragOld) .5f else 1f
                            val y = (TOP - shown.pitch) * rowPx
                            drawRoundRect(track.color.copy(alpha = a), Offset(shown.start * pw, y + 1), Size(maxOf(4f, shown.length * pw - 1), rowPx - 2), CornerRadius(4f))
                        }
                    }
                    val x = with(density) { (vm.playhead * ppb).dp.roundToPx() }
                    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x, 0) }.width(2.dp).fillMaxHeight().background(Zk.accent))
                }
            }
        }
    }
}

@Composable private fun rememberStartScroll(): Int = with(LocalDensity.current) { (RowPx.toPx() * (TOP - 84)).toInt() }  // start around C6 -> C4 area
