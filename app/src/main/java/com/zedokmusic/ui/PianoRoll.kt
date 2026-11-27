package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.hypot
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.zedokmusic.model.Theory
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.roundToInt

private const val TOP = 96
private const val BOTTOM = 36
private val RowPx = 18.dp
private val KeysW = 54.dp
/** The snap step of the piano roll: fixed, or (Auto) the finest step the current zoom can show. Reads the zoom each time, so finer steps appear when zooming in and notes already placed on a finer step keep their exact position. */
private val SNAP get() = com.zedokmusic.settings.AppSettings.snapFor(DawViewModel.current?.ppb ?: 44f)
private val NoteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private fun beatsLabel(b: Float) = "%.3f".format(b).trimEnd('0').trimEnd('.')
private fun isBlack(p: Int) = (p % 12) in intArrayOf(1, 3, 6, 8, 10)

/**
 * Piano roll for an instrument track.
 * Draw mode: tap an empty cell = add a note; tap a note = select it (tap it twice quickly = delete); long press in empty space, then drag = rectangle selection.
 * Select mode (the Select button): drag in empty space = rectangle; tap a note = add it to / remove it from the selection; tap empty space = deselect.
 * Dragging a selected note moves the whole selection; dragging its right end stretches all selected notes; a note that is not selected is selected alone first.
 * With a selection: a floating bar (Clone, Copy, Cut, Delete) over it and a second row under the header (transpose, velocity, quantize, length, all, invert).
 * With nothing selected and something copied: a Paste button at the playhead.
 */
