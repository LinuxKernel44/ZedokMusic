package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
private val LocalRowScale = compositionLocalOf { 1f }
/** Height of an arrangement track row: the base height (denser on short windows) times the vertical zoom, never too small for its buttons. */
@Composable private fun rowH(): androidx.compose.ui.unit.Dp = ((if (LocalShort.current) RowHShort else RowHTall) * LocalRowScale.current).coerceAtLeast(54.dp)
private val HeaderW = 196.dp
private val RulerH = 28.dp

/** Arrangement: track headers on the left, a scrolling timeline on the right (beats on the x axis). */
@Composable
fun ArrangeView(vm: DawViewModel, pxPerBeat: Float, onZoom: (Float) -> Unit, onEdit: (TrackM) -> Unit, onImport: (TrackM) -> Unit) {
    val hs = rememberScrollState(vm.arrangeX); val vs = rememberScrollState(vm.arrangeY)
    // Remember where the timeline is scrolled (saved with the project); jump there when another project is opened.
    LaunchedEffect(Unit) { snapshotFlow { hs.value to vs.value }.collect { (x, y) -> vm.viewScrolled(x, y) } }
    var seenView by remember { mutableIntStateOf(vm.viewRevision) }
    LaunchedEffect(vm.viewRevision) { if (vm.viewRevision != seenView) { seenView = vm.viewRevision; hs.scrollTo(vm.arrangeX); vs.scrollTo(vm.arrangeY) } }
    var viewportPx by remember { mutableIntStateOf(0) }
    FollowPlayhead(vm, hs, pxPerBeat, viewportPx)
    val density = LocalDensity.current
    val ppb = pxPerBeat
    val totalBeats = maxOf(64f, vm.songBeats() + 32f)
    val totalW = (totalBeats * ppb).dp
    // Pinch zoom: the beat under the fingers stays under them (the scroll position is corrected once the new width is laid out).
    var anchor by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    LaunchedEffect(ppb) { anchor?.let { (beat, cx) -> hs.scrollTo((beat * ppb * density.density - cx).toInt().coerceAtLeast(0)); anchor = null } }
    val ppbNow = rememberUpdatedState(ppb); val headerPx = with(density) { HeaderW.toPx() }
    val dnd = LocalDnd.current
    SideEffect { dnd.ppb = ppb; dnd.density = density.density }
    DisposableEffect(Unit) { onDispose { dnd.arrangeRect = null; dnd.lanes.clear(); dnd.newZone = null } }
    // While something is dragged, the tracks scroll when the finger is near an edge.
    LaunchedEffect(dnd.payload != null) {
        while (dnd.payload != null) {
            kotlinx.coroutines.delay(16)
            val r = dnd.arrangeRect; val at = dnd.pos
            if (r != null && r.contains(at)) {
                val edge = with(density) { 48.dp.toPx() }
                if (at.y > r.bottom - edge) vs.scrollBy(18f) else if (at.y < r.top + edge) vs.scrollBy(-18f)
                if (at.x > r.right - edge) hs.scrollBy(18f) else if (at.x < r.left + headerPx + edge) hs.scrollBy(-18f)
                dnd.move(at)
            }
        }
    }
    // ---- clip selection: rectangle (long press in empty space then drag, or drag with the Select tool); near an edge the timeline scrolls
    var rectAnchor by remember { mutableStateOf<Offset?>(null) }
    var rectFinger by remember { mutableStateOf<Offset?>(null) }
    var rectSel by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var viewH by remember { mutableIntStateOf(0) }
    val short = LocalShort.current
    val rowHpx = with(density) { ((if (short) RowHShort else RowHTall) * vm.rowScale).coerceAtLeast(54.dp).toPx() }
    val padPx = with(density) { 8.dp.toPx() }
    val patPx = with(density) { (TimelineLaneH + PatRowH * patternRows(vm)).toPx() }   // the pattern lane above the track rows
    fun clipBounds(c: ClipM, trackIndex: Int): androidx.compose.ui.geometry.Rect {
        val pw = ppb * density.density; val len = vm.clipBeats(c)
        return androidx.compose.ui.geometry.Rect(c.start * pw, patPx + trackIndex * rowHpx + padPx, maxOf((c.start + len) * pw, c.start * pw + 12.dp.value * density.density), patPx + (trackIndex + 1) * rowHpx - padPx)
    }
    fun clipAt(p: Offset): ClipM? { for ((i, t) in vm.tracks.withIndex()) for (c in t.clips) if (clipBounds(c, i).contains(p)) return c; return null }
    fun updateRect() {
        val an = rectAnchor ?: return; val fg = rectFinger ?: return
        val p = Offset(fg.x + hs.value, fg.y + vs.value)
        val rc = androidx.compose.ui.geometry.Rect(minOf(an.x, p.x), minOf(an.y, p.y), maxOf(an.x, p.x), maxOf(an.y, p.y))
        rectSel = rc
        val hits = ArrayList<ClipM>()
        for ((i, t) in vm.tracks.withIndex()) for (c in t.clips) if (clipBounds(c, i).overlaps(rc)) hits.add(c)
        vm.clipSelection.clear(); vm.clipSelection.addAll(hits)
    }
    LaunchedEffect(rectAnchor != null) {
        while (rectAnchor != null) {
            kotlinx.coroutines.delay(16)
            val fg = rectFinger ?: continue
            val edge = with(density) { 44.dp.toPx() }; val step = with(density) { 14.dp.toPx() }
            val dx = if (fg.x > viewportPx - edge) step else if (fg.x < edge) -step else 0f
            val dy = if (fg.y > viewH - edge) step else if (fg.y < edge) -step else 0f
            if (dx != 0f) hs.scrollBy(dx); if (dy != 0f) vs.scrollBy(dy)
            if (dx != 0f || dy != 0f) updateRect()
        }
    }
    var editClips by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalRowScale provides vm.rowScale) {
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().height(RulerH).background(Zk.panel)) {
            Row(Modifier.width(HeaderW).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                SquareButton(Glyph.Minus, size = 24.dp) { onZoom((ppb / 1.4f).coerceAtLeast(DawViewModel.ZOOM_MIN)) }
                Spacer(Modifier.width(6.dp))
                SquareButton(Glyph.Plus, size = 24.dp) { onZoom((ppb * 1.4f).coerceAtMost(DawViewModel.ZOOM_MAX)) }
                Spacer(Modifier.width(6.dp))
                LabelButton("Follow", vm.followPlayhead, w = 50.dp, h = 24.dp) { vm.followPlayhead = !vm.followPlayhead }
                Spacer(Modifier.width(6.dp))
                LabelButton("♪", vm.browserOpen, w = 30.dp, h = 24.dp) { vm.browserOpen = !vm.browserOpen }
                Spacer(Modifier.width(6.dp))
                LabelButton("Sel", vm.clipSelectMode, w = 34.dp, h = 24.dp) { vm.clipSelectMode = !vm.clipSelectMode }
            }
            Box(Modifier.weight(1f).fillMaxHeight().onSizeChanged { viewportPx = it.width }.horizontalScroll(hs)) { Ruler(vm, totalBeats, ppb, totalW) }
        }
        Row(Modifier.fillMaxSize().onGloballyPositioned { dnd.arrangeRect = it.boundsInRoot() }.onSizeChanged { viewH = it.height }.pointerInput(Unit) {
            // two fingers: apart/together horizontally = time zoom, vertically = track height
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                var prevX = 0f; var prevY = 0f; var pinching = false
                var cur = ppbNow.value; var beat0 = 0f   // the zoom is tracked here (the state follows a frame later), and the beat under the fingers when the pinch began
                do {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val pts = ev.changes.filter { it.pressed }
                    if (pts.size >= 2) {
                        val p0 = pts[0].position; val p1 = pts[1].position
                        val sx = kotlin.math.abs(p0.x - p1.x); val sy = kotlin.math.abs(p0.y - p1.y); val cx = (p0.x + p1.x) / 2f - headerPx
                        if (!pinching) beat0 = (hs.value + cx) / (cur * density.density)
                        if (pinching) {
                            if (sy > sx * 1.5f && prevY > 40f && sy > 40f) vm.rowScale = (vm.rowScale * sy / prevY).coerceIn(0.7f, 2.4f)
                            else if (prevX > 40f && sx > 40f) {
                                val nw = (cur * sx / prevX).coerceIn(DawViewModel.ZOOM_MIN, DawViewModel.ZOOM_MAX)
                                if (nw != cur) { cur = nw; anchor = beat0 to cx; vm.ppb = nw }
                            }
                            ev.changes.forEach { it.consume() }
                        }
                        prevX = sx; prevY = sy; pinching = true
                    } else pinching = false
                } while (ev.changes.any { it.pressed })
            }
        }.verticalScroll(vs).padding(bottom = ScrollBottomPad)) {
            Column(Modifier.width(HeaderW)) { TimelineHeader(vm); PatternHeader(vm); vm.tracks.forEach { TrackHeader(vm, it, onImport) } ; if (vm.tracks.isEmpty()) EmptyHint(); if (dnd.payload != null) Spacer(Modifier.height(rowH() * 0.8f)) }
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                Box(Modifier.width(totalW).pointerInput(ppb, vm.tracks.size, rowHpx) {
                    val lp = viewConfiguration.longPressTimeoutMillis; val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val t0 = System.currentTimeMillis(); var rect = false; var last = down.position
                        while (true) {
                            val waitLong = !vm.clipSelectMode
                            val ev = if (waitLong) withTimeoutOrNull(maxOf(1L, lp - (System.currentTimeMillis() - t0))) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final) }
                                     else awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Final)
                            if (ev == null) { if ((last - down.position).getDistance() > slop || clipAt(down.position) != null) return@awaitEachGesture; rect = true; break }
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (c.isConsumed) return@awaitEachGesture            // a clip took it (tap, drag, menu) or the timeline is scrolling
                            last = c.position
                            if (!c.pressed) { if (clipAt(down.position) == null) vm.clearClipSelection(); return@awaitEachGesture }
                            if (vm.clipSelectMode && (c.position - down.position).getDistance() > slop && clipAt(down.position) == null) { rect = true; break }
                        }
                        if (rect) {
                            rectAnchor = down.position; rectFinger = Offset(down.position.x - hs.value, down.position.y - vs.value); updateRect()
                            while (true) {
                                val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                c.consume(); rectFinger = Offset(c.position.x - hs.value, c.position.y - vs.value); updateRect()
                            }
                            rectAnchor = null; rectFinger = null; rectSel = null
                        }
                    }
                }) {
                    Column {
                        TimelineLane(vm, totalBeats, ppb)
                        PatternTimeline(vm, totalBeats, ppb)
                        vm.tracks.forEach { t -> TimelineRow(vm, t, totalBeats, ppb, onEdit) }
                        if (dnd.payload != null) NewTrackZone(dnd)
                    }
                    // matchParentSize: fillMaxHeight measures to nothing inside a vertically scrolling parent.
                    Canvas(Modifier.matchParentSize()) {
                        drawPlayhead(vm.playhead * ppb * density.density, Zk.accent, head = false)
                        rectSel?.let { r -> drawRect(Zk.accent.copy(alpha = .14f), r.topLeft, r.size); drawRect(Zk.accent, r.topLeft, r.size, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx())) }
                    }
                }
            }
        }
    }
    // The actions of the selection (and Paste) sit at the bottom, out of the way of the tracks.
    val sel = vm.liveSelection()
    if (sel.isNotEmpty() || vm.clipClip.isNotEmpty()) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(40.dp).background(Zk.panel2).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (sel.isEmpty()) {
            LabelButton("Paste ${vm.clipClip.size}", false, w = 78.dp, h = 30.dp) { val at = (vm.playhead / com.zedokmusic.settings.AppSettings.snapBeats).roundToInt() * com.zedokmusic.settings.AppSettings.snapBeats; val p = vm.pasteClips(at); vm.clipSelection.clear(); vm.clipSelection.addAll(p) }
            Text("at the playhead", color = Zk.dim, fontSize = 11.sp)
            LabelButton("All", false, w = 38.dp, h = 30.dp) { vm.clipSelection.clear(); vm.tracks.forEach { vm.clipSelection.addAll(it.clips) } }
        } else {
            Text("${sel.size} clip${if (sel.size > 1) "s" else ""}", color = Zk.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 52.dp))
            LabelButton("Clone", false, w = 54.dp, h = 30.dp) { val n = vm.cloneClips(sel); vm.clipSelection.clear(); vm.clipSelection.addAll(n) }
            LabelButton("Copy", false, w = 48.dp, h = 30.dp) { vm.copyClips(sel); vm.message = "${sel.size} clip${if (sel.size > 1) "s" else ""} copied." }
            LabelButton("Cut", false, w = 42.dp, h = 30.dp) { vm.cutClips(sel) }
            LabelButton("Del", false, Zk.danger, w = 42.dp, h = 30.dp) { vm.removeClips(sel) }
            LabelButton("Edit", false, w = 44.dp, h = 30.dp) { editClips = true }
            LabelButton("All", false, w = 38.dp, h = 30.dp) { vm.clipSelection.clear(); vm.tracks.forEach { vm.clipSelection.addAll(it.clips) } }
            LabelButton("✕", false, w = 32.dp, h = 30.dp) { vm.clearClipSelection() }
        }
    }
    if (editClips && sel.isNotEmpty()) vm.trackOf(sel[0])?.let { t0 -> ClipEditDialog(vm, t0, sel[0], sel) { editClips = false } } else if (editClips) editClips = false
    }  // Box
    }  // CompositionLocalProvider
}

