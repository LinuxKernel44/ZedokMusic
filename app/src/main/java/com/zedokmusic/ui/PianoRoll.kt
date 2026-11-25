package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.hypot
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
private val SNAP get() = com.zedokmusic.settings.AppSettings.snapBeats
private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun beatsLabel(b: Float) = "%.3f".format(b).trimEnd('0').trimEnd('.')
private fun isBlack(p: Int) = (p % 12) in intArrayOf(1, 3, 6, 8, 10)

/** Piano roll for an instrument track. Tap = add a note / select one; drag = move; drag a selected note's end handles (or the right part of any note) = stretch or shrink; −/+ in the header change the length by one grid step. */
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
    var defaultLen by remember { mutableFloatStateOf(com.zedokmusic.settings.AppSettings.noteLengthBeats) }
    var preview by remember { mutableStateOf<Int?>(null) }
    // drag state
    var dragOld by remember { mutableStateOf<NoteM?>(null) }
    var dragNew by remember { mutableStateOf<NoteM?>(null) }
    var sel by remember(track.id) { mutableStateOf<NoteM?>(null) }
    val selNote = sel?.takeIf { track.notes.contains(it) }

    LaunchedEffect(preview) { preview?.let { p -> vm.liveNote(track, true, p); delay(280); vm.liveNote(track, false, p); preview = null } }

    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(Zk.panel), verticalAlignment = Alignment.CenterVertically) {
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
                if (selNote != null) {
                    // The selected note: one-tap length changes (by the grid step) and delete.
                    fun resized(l: Float) { val n = NoteM(selNote.pitch, selNote.start, l.coerceAtLeast(SNAP), selNote.velocity); vm.replaceNote(track, selNote, n); sel = n }
                    LabelButton("−", false, w = 36.dp, h = 32.dp) { resized(selNote.length - SNAP) }
                    Text(beatsLabel(selNote.length), color = Zk.text, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 34.dp))
                    LabelButton("+", false, w = 36.dp, h = 32.dp) { resized(selNote.length + SNAP) }
                    LabelButton("Del", false, Zk.danger, w = 42.dp, h = 32.dp) { vm.removeNote(track, selNote); sel = null }
                } else {
                    Text("Len", color = Zk.dim, fontSize = 11.sp)
                    for ((label, v) in listOf("¼" to 0.25f, "½" to 0.5f, "1" to 1f, "2" to 2f)) LabelButton(label, defaultLen == v) { defaultLen = v }
                }
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
                            val pw = ppb * density.density; val rowPx = RowPx.toPx()
                            val slop = viewConfiguration.touchSlop
                            val handleR = 28.dp.toPx(); val tol = 8.dp.toPx()
                            var lastTapAt = 0L; var lastTapNote: NoteM? = null
                            // One finger: tap an empty cell = add a note; tap a note = select it (tap it twice quickly = delete);
                            // drag a note = move; drag its end handles (or its right part) = stretch / shrink; dragging empty space scrolls.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val x0 = down.position.x; val y0 = down.position.y
                                val pitch0 = (TOP - floor(y0 / rowPx).toInt()).coerceIn(BOTTOM, TOP)
                                var mode = 0  // 0 empty, 1 move, 2 resize the end, 3 resize the start
                                var target: NoteM? = null
                                val cur = sel?.takeIf { track.notes.contains(it) }
                                if (cur != null) {  // the handles of the selected note win: easy to grab, even on tiny notes
                                    val cy = (TOP - cur.pitch) * rowPx + rowPx / 2
                                    if (hypot(x0 - (cur.start + cur.length) * pw, y0 - cy) < handleR) { mode = 2; target = cur }
                                    else if (hypot(x0 - cur.start * pw, y0 - cy) < handleR) { mode = 3; target = cur }
                                }
                                if (target == null) {
                                    val hit = track.notes.lastOrNull { it.pitch == pitch0 && x0 >= it.start * pw - tol && x0 <= (it.start + it.length) * pw + tol }
                                    if (hit != null) {
                                        target = hit
                                        val zone = minOf(hit.length * pw * 0.4f, 22.dp.toPx())
                                        mode = if (x0 > (hit.start + hit.length) * pw - zone) 2 else 1
                                    }
                                }
                                val handleGrab = cur != null && target === cur && mode != 1
                                // tap or drag?
                                while (true) {
                                    val ev = awaitPointerEvent(if (mode == 0) PointerEventPass.Final else PointerEventPass.Main)
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                    if (mode == 0 && c.isConsumed) return@awaitEachGesture  // the timeline is being scrolled
                                    if (!c.pressed) {
                                        val now = System.currentTimeMillis()
                                        if (target == null) {
                                            val st = floor(x0 / pw / SNAP) * SNAP
                                            vm.addNote(track, pitch0, st, defaultLen); sel = track.notes.lastOrNull(); preview = pitch0
                                        } else if (!handleGrab && lastTapNote === target && now - lastTapAt < 320) {
                                            vm.removeNote(track, target); sel = null; lastTapNote = null
                                        } else { sel = target; preview = target.pitch; lastTapNote = target; lastTapAt = now }
                                        return@awaitEachGesture
                                    }
                                    if (mode != 0 && (c.position - down.position).getDistance() > slop) { c.consume(); break }
                                }
                                val o = target ?: return@awaitEachGesture
                                val grab = x0 / pw - o.start; val endFixed = o.start + o.length
                                sel = o; dragOld = o; dragNew = o
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    val beat = c.position.x / pw
                                    dragNew = when (mode) {
                                        2 -> NoteM(o.pitch, o.start, ((beat / SNAP).roundToInt() * SNAP).coerceAtLeast(o.start + SNAP) - o.start, o.velocity)
                                        3 -> { val st = ((beat / SNAP).roundToInt() * SNAP).coerceIn(0f, endFixed - SNAP); NoteM(o.pitch, st, endFixed - st, o.velocity) }
                                        else -> NoteM((TOP - floor(c.position.y / rowPx).toInt()).coerceIn(BOTTOM, TOP), (((beat - grab) / SNAP).roundToInt() * SNAP).coerceAtLeast(0f), o.length, o.velocity)
                                    }
                                }
                                val n = dragNew; dragOld = null; dragNew = null
                                if (n != null && (n.pitch != o.pitch || n.start != o.start || n.length != o.length)) {
                                    vm.replaceNote(track, o, n); sel = n; if (n.pitch != o.pitch) preview = n.pitch
                                }
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
                            val y = (TOP - shown.pitch) * rowPx
                            val w = maxOf(4f, shown.length * pw - 1)
                            drawRoundRect(track.color, Offset(shown.start * pw, y + 1), Size(w, rowPx - 2), CornerRadius(4f))
                            if (n === sel) {
                                drawRoundRect(Color.White, Offset(shown.start * pw, y + 1), Size(w, rowPx - 2), CornerRadius(4f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                val cy = y + rowPx / 2
                                for (hx in floatArrayOf(shown.start * pw, shown.start * pw + w)) { drawCircle(Color.White, 8.dp.toPx(), Offset(hx, cy)); drawCircle(track.color, 4.dp.toPx(), Offset(hx, cy)) }
                                if (n === dragOld) drawText(tm.measure(beatsLabel(shown.length), TextStyle(color = Color.White, fontSize = 12.sp)), topLeft = Offset(shown.start * pw, maxOf(0f, y - 16.dp.toPx())))
                            }
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