@Composable
fun PianoRollView(vm: DawViewModel, track: TrackM, pxPerBeat: Float) {
    val hs = rememberScrollState(vm.rollX); val vs = rememberScrollState(if (vm.rollY >= 0) vm.rollY else rememberStartScroll())
    LaunchedEffect(Unit) { snapshotFlow { hs.value to vs.value }.collect { (x, y) -> vm.rollScrolled(x, y) } }
    var seenView by remember { mutableIntStateOf(vm.viewRevision) }
    LaunchedEffect(vm.viewRevision) { if (vm.viewRevision != seenView) { seenView = vm.viewRevision; hs.scrollTo(vm.rollX); if (vm.rollY >= 0) vs.scrollTo(vm.rollY) } }
    var viewportPx by remember { mutableIntStateOf(0) }
    FollowPlayhead(vm, hs, pxPerBeat, viewportPx)
    val density = LocalDensity.current
    val ppb = pxPerBeat
    val RowPx = 18.dp * vm.rollRowScale   // vertical zoom (shadows the base row height above)
    val rows = TOP - BOTTOM + 1
    val beats = maxOf(32f, track.lengthBeats(vm.bpm) + 8f)
    val totalW = (beats * ppb).dp
    val tm = rememberTextMeasurer()
    var defaultLen by remember { mutableFloatStateOf(com.zedokmusic.settings.AppSettings.noteLengthBeats) }
    var preview by remember { mutableStateOf<Int?>(null) }
    // selection and drag state
    val selection = remember(track.id) { mutableStateListOf<NoteM>() }
    var selectMode by remember(track.id) { mutableStateOf(false) }
    val scaleRoot = vm.scaleRoot; val scaleType = vm.scaleType
    var scaleDialog by remember { mutableStateOf(false) }
    val selNotes = selection.filter { track.notes.contains(it) }
    val selSet = selNotes.toHashSet()
    var dragPrev by remember { mutableStateOf<Map<NoteM, NoteM>?>(null) }   // notes being dragged -> where they would land
    var rectSel by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }   // the selection rectangle (pixels of the grid)
    // Rectangle selection with scrolling: the rectangle is anchored in the grid, the finger is tracked relative to the visible area, so the grid can scroll under a held finger.
    var rectAnchor by remember { mutableStateOf<Offset?>(null) }
    var rectFinger by remember { mutableStateOf<Offset?>(null) }
    var viewH by remember { mutableIntStateOf(0) }
    fun updateRect() {
        val a = rectAnchor ?: return; val fgr = rectFinger ?: return
        val p = Offset(fgr.x + hs.value, fgr.y + vs.value)
        val rc = androidx.compose.ui.geometry.Rect(minOf(a.x, p.x), minOf(a.y, p.y), maxOf(a.x, p.x), maxOf(a.y, p.y))
        rectSel = rc
        val pwR = with(density) { ppb.dp.toPx() }; val rowR = with(density) { RowPx.toPx() }
        val hits = track.notes.filter { n ->
            val nx0 = n.start * pwR; val nx1 = (n.start + n.length) * pwR; val ny0 = (TOP - n.pitch) * rowR
            nx1 >= rc.left && nx0 <= rc.right && ny0 + rowR >= rc.top && ny0 <= rc.bottom
        }
        selection.clear(); selection.addAll(hits)
    }
    LaunchedEffect(rectAnchor != null) {
        while (rectAnchor != null) {
            delay(16)
            val fgr = rectFinger ?: continue
            val edge = with(density) { 44.dp.toPx() }; val step = with(density) { 14.dp.toPx() }
            val dx = if (fgr.x > viewportPx - edge) step else if (fgr.x < edge) -step else 0f
            val dy = if (fgr.y > viewH - edge) step else if (fgr.y < edge) -step else 0f
            if (dx != 0f) hs.scrollBy(dx); if (dy != 0f) vs.scrollBy(dy)
            if (dx != 0f || dy != 0f) updateRect()
        }
    }
    // The floating bar is only a shortcut: it shows for 2.5 s after a rectangle / Select tap / Clone / Paste and goes away at the next touch of the grid, so it never sits on the cells you want to write in. The same actions are in the row at the bottom.
    var barRequest by remember { mutableIntStateOf(0) }
    var barVisible by remember { mutableStateOf(false) }
    LaunchedEffect(barRequest) { if (barRequest > 0) { barVisible = true; delay(2500); barVisible = false } }

    // The note-off must always follow the note-on, even when another tap restarts this effect or the view is left during the 280 ms.
    // While the song plays, editing notes is silent (the preview would be heard on top of the playback).
    LaunchedEffect(preview) { preview?.let { p -> if (vm.playing) { preview = null; return@let }; vm.liveNote(track, true, p); try { delay(280) } finally { vm.liveNote(track, false, p) }; preview = null } }

    fun mapSel(f: (NoteM) -> NoteM) {
        val sel = selNotes; if (sel.isEmpty()) return
        val ch = sel.associateWith(f); vm.replaceNotes(track, ch); selection.clear(); selection.addAll(sel.map { ch[it]!! })
    }

    if (scaleDialog) androidx.compose.material3.AlertDialog(onDismissRequest = { scaleDialog = false }, containerColor = Zk.panel, title = { Text("Project scale") }, text = {
        // The whole content scrolls as one piece: on a landscape phone the dialog is short, and a list with its own height used to be squeezed to one cut-off line.
        Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Text("Rows in the scale are lighter, the others darker, the root is tinted. Saved with the project.", color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                for (r in 0 until 12) LabelButton(Theory.NAMES[r], r == scaleRoot, Zk.accent, w = 40.dp, h = 32.dp) { vm.setScale(r, scaleType) }
            }
            Spacer(Modifier.height(8.dp))
            Theory.SCALES.forEachIndexed { i, sc ->
                Text(sc.label, color = if (i == scaleType) Zk.accent else Zk.text, fontSize = 14.sp, fontWeight = if (i == scaleType) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clickable { vm.setScale(scaleRoot, i) }.padding(vertical = 9.dp))
            }
        }
    }, confirmButton = { androidx.compose.material3.TextButton({ scaleDialog = false }) { Text("Close") } })

    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(Zk.panel), verticalAlignment = Alignment.CenterVertically) {
            Text(track.name, color = track.color, fontSize = 13.sp, modifier = Modifier.width(KeysW).padding(start = 4.dp), maxLines = 1)
            // One box for the ruler and the buttons: the ruler spans the same width as the grid below, so the two scroll over exactly the same range and never drift apart.
            Box(Modifier.weight(1f).fillMaxHeight()) {
            Box(Modifier.fillMaxSize().onSizeChanged { viewportPx = it.width }.horizontalScroll(hs)) {
                Canvas(Modifier.width(totalW).fillMaxHeight().scrubRuler(ppb) { vm.seekBeat(it) }) {
                    val pw = ppb * density.density; var b = 0
                    while (b <= beats) {
                        val x = gridLineX(b * pw); val bar = b % 4 == 0
                        drawLine(if (bar) Zk.dim else Zk.line, Offset(x, if (bar) 10.dp.toPx() else 20.dp.toPx()), Offset(x, size.height), 1f)
                        if (bar) drawText(tm.measure("${b / 4 + 1}", TextStyle(color = Zk.dim, fontSize = 10.sp)), topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx())); b++
                    }
                    drawPlayhead(vm.playhead * pw, Zk.accent, head = true)
                }
            }
            Row(Modifier.align(Alignment.CenterEnd).background(Zk.panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val canUndo = vm.canUndoNotes(track); val canRedo = vm.canRedoNotes(track)
                LabelButton("↶", false, w = 32.dp, h = 32.dp, enabled = canUndo) { vm.undoNotes(track) }
                LabelButton("↷", false, w = 32.dp, h = 32.dp, enabled = canRedo) { vm.redoNotes(track) }
                LabelButton(Theory.label(scaleRoot, scaleType), scaleType > 0, Zk.accent, w = 84.dp, h = 32.dp) { scaleDialog = true }
                LabelButton("Select", selectMode, w = 56.dp, h = 32.dp) { selectMode = !selectMode }
                if (selNotes.isEmpty()) {
                    Text("Len", color = Zk.dim, fontSize = 11.sp)
                    for ((label, v) in listOf("¼" to 0.25f, "½" to 0.5f, "1" to 1f, "2" to 2f)) LabelButton(label, defaultLen == v) { defaultLen = v }
                }
            }
            }
        }
        val showBar = selNotes.isNotEmpty() || vm.noteClip.isNotEmpty()
        var barWas by remember { mutableStateOf(showBar) }
        val barPx = with(density) { 40.dp.toPx() }
        LaunchedEffect(showBar) { if (showBar != barWas) { barWas = showBar; vs.scrollBy(if (showBar) barPx else -barPx) } }
        if (showBar) {
                // The actions of the selection (and Paste) sit under the scale bar, only while there is something to act on. The grid is scrolled by the bar's height when it comes or goes, so the cells stay under the finger.
                Row(Modifier.fillMaxWidth().height(40.dp).background(Zk.panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (selNotes.isEmpty()) {
                        LabelButton("Paste ${vm.noteClip.size}", false, w = 78.dp, h = 30.dp) { val at = (vm.playhead / SNAP).roundToInt() * SNAP; val pasted = vm.pasteNotes(track, at); selection.clear(); selection.addAll(pasted) }
                        Text("at the playhead", color = Zk.dim, fontSize = 11.sp)
                        LabelButton("All", false, w = 38.dp, h = 30.dp) { selection.clear(); selection.addAll(track.notes) }
                    } else {
                    Text("${selNotes.size} note${if (selNotes.size > 1) "s" else ""}", color = track.color, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.widthIn(min = 52.dp))
                    LabelButton("Clone", false, w = 54.dp, h = 30.dp) { val copies = vm.cloneNotes(track, selNotes); selection.clear(); selection.addAll(copies) }
                    LabelButton("Copy", false, w = 48.dp, h = 30.dp) { vm.copyNotes(selNotes); vm.message = "${selNotes.size} note${if (selNotes.size > 1) "s" else ""} copied." }
                    LabelButton("Cut", false, w = 42.dp, h = 30.dp) { vm.cutNotes(track, selNotes); selection.clear() }
                    LabelButton("Del", false, Zk.danger, w = 42.dp, h = 30.dp) { vm.removeNotes(track, selNotes); selection.clear() }
                    Spacer(Modifier.width(4.dp))
                    fun transpose(d: Int) { if (selNotes.all { it.pitch + d in BOTTOM..TOP }) { mapSel { NoteM(it.pitch + d, it.start, it.length, it.velocity) }; preview = (selNotes.firstOrNull()?.pitch ?: 60) + d } }
                    for ((l, d) in listOf("−12" to -12, "−1" to -1, "+1" to 1, "+12" to 12)) LabelButton(l, false, w = 40.dp, h = 30.dp, enabled = selNotes.all { it.pitch + d in BOTTOM..TOP }) { transpose(d) }   // greyed when a selected note would leave the keyboard range
                    Spacer(Modifier.width(4.dp))
                    val avgVel = selNotes.map { it.velocity }.average().toInt()
                    var velDrag by remember(selNotes.size, avgVel) { mutableFloatStateOf(avgVel.toFloat()) }
                    Text("Vel ${velDrag.toInt()}", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.widthIn(min = 44.dp))
                    androidx.compose.material3.Slider(velDrag, { velDrag = it }, Modifier.width(110.dp), valueRange = 1f..127f, onValueChangeFinished = { val v = velDrag.toInt().coerceIn(1, 127); mapSel { NoteM(it.pitch, it.start, it.length, v) } },
                        colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = track.color, activeTrackColor = track.color))
                    Spacer(Modifier.width(4.dp))
                    LabelButton("Quant", false, w = 52.dp, h = 30.dp) { mapSel { NoteM(it.pitch, ((it.start / SNAP).roundToInt() * SNAP).coerceAtLeast(0f), it.length, it.velocity) } }
                    LabelButton("−", false, w = 32.dp, h = 30.dp, enabled = selNotes.any { it.length > SNAP + 1e-4f }) { mapSel { NoteM(it.pitch, it.start, (it.length - SNAP).coerceAtLeast(SNAP), it.velocity) } }
                    Text(if (selNotes.size == 1) beatsLabel(selNotes[0].length) else "len", color = Zk.text, fontSize = 12.sp, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 34.dp))
                    LabelButton("+", false, w = 32.dp, h = 30.dp) { mapSel { NoteM(it.pitch, it.start, it.length + SNAP, it.velocity) } }
                    Spacer(Modifier.width(4.dp))
                    LabelButton("All", false, w = 38.dp, h = 30.dp) { selection.clear(); selection.addAll(track.notes) }
                    LabelButton("Inv", false, w = 38.dp, h = 30.dp) { val keep = track.notes.filter { it !in selSet }; selection.clear(); selection.addAll(keep) }
                    LabelButton("✕", false, w = 32.dp, h = 30.dp) { selection.clear() }
                    }
                }
        }
        // The bottom padding is part of the scrolled content: the lowest notes can be scrolled up away from the screen edge, where the system's gesture bar is.
        // Pinch: apart / together horizontally = time zoom, vertically = taller / shorter note rows. The point under the fingers stays under them.
        var anchor by remember { mutableStateOf<Triple<Float, Float, Pair<Float, Float>>?>(null) }   // beat, row index, (finger x, finger y)
        LaunchedEffect(ppb, vm.rollRowScale) {
            anchor?.let { (beat, row, c) ->
                hs.scrollTo((beat * ppb * density.density - c.first).toInt().coerceAtLeast(0))
                vs.scrollTo((row * with(density) { RowPx.toPx() } - c.second).toInt().coerceAtLeast(0)); anchor = null
            }
        }
        val ppbNow = rememberUpdatedState(ppb); val keysPx = with(density) { KeysW.toPx() }
        Box(Modifier.fillMaxSize().onSizeChanged { viewH = it.height }) {
        Row(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                var prevX = 0f; var prevY = 0f; var pinching = false
                var cur = ppbNow.value; var curRow = vm.rollRowScale; var beat0 = 0f; var row0 = 0f
                do {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val pts = ev.changes.filter { it.pressed }
                    if (pts.size >= 2) {
                        val a = pts[0].position; val b = pts[1].position
                        val sx = kotlin.math.abs(a.x - b.x); val sy = kotlin.math.abs(a.y - b.y); val cx = (a.x + b.x) / 2f - keysPx; val cy = (a.y + b.y) / 2f
                        if (!pinching) { beat0 = (hs.value + cx) / (cur * density.density); row0 = (vs.value + cy) / (18.dp.toPx() * curRow) }
                        if (pinching) {
                            if (sy > sx * 1.5f && prevY > 40f && sy > 40f) { val nr = (curRow * sy / prevY).coerceIn(0.6f, 2.6f); if (nr != curRow) { curRow = nr; anchor = Triple(beat0, row0, cx to cy); vm.rollRowScale = nr } }
                            else if (prevX > 40f && sx > 40f) { val nw = (cur * sx / prevX).coerceIn(DawViewModel.ZOOM_MIN, DawViewModel.ZOOM_MAX); if (nw != cur) { cur = nw; anchor = Triple(beat0, row0, cx to cy); vm.ppb = nw } }
                            ev.changes.forEach { it.consume() }
                        }
                        prevX = sx; prevY = sy; pinching = true
                    } else pinching = false
                } while (ev.changes.any { it.pressed })
            }
        }.verticalScroll(vs).padding(bottom = ScrollBottomPad)) {
            // keyboard column
            Column(Modifier.width(KeysW)) {
                val lit = vm.activePitches(track)
                for (p in TOP downTo BOTTOM) {
                    Box(Modifier.fillMaxWidth().height(RowPx).background(if (p in lit) (if (isBlack(p)) track.color else track.color.copy(alpha = .75f)) else if (isBlack(p)) Color(0xFF15171C) else Color(0xFFD9DCE2)).border(0.5.dp, Zk.line)
                        .pointerInput(p) { detectTapGestures(onPress = { vm.liveNote(track, true, p); tryAwaitRelease(); vm.liveNote(track, false, p) }) },
                        contentAlignment = Alignment.CenterEnd) {
                        Text(Theory.name(p), fontSize = 9.sp, color = if (p in lit) Color.Black else if (isBlack(p)) Color(0xFFC9CDD6) else Color(0xFF333843), maxLines = 1, softWrap = false, modifier = Modifier.padding(end = 4.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                Box(Modifier.width(totalW).height(RowPx * rows)) {
                    Canvas(Modifier.matchParentSize()
                        .pointerInput(ppb, track, vm.rollRowScale) {
                            val pw = ppb * density.density; val rowPx = RowPx.toPx()
                            val slop = viewConfiguration.touchSlop
                            val handleR = 28.dp.toPx(); val tol = 8.dp.toPx()
                            val lpTime = viewConfiguration.longPressTimeoutMillis
                            var lastTapAt = 0L; var lastTapNote: NoteM? = null; var lastEmptyTapAt = 0L
                            fun hitRect(r: androidx.compose.ui.geometry.Rect) = track.notes.filter { n ->
                                val nx0 = n.start * pw; val nx1 = (n.start + n.length) * pw; val ny0 = (TOP - n.pitch) * rowPx
                                nx1 >= r.left && nx0 <= r.right && ny0 + rowPx >= r.top && ny0 <= r.bottom
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                barVisible = false
                                val x0 = down.position.x; val y0 = down.position.y
                                val pitch0 = (TOP - floor(y0 / rowPx).toInt()).coerceIn(BOTTOM, TOP)
                                var mode = 0  // 0 empty, 1 move, 2 resize the end, 3 resize the start (one note)
                                var target: NoteM? = null
                                val selNow = selection.filter { track.notes.contains(it) }
                                var cur: NoteM? = null
                                run {  // the handles of the selected notes win: easy to grab, even on tiny notes; the nearest one is taken
                                    var best = handleR
                                    for (nn in selNow) {
                                        val cy = (TOP - nn.pitch) * rowPx + rowPx / 2
                                        val de = hypot(x0 - (nn.start + nn.length) * pw, y0 - cy); val ds = hypot(x0 - nn.start * pw, y0 - cy)
                                        if (de < best) { best = de; mode = 2; target = nn; cur = nn }
                                        if (ds < best) { best = ds; mode = 3; target = nn; cur = nn }
                                    }
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
                                // tap, drag, or long press (in empty space)
                                val t0 = System.currentTimeMillis(); var rect = false; var lastPos = down.position
                                while (true) {
                                    val waitLong = mode == 0 && !selectMode
                                    val ev = if (waitLong) withTimeoutOrNull(maxOf(1L, lpTime - (System.currentTimeMillis() - t0))) { awaitPointerEvent(PointerEventPass.Final) }
                                             else awaitPointerEvent(PointerEventPass.Main)
                                    if (ev == null) { if ((lastPos - down.position).getDistance() > slop) return@awaitEachGesture; rect = true; break }
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                    if (waitLong && c.isConsumed) return@awaitEachGesture  // the timeline is being scrolled
                                    lastPos = c.position
                                    if (!c.pressed) {
                                        val now = System.currentTimeMillis()
                                        if (target == null) {
                                            if (selectMode) selection.clear()
                                            else if (selNow.size > 1) {   // with several notes selected: one tap elsewhere does nothing, a second one deselects them all
                                                if (now - lastEmptyTapAt < 450) { selection.clear(); lastEmptyTapAt = 0L } else lastEmptyTapAt = now
                                            } else { val st = floor(x0 / pw / SNAP) * SNAP; vm.addNote(track, pitch0, st, defaultLen); selection.clear(); track.notes.lastOrNull()?.let { selection.add(it) }; preview = pitch0 }
                                        } else if (!handleGrab && lastTapNote === target && now - lastTapAt < 320) {
                                            vm.removeNote(track, target); selection.remove(target); lastTapNote = null
                                        } else {
                                            if (selectMode) { if (!selection.remove(target)) selection.add(target); barRequest++ }   // selecting never plays the note
                                            else { selection.clear(); selection.add(target) }
                                            lastTapNote = target; lastTapAt = now
                                        }
                                        return@awaitEachGesture
                                    }
                                    if ((c.position - down.position).getDistance() > slop) {
                                        if (mode != 0) { c.consume(); break }
                                        if (selectMode) { c.consume(); rect = true; break }
                                    }
                                }
                                if (rect) {   // rectangle selection: every note it touches (replaces the selection); near an edge the grid scrolls
                                    rectAnchor = Offset(x0, y0); rectFinger = Offset(x0 - hs.value, y0 - vs.value); updateRect()
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!c.pressed) break
                                        c.consume()
                                        rectFinger = Offset(c.position.x - hs.value, c.position.y - vs.value); updateRect()
                                    }
                                    rectAnchor = null; rectFinger = null; rectSel = null
                                    if (selection.isNotEmpty()) barRequest++
                                    return@awaitEachGesture
                                }
                                val o = target ?: return@awaitEachGesture
                                val group: List<NoteM> = if (o in selNow) selNow else listOf(o)
                                if (o !in selNow) { selection.clear(); selection.add(o) }
                                val grab = x0 / pw - o.start; val endFixed = o.start + o.length
                                val minStart = group.minOf { it.start }; val minPitch = group.minOf { it.pitch }; val maxPitch = group.maxOf { it.pitch }
                                dragPrev = group.associateWith { it }
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    c.consume()
                                    val beat = c.position.x / pw
                                    dragPrev = when (mode) {
                                        2 -> {
                                            val newEnd = ((beat / SNAP).roundToInt() * SNAP).coerceAtLeast(o.start + SNAP); val dLen = newEnd - (o.start + o.length)
                                            group.associateWith { NoteM(it.pitch, it.start, (it.length + dLen).coerceAtLeast(SNAP), it.velocity) }
                                        }
                                        3 -> {   // the start handle: every selected note moves its start by the same amount (none goes below 0 or under the smallest length)
                                            val want = ((beat / SNAP).roundToInt() * SNAP) - o.start
                                            val dS = want.coerceIn(-minStart, group.minOf { it.length } - SNAP)
                                            group.associateWith { NoteM(it.pitch, it.start + dS, it.length - dS, it.velocity) }
                                        }
                                        else -> {
                                            val newStart = (((beat - grab) / SNAP).roundToInt() * SNAP)
                                            val dStart = (newStart - o.start).coerceAtLeast(-minStart)
                                            val pitchNow = (TOP - floor(c.position.y / rowPx).toInt())
                                            val dPitch = (pitchNow - o.pitch).coerceIn(BOTTOM - minPitch, TOP - maxPitch)
                                            group.associateWith { NoteM(it.pitch + dPitch, it.start + dStart, it.length, it.velocity) }
                                        }
                                    }
                                }
                                val result = dragPrev; dragPrev = null
                                if (result != null) {
                                    val changed = result.filter { (a, b) -> a.pitch != b.pitch || a.start != b.start || a.length != b.length }
                                    if (changed.isNotEmpty()) {
                                        vm.replaceNotes(track, changed)
                                        selection.clear(); selection.addAll(group.map { result[it] ?: it })
                                        val np = result[o]?.pitch; if (np != null && np != o.pitch) preview = np
                                    }
                                }
                            }
                        }) {
                        val rowPx = RowPx.toPx(); val pw = ppb * density.density
                        for (p in TOP downTo BOTTOM) {
                            val y = (TOP - p) * rowPx
                            val rowCol = if (scaleType > 0) (if (Theory.isRoot(p, scaleRoot)) Color(0xFF26342F) else if (Theory.inScale(p, scaleRoot, scaleType)) Color(0xFF20252C) else Color(0xFF0E1013))
                                         else if (isBlack(p)) Color(0xFF121418) else Color(0xFF181B20)
                            drawRect(rowCol, Offset(0f, y), Size(size.width, rowPx))
                            drawLine(if (p % 12 == 0) Zk.dim.copy(alpha = .5f) else Zk.line.copy(alpha = .5f), Offset(0f, y), Offset(size.width, y), 1f)
                        }
                        // Grid lines follow the snap setting (as fine as is still readable), so the lines you see are the positions notes snap to.
                        var step = SNAP; while (step * pw < 9.dp.toPx() && step < 1f) step *= 2f
                        if (step >= 1f) step = 1f
                        var k = 0
                        while (k * step <= beats) {
                            val b = k * step; val gx = gridLineX(b * pw)
                            // the coarser the position (bar, beat, 1/8, 1/16...), the stronger the line: finer lines fade in as you zoom
                            val col = if (b % 4f == 0f) Zk.dim.copy(alpha = .5f) else if (b % 1f == 0f) Zk.line else if (b % 0.5f == 0f) Zk.line.copy(alpha = .4f) else if (b % 0.25f == 0f) Zk.line.copy(alpha = .3f) else if (b % 0.125f == 0f) Zk.line.copy(alpha = .22f) else Zk.line.copy(alpha = .15f)
                            drawLine(col, Offset(gx, 0f), Offset(gx, size.height), 1f); k++
                        }
                        val prev = dragPrev
                        for (n in track.notes) {
                            val shown = prev?.get(n) ?: n
                            val y = (TOP - shown.pitch) * rowPx
                            val nx = gridPx(shown.start * pw); val w = maxOf(4f, gridPx((shown.start + shown.length) * pw) - nx - 1)
                            drawRoundRect(track.color, Offset(nx, y + 1), Size(w, rowPx - 2), CornerRadius(4f))
                            if (rowPx >= 14.dp.toPx() && w >= 26.dp.toPx()) drawText(tm.measure(Theory.name(shown.pitch), TextStyle(color = Color(0xFF0B0D10), fontSize = 10.sp)), topLeft = Offset(nx + 4.dp.toPx(), y + (rowPx - 12.dp.toPx()) / 2))
                            if (n in selSet) {
                                drawRoundRect(Color.White, Offset(nx, y + 1), Size(w, rowPx - 2), CornerRadius(4f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                                // every selected note shows its two resize handles; using one of them resizes all the selected notes together
                                val cy = y + rowPx / 2
                                for (hx in floatArrayOf(nx, nx + w)) { drawCircle(Color.White, 8.dp.toPx(), Offset(hx, cy)); drawCircle(track.color, 4.dp.toPx(), Offset(hx, cy)) }
                                if (prev != null && selSet.size == 1) drawText(tm.measure(beatsLabel(shown.length), TextStyle(color = Color.White, fontSize = 12.sp)), topLeft = Offset(nx, maxOf(0f, y - 16.dp.toPx())))
                            }
                        }
                        rectSel?.let { r -> drawRect(Zk.accent.copy(alpha = .14f), r.topLeft, r.size); drawRect(Zk.accent, r.topLeft, r.size, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())) }
                    }
                    val x = with(density) { (vm.playhead * ppb).dp.roundToPx() }
                    Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x, 0) }.width(2.dp).fillMaxHeight().background(Zk.accent))
                    // ---- floating bars: over the selection (Clone, Copy, Cut, Delete) / Paste at the playhead when nothing is selected
                    val barH = 36.dp
                    if (barVisible && selNotes.isNotEmpty() && dragPrev == null && rectSel == null) {
                        val rowPxF = with(density) { RowPx.toPx() }; val pwF = with(density) { ppb.dp.toPx() }
                        val minS = selNotes.minOf { it.start }; val maxE = selNotes.maxOf { it.start + it.length }; val topP = selNotes.maxOf { it.pitch }; val botP = selNotes.minOf { it.pitch }
                        var barW by remember { mutableIntStateOf(with(density) { 200.dp.roundToPx() }) }
                        FloatingBar(Modifier.offset {
                            val cx = ((minS + maxE) / 2f * pwF - barW / 2f).coerceIn(hs.value + 6.dp.toPx(), maxOf(hs.value + 6.dp.toPx(), hs.value + viewportPx - barW - 6.dp.toPx()))
                            var y = (TOP - topP) * rowPxF - barH.toPx() - 8.dp.toPx()
                            if (y < vs.value + 4.dp.toPx()) y = (TOP - botP + 1) * rowPxF + 8.dp.toPx()   // no room above: below the selection
                            androidx.compose.ui.unit.IntOffset(cx.roundToInt(), y.roundToInt())
                        }.onSizeChanged { barW = it.width }) {
                            BarButton("Clone") { val copies = vm.cloneNotes(track, selNotes); selection.clear(); selection.addAll(copies); barRequest++ }
                            BarButton("Copy") { vm.copyNotes(selNotes); vm.message = "${selNotes.size} note${if (selNotes.size > 1) "s" else ""} copied."; barVisible = false }
                            BarButton("Cut") { vm.cutNotes(track, selNotes); selection.clear(); barVisible = false }
                            BarButton("Del", Zk.danger) { vm.removeNotes(track, selNotes); selection.clear(); barVisible = false }
                        }
                    }
                }
            }
        }
        }
    }
}

/** A small floating row of actions drawn over the notes. */
@Composable
private fun FloatingBar(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.height(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Zk.panel).border(1.dp, Zk.accent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), content = content)
}
@Composable
private fun BarButton(text: String, color: Color = Zk.text, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false,
        modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(7.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp))
}

@Composable private fun rememberStartScroll(): Int = with(LocalDensity.current) { (RowPx.toPx() * (TOP - 84)).toInt() }  // start around C6 -> C4 area

/** Empty room kept below scrolling content so that the last rows are not under the thumb / the system navigation gesture. */
val ScrollBottomPad = 120.dp