/** Shown while dragging: dropping here creates a new track. */
@Composable private fun NewTrackZone(dnd: DndState) {
    val h = dnd.hover is Hover.NewTrack
    val ok = h && dnd.payload?.let { dnd.accepts(it, Hover.NewTrack) } == true
    DisposableEffect(Unit) { onDispose { dnd.newZone = null } }
    Box(Modifier.fillMaxWidth().height(rowH() * 0.8f).onGloballyPositioned { dnd.newZone = it.boundsInRoot() }.padding(8.dp).clip(RoundedCornerShape(10.dp))
        .background(if (ok) Zk.accent.copy(alpha = .14f) else Zk.panel.copy(alpha = .6f)).border(if (h) 2.dp else 1.dp, if (ok) Zk.accent else if (h) Zk.danger else Zk.line, RoundedCornerShape(10.dp))) {
        Text(when { h && !ok -> "Not here"; dnd.payload?.isFolder == true -> "Drop for a new Zedok Drums track with this kit"; dnd.payload?.plugin != null -> "Drop for a new track"; else -> "Drop for a new audio track" },
            color = if (ok) Zk.accent else Zk.dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
    }
}

@Composable private fun EmptyHint() {
    val RowH = rowH()
    Box(Modifier.width(HeaderW).height(RowH), contentAlignment = Alignment.Center) { Text("Add a track with +", color = Zk.dim, fontSize = 12.sp) }
}

@Composable
private fun Ruler(vm: DawViewModel, totalBeats: Float, ppb: Float, totalW: androidx.compose.ui.unit.Dp) {
    val tm = rememberTextMeasurer()
    Canvas(Modifier.width(totalW).fillMaxHeight().scrubRuler(ppb) { vm.seekBeat(it) }) {
        val pw = ppb * density
        var b = 0
        while (b <= totalBeats) { val x = gridLineX(b * pw); drawLine(Zk.line, Offset(x, 18.dp.toPx()), Offset(x, size.height), 1f); b++ }   // beats
        // bar lines and numbers follow the time signature changes of the lane above
        val bars = TimeSig.barStarts(vm.sigChanges.toList(), totalBeats)
        for ((i, bl) in bars.withIndex()) {
            val x = gridLineX(bl.first * pw); drawLine(Zk.dim, Offset(x, 8.dp.toPx()), Offset(x, size.height), 1f)
            val nextW = if (i + 1 < bars.size) (bars[i + 1].first - bl.first) * pw else 4 * pw
            if (nextW > 40) drawText(tm.measure("${bl.second}", androidx.compose.ui.text.TextStyle(color = Zk.dim, fontSize = 10.sp)), topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx()))
        }
        if (vm.loop) drawRect(Zk.accent.copy(alpha = .35f), Offset(0f, size.height - 4.dp.toPx()), Size(vm.loopBars * 4 * pw, 4.dp.toPx()))
        drawPlayhead(vm.playhead * pw, Zk.accent, head = true)
    }
}

