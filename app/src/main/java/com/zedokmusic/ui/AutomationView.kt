package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*

/** Draws parameter automation: tap to add a point, drag to move it, double-tap to delete it. Arm REC and move a knob to record. */
@Composable
fun AutomationView(vm: DawViewModel) {
    val t = vm.selected?.takeIf { it.kind == TrackKind.Instrument } ?: vm.tracks.firstOrNull { it.kind == TrackKind.Instrument }
    if (t == null || t.pluginState != 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Add an instrument track and wait for its green dot.", color = Zk.dim, fontSize = 13.sp) }
        return
    }
    val lane = vm.laneSelected?.takeIf { it in t.lanes } ?: t.lanes.firstOrNull()
    var picking by remember { mutableStateOf(false) }
    val short = LocalShort.current
    var sel by remember(lane) { mutableIntStateOf(-1) }  // selected point (its outgoing segment gets the shape buttons)
    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = if (short) 3.dp else 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(lane?.paramName ?: "No automation yet", color = t.color, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!short || vm.recording) Text(if (vm.recording) "Recording: move a knob" else "${t.lanes.size} lane(s) · REC + move a knob to record", color = if (vm.recording) Zk.danger else Zk.dim, fontSize = 11.sp)
            }
            LabelButton("+ Param", false, w = 78.dp) { picking = true }
            if (lane != null) LabelButton("Delete", false, w = 64.dp) { vm.removeLane(t, lane) }
        }
        if (lane != null) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (short) 2.dp else 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val cur = lane.points.getOrNull(sel)
            Text(if (cur == null) "Tap a point to choose its curve" else "Curve after the point:", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            listOf("Line" to 0, "Step" to 1, "Smooth" to 2).forEach { (label, sh) ->
                LabelButton(label, cur?.shape == sh, w = 64.dp) { if (cur != null) { val pts = lane.points.toMutableList(); pts[sel] = cur.copy(shape = sh); vm.setLanePoints(t, lane, pts) } }
            }
        }
        if (t.lanes.size > 1) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (short) 2.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            t.lanes.take(6).forEach { l -> LabelButton(l.paramName.take(9), l === lane, w = 80.dp) { vm.laneSelected = l } }
        }
        if (lane == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tap \"+ Param\" to automate a parameter.", color = Zk.dim, fontSize = 13.sp) }
        else LaneEditor(vm, t, lane, sel) { sel = it }
    }
    if (picking) ParamPicker(vm, t, onPick = { idx -> vm.addLane(t, idx)?.let { vm.laneSelected = it }; picking = false }, onDismiss = { picking = false })
}