@Composable
private fun TrackHeader(vm: DawViewModel, t: TrackM, onImport: (TrackM) -> Unit) {
    val RowH = rowH()
    val sel = vm.selected == t
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Box(Modifier.width(HeaderW).height(RowH).background(if (sel) Zk.panel2 else Zk.panel).border(0.5.dp, Zk.line).clickable { vm.selected = t }) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(t.color))
        Column(Modifier.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.kind == TrackKind.Instrument) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(when (t.pluginState) { 2 -> Zk.accent; 3 -> Zk.danger; else -> Zk.warn }))
                    Spacer(Modifier.width(6.dp))
                }
                Text(t.name, color = Zk.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = !t.frozen))
                if (t.frozen || vm.freezing == t) Text(if (t.frozen) "FROZEN" else "…", color = Color(0xFF0B0D10), fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp).clip(RoundedCornerShape(3.dp)).background(Zk.accent).padding(horizontal = 4.dp, vertical = 1.dp))
                if (renaming) NameDialog("Rename track", t.name, "Rename", null, { vm.renameTrack(t, it) }, { renaming = false })
                Box {
                    Text("⋮", color = Zk.dim, fontSize = 18.sp, modifier = Modifier.clickable { menu = true }.padding(horizontal = 6.dp))
                    DropdownMenu(menu, { menu = false }) {
                        if (t.kind == TrackKind.Audio) DropdownMenuItem(text = { Text("Add sample…") }, onClick = { menu = false; onImport(t) })
                        DropdownMenuItem(text = { Text("Rename track…") }, onClick = { menu = false; renaming = true })
                        if (t.kind != TrackKind.Audio || t.clips.isNotEmpty() || t.frozen) DropdownMenuItem(text = { Text(if (t.frozen) "Unfreeze track" else "Freeze track") }, onClick = { menu = false; if (t.frozen) vm.unfreezeTrack(t) else vm.freezeTrack(t) })
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
    val RowH = rowH()
    val density = LocalDensity.current
    val sel = vm.selected == t
    val dnd = LocalDnd.current
    DisposableEffect(t) { onDispose { dnd.lanes.remove(t) } }
    Box(Modifier.height(RowH).fillMaxWidth().background(if (sel) Zk.panel2.copy(alpha = .55f) else Zk.bg).border(0.5.dp, Zk.line).onGloballyPositioned { dnd.lanes[t] = it.boundsInRoot() }) {
        Canvas(Modifier.matchParentSize()) {
            val pw = ppb * density.density
            var b = 0
            while (b <= totalBeats) { val gx = gridLineX(b * pw); drawLine(if (b % 4 == 0) Zk.line else Zk.line.copy(alpha = .35f), Offset(gx, 0f), Offset(gx, size.height), 1f); b++ }
        }
        if (t.kind == TrackKind.Instrument) {
            // The notes the playlist puts on this track (every placement of every pattern), drawn over faint blocks that mark the placements.
            // Tapping inside a placement opens the piano roll on that placement's pattern.
            val placed = vm.playlist.toList(); val cur = vm.currentPattern
            val shown = remember(placed.map { Triple(it.pattern, it.start, it.length) }, t.notes.toList(), cur, vm.bpm) { vm.notesOnTimeline(t) }
            Box(Modifier.matchParentSize().pointerInput(t, ppb, placed.size) {
                detectTapGestures { off ->
                    val beat = off.x / (ppb * density.density)
                    placed.firstOrNull { beat >= it.start && beat < it.start + vm.clipLength(it) }?.let { vm.selectPattern(it.pattern); vm.selectedPatternClip = it }
                    vm.selected = t; onEdit(t)
                }
            }) {
                Canvas(Modifier.matchParentSize()) {
                    val pw = ppb * density.density
                    for (c in placed) {
                        val x0 = gridPx(c.start * pw); val x1 = gridPx((c.start + vm.clipLength(c)) * pw)
                        drawRoundRect(t.color.copy(alpha = if (c.pattern === cur) .26f else .14f), Offset(x0, 8.dp.toPx()), Size(x1 - x0, size.height - 16.dp.toPx()), CornerRadius(8.dp.toPx()))
                        drawRoundRect(t.color.copy(alpha = .7f), Offset(x0, 8.dp.toPx()), Size(x1 - x0, size.height - 16.dp.toPx()), CornerRadius(8.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                    }
                    if (shown.isNotEmpty()) {
                        val lo = shown.minOf { it.pitch }; val hi = shown.maxOf { it.pitch }; val span = maxOf(1, hi - lo)
                        val top = 14.dp.toPx(); val ph = (size.height - 28.dp.toPx()) / (span + 1)
                        for (n in shown) { val x0 = gridPx(n.start * pw); val x1 = gridPx((n.start + n.length) * pw); drawRoundRect(t.color, Offset(x0, top + (hi - n.pitch) * ph), Size(maxOf(2f, x1 - x0), maxOf(2f, ph * .8f)), CornerRadius(2f)) }
                    }
                }
                if (shown.isEmpty() && t.notes.isEmpty()) Text("Tap to write notes", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
            }
        }
        for (c in t.clips) ClipBlock(vm, t, c, ppb)
        val dp = dnd.payload; val hv = dnd.hover
        if (dp != null && hv is Hover.Lane && hv.track === t) {
            val ok = dnd.accepts(dp, hv); val col = if (ok) Zk.accent else Zk.danger
            Box(Modifier.matchParentSize().background(col.copy(alpha = .08f)).border(2.dp, col))
            if (ok && t.kind == TrackKind.Audio && dp.isSound) Box(Modifier.offset { IntOffset(with(density) { (hv.beat * ppb).dp.roundToPx() }, 0) }.padding(vertical = 8.dp).width((ppb * 2).dp).fillMaxHeight()
                .clip(RoundedCornerShape(8.dp)).background(col.copy(alpha = .22f)).border(1.5.dp, col, RoundedCornerShape(8.dp))) { Text(dp.title, color = col, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(5.dp)) }
        }
    }
}

@Composable
private fun ClipBlock(vm: DawViewModel, t: TrackM, c: ClipM, ppb: Float) {
    val density = LocalDensity.current
    var drag by remember(c) { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var selfDrag by remember(c) { mutableStateOf(false) }
    val selected = c in vm.clipSelection
    val lenBeats = vm.clipBeats(c)
    val follow = if (selected && vm.clipDragging && !selfDrag) vm.clipDragBeats * ppb else drag
    val x = with(density) { ((c.start * ppb) + follow).dp.roundToPx() }
    // The real waveform, computed once and cached in the project (Cache/Peaks); it follows the processed copy when pitch / speed were changed.
    val peaks by produceState<ByteArray?>(null, c.path) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.clipPeaks(c) } }
    Box(Modifier.offset { IntOffset(x, 0) }.padding(vertical = 8.dp).width((lenBeats * ppb).dp.coerceAtLeast(12.dp)).fillMaxHeight()
        .clip(RoundedCornerShape(8.dp)).background(t.color.copy(alpha = if (selected) .5f else .35f)).border(if (selected) 2.dp else 1.dp, if (selected) Color.White else t.color, RoundedCornerShape(8.dp))
        .pointerInput(c, ppb, vm.bpm) {
            detectDragGestures(onDragStart = { if (c !in vm.clipSelection) { vm.clipSelection.clear(); vm.clipSelection.add(c) }; selfDrag = true; vm.clipDragging = true; vm.clipDragBeats = 0f },
                onDragEnd = {
                    val snap = com.zedokmusic.settings.AppSettings.snapBeats
                    val target = ((c.start + drag / density.density / ppb) / snap).roundToInt() * snap    // snap to the grid chosen in Settings
                    val group = vm.liveSelection().ifEmpty { listOf(c) }
                    drag = 0f; selfDrag = false; vm.clipDragging = false; vm.clipDragBeats = 0f
                    vm.moveClips(group, target - c.start)
                }, onDragCancel = { drag = 0f; selfDrag = false; vm.clipDragging = false; vm.clipDragBeats = 0f }) { ch, d -> ch.consume(); drag += d.x; vm.clipDragBeats = drag / density.density / ppb }
        }.pointerInput(c) { detectTapGestures(onTap = { if (vm.clipSelectMode) { if (!vm.clipSelection.remove(c)) vm.clipSelection.add(c) } else { vm.clipSelection.clear(); vm.clipSelection.add(c) } }, onLongPress = { menu = true }) }) {
        if (c.hasFades && c.lengthFrames > 0) Canvas(Modifier.matchParentSize()) {   // the fade ramps: what the fade takes away is shaded
            val secs = c.lengthFrames / 48000f
            fun ramp(frac: Float, shape: Int, fromLeft: Boolean) {
                val wpx = (size.width * frac).coerceIn(0f, size.width); if (wpx < 1f) return
                val path = androidx.compose.ui.graphics.Path(); val n = 24
                path.moveTo(if (fromLeft) 0f else size.width, 0f)
                for (k in 0..n) { val u = k / n.toFloat(); val x = if (fromLeft) wpx * u else size.width - wpx * u; path.lineTo(x, size.height * (1f - com.zedokmusic.engine.fadeShape(shape, if (fromLeft) u else u)).let { y -> y }) }
                path.lineTo(if (fromLeft) wpx else size.width - wpx, 0f); path.close()
                drawPath(path, Zk.bg.copy(alpha = .55f))
            }
            ramp(c.fadeInMs / 1000f / secs, c.fadeInShape, true); ramp(c.fadeOutMs / 1000f / secs, c.fadeOutShape, false)
        }
        peaks?.let { pk ->
            Canvas(Modifier.matchParentSize().padding(vertical = 2.dp)) {
                val nb = pk.size / 2; if (nb == 0) return@Canvas
                val w = size.width.toInt().coerceAtLeast(1); val mid = size.height / 2; val amp = size.height / 2 * 0.92f
                for (px in 0 until w) {
                    val b0 = (px.toLong() * nb / w).toInt(); val b1 = maxOf(b0 + 1, ((px + 1).toLong() * nb / w).toInt()).coerceAtMost(nb)
                    var lo = 0; var hi = 0
                    for (b in b0 until b1) { val l = pk[b * 2].toInt(); val h = pk[b * 2 + 1].toInt(); if (l < lo) lo = l; if (h > hi) hi = h }
                    val y0 = mid - hi / 127f * amp; val y1 = mid - lo / 127f * amp
                    drawLine(t.color.copy(alpha = .9f), Offset(px + 0.5f, y0), Offset(px + 0.5f, maxOf(y1, y0 + 1f)), 1f)
                }
            }
        }
        Text(c.name + (if (c.busy) "  · processing…" else if (c.edited || c.hasFades) "  · " + clipSummary(c) else ""), color = Zk.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(6.dp))
        DropdownMenu(menu, { menu = false }) {
            // the full name, even when the clip is too short to show it
            Text(java.io.File(c.src).name + if (c.edited) "\n" + clipSummary(c) else "", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 16.dp, vertical = 8.dp))
            DropdownMenuItem(text = { Text("Edit clip…") }, onClick = { menu = false; edit = true })
            DropdownMenuItem(text = { Text("Remove clip") }, onClick = { menu = false; vm.removeClip(t, c) })
        }
    }
    if (edit) ClipEditDialog(vm, t, c) { edit = false }
}

private fun clipSummary(c: ClipM): String = buildList {
    if (c.gainDb != 0f) add("%+.1f dB".format(c.gainDb))
    if (c.hasFades) add("fades")
    if (c.semis != 0f) add("%+.1f st".format(c.semis))
    if (c.speed != 1f) add("×%.2f".format(c.speed))
}.joinToString(" · ")