@Composable
private fun LaneEditor(vm: DawViewModel, t: TrackM, lane: AutoLane, sel: Int, onSelect: (Int) -> Unit) {
    val beats = maxOf(vm.loopBars * 4f, (lane.points.lastOrNull()?.beat ?: 0f) + 1f, 4f)
    var drag by remember(lane) { mutableIntStateOf(-1) }
    Canvas(Modifier.fillMaxSize().padding(10.dp)
        .pointerInput(lane, beats) {
            val hit = 28.dp.toPx()
            fun toX(b: Float) = b / beats * size.width
            fun toY(v: Float) = (1f - v) * size.height
            fun nearest(o: Offset): Int = lane.points.indices.minByOrNull { (Offset(toX(lane.points[it].beat), toY(lane.points[it].value)) - o).getDistance() }
                ?.takeIf { (Offset(toX(lane.points[it].beat), toY(lane.points[it].value)) - o).getDistance() < hit } ?: -1
            detectTapGestures(
                onTap = { o ->
                    val i = nearest(o)
                    if (i >= 0) onSelect(i)
                    else { val np = AutoPoint((o.x / size.width * beats).coerceIn(0f, beats), (1f - o.y / size.height).coerceIn(0f, 1f))
                        vm.setLanePoints(t, lane, lane.points + np); onSelect(lane.points.indexOf(np)) }
                },
                onDoubleTap = { o -> val i = nearest(o); if (i >= 0 && lane.points.size > 1) { vm.setLanePoints(t, lane, lane.points.filterIndexed { k, _ -> k != i }); onSelect(-1) } },
            )
        }
        .pointerInput(lane, beats) {
            val hit = 28.dp.toPx()
            detectDragGestures(
                onDragStart = { o ->
                    drag = lane.points.indices.minByOrNull { (Offset(lane.points[it].beat / beats * size.width, (1f - lane.points[it].value) * size.height) - o).getDistance() }
                        ?.takeIf { (Offset(lane.points[it].beat / beats * size.width, (1f - lane.points[it].value) * size.height) - o).getDistance() < hit } ?: -1
                },
                onDrag = { change, _ ->
                    if (drag >= 0 && drag < lane.points.size) {
                        val nb = (change.position.x / size.width * beats).coerceIn(0f, beats); val nv = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        val pts = lane.points.toMutableList(); val moved = lane.points[drag].copy(beat = nb, value = nv); pts[drag] = moved
                        vm.setLanePoints(t, lane, pts); drag = lane.points.indexOf(moved); onSelect(drag)  // sorting may reorder
                        change.consume()
                    }
                },
                onDragEnd = { drag = -1 }, onDragCancel = { drag = -1 },
            )
        }
    ) {
        for (b in 0..beats.toInt()) { val x = b / beats * size.width; drawLine(if (b % 4 == 0) Zk.line else Zk.panel2, Offset(x, 0f), Offset(x, size.height), strokeWidth = if (b % 4 == 0) 2f else 1f) }
        for (q in 0..4) { val y = q / 4f * size.height; drawLine(Zk.panel2, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f) }
        val pts = lane.points
        if (pts.isNotEmpty()) {
            val path = Path()
            fun X(b: Float) = b / beats * size.width
            fun Y(v: Float) = (1f - v) * size.height
            path.moveTo(0f, Y(pts.first().value)); path.lineTo(X(pts.first().beat), Y(pts.first().value))
            for (i in 0 until pts.size - 1) {
                val a0 = pts[i]; val b0 = pts[i + 1]
                when (a0.shape) {
                    1 -> { path.lineTo(X(b0.beat), Y(a0.value)); path.lineTo(X(b0.beat), Y(b0.value)) }
                    2 -> for (k in 1..24) { val u = k / 24f; val e = u * u * (3f - 2f * u); path.lineTo(X(a0.beat + (b0.beat - a0.beat) * u), Y(a0.value + (b0.value - a0.value) * e)) }
                    else -> path.lineTo(X(b0.beat), Y(b0.value))
                }
            }
            path.lineTo(size.width, Y(pts.last().value))
            drawPath(path, t.color, style = Stroke(width = 4f))
            pts.forEachIndexed { i, p -> drawCircle(if (i == drag || i == sel) Zk.warn else t.color, if (i == sel) 14f else 11f, Offset(X(p.beat), Y(p.value))) }
        }
        val px = vm.playhead.coerceIn(0f, beats) / beats * size.width
        drawLine(Zk.text, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
    }
}

@Composable
private fun ParamPicker(vm: DawViewModel, t: TrackM, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val params = remember(t.id) { vm.loadParams(t) }
    val shown = remember(params, query) { params.filter { query.isBlank() || it.name.contains(query, true) }.take(200) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Cancel") } }, title = { Text("Automate a parameter") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = dialogListMax(360.dp))) {
                items(shown, key = { it.index }) { p ->
                    Text(p.name, Modifier.fillMaxWidth().padding(vertical = 10.dp).androidClickable { onPick(p.index) }, color = Zk.text, fontSize = 14.sp)
                }
            }
        }
    })
}

private fun Modifier.androidClickable(onClick: () -> Unit) = this.then(Modifier.pointerInput(Unit) { detectTapGestures { onClick() } })
