package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val StepW = 30.dp
private val RackRowH = 38.dp
private const val STEP = 0.25f   // a step is a sixteenth note

/** One row of the step sequencer: a pitch of an instrument track (a melodic track has one row, its step pitch; a Zedok Drums track has its 16 pads). */
private class RackRow(val track: TrackM, val pitch: Int, val label: String, val first: Boolean)

/**
 * The Channel Rack: the current pattern as a grid of steps (sixteenths), one row per channel. Tap a step to switch a note on or off, drag across steps to
 * paint (or erase, depending on the first step you touched). Zedok Drums tracks show their 16 pads; other instruments have one row and a pitch you can change.
 */
@Composable
fun RackView(vm: DawViewModel) {
    val cur = vm.currentPattern
    val steps = ((cur?.let { vm.patternLength(it) } ?: 4f) / STEP).roundToInt().coerceIn(4, 512)
    val vs = rememberScrollState(); val hs = rememberScrollState()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val rows = remember(vm.tracks.toList(), vm.stepPitches.toMap()) {
        vm.tracks.filter { it.kind == TrackKind.Instrument }.flatMap { t ->
            if (t.pluginPath == "zedok:drums") (0 until 16).map { RackRow(t, 36 + it, "Pad ${it + 1}", it == 0) }
            else listOf(RackRow(t, vm.stepPitch(t), Theory.name(vm.stepPitch(t)), true))
        }
    }
    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().height(46.dp).background(Zk.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Pattern", color = Zk.dim, fontSize = 12.sp)
            for (p in vm.patterns) Text(p.name, color = if (p === cur) Color(0xFF00201B) else Zk.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (p === cur) p.color else Zk.panel2).clickable { vm.selectPattern(p) }.padding(horizontal = 10.dp, vertical = 8.dp))
            LabelButton("+", false, w = 36.dp, h = 32.dp) { vm.newPattern() }
            Text("Play", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
            LabelButton("Song", vm.songMode, w = 54.dp, h = 32.dp) { vm.switchSongMode(true) }
            LabelButton("Pattern", !vm.songMode, w = 64.dp, h = 32.dp) { vm.switchSongMode(false) }
            val bars = steps * STEP / 4f
            Text("$steps steps · ${"%.2f".format(bars).trimEnd('0').trimEnd('.')} bar${if (bars != 1f) "s" else ""}", color = Zk.dim, fontSize = 11.sp)
        }
        if (rows.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Add an instrument track to use the step sequencer.", color = Zk.dim, fontSize = 13.sp) }; return@Column }
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(vs).padding(bottom = ScrollBottomPad)) {
            Column(Modifier.width(150.dp)) {
                for (r in rows) Row(Modifier.fillMaxWidth().height(RackRowH).background(if (vm.selected === r.track) Zk.panel2 else Zk.panel).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (r.first) Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(r.track.color))
                    Spacer(Modifier.width(6.dp))
                    if (r.track.pluginPath == "zedok:drums") Text(if (r.first) "${r.track.name} · ${r.label}" else r.label, color = if (r.first) Zk.text else Zk.dim, fontSize = 11.sp, maxLines = 1)
                    else {
                        Text(r.track.name, color = Zk.text, fontSize = 11.sp, maxLines = 1, modifier = Modifier.weight(1f).clickable { vm.selected = r.track })
                        Text("−", color = Zk.accent, fontSize = 16.sp, modifier = Modifier.clickable { vm.setStepPitch(r.track, r.pitch - 1) }.padding(horizontal = 4.dp))
                        Text(r.label, color = Zk.text, fontSize = 11.sp, maxLines = 1)
                        Text("+", color = Zk.accent, fontSize = 16.sp, modifier = Modifier.clickable { vm.setStepPitch(r.track, r.pitch + 1) }.padding(horizontal = 4.dp))
                    }
                }
            }
            Box(Modifier.weight(1f).horizontalScroll(hs)) {
                Column {
                    for (r in rows) StepRow(vm, r, steps) { p -> if (!vm.playing) scope.launch { vm.liveNote(r.track, true, p); delay(160); vm.liveNote(r.track, false, p) } }
                }
                val head = vm.patternPlayhead()
                if (head >= 0f) Canvas(Modifier.matchParentSize()) { val x = head / STEP * StepW.toPx(); drawLine(Zk.accent, Offset(x, 0f), Offset(x, size.height), 2f) }
            }
        }
    }
}

@Composable
private fun StepRow(vm: DawViewModel, r: RackRow, steps: Int, onHear: (Int) -> Unit) {
    val density = LocalDensity.current
    val t = r.track
    var pendingOn by remember(t, r.pitch, steps) { mutableStateOf<Set<Int>>(emptySet()) }
    var pendingOff by remember(t, r.pitch, steps) { mutableStateOf<Set<Int>>(emptySet()) }
    // the notes of this row, by step
    val byStep = HashMap<Int, NoteM>(); for (n in t.notes) if (n.pitch == r.pitch) { val k = (n.start / STEP).roundToInt(); if (abs(n.start - k * STEP) < 0.01f) byStep[k] = n }
    Canvas(Modifier.width(StepW * steps).height(RackRowH).pointerInput(t, r.pitch, steps) {
        val cw = StepW.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val first = (down.position.x / cw).toInt().coerceIn(0, steps - 1)
            val turnOn = byStep[first] == null
            val on = HashSet<Int>(); val off = HashSet<Int>()
            fun touch(i: Int) { if (turnOn) { if (byStep[i] == null) on.add(i) } else if (byStep[i] != null) off.add(i); pendingOn = HashSet(on); pendingOff = HashSet(off) }
            touch(first); down.consume(); if (turnOn) onHear(r.pitch)
            var last = first
            while (true) {
                val ev = awaitPointerEvent(); val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                c.consume(); if (!c.pressed) break
                val i = (c.position.x / cw).toInt().coerceIn(0, steps - 1)
                if (i != last) { val lo = minOf(i, last); val hi = maxOf(i, last); for (k in lo..hi) touch(k); last = i }
            }
            val add = on.map { NoteM(r.pitch, it * STEP, STEP, 100) }; val gone = off.mapNotNull { byStep[it] }
            pendingOn = emptySet(); pendingOff = emptySet()
            vm.editNotes(t, gone, add, "Steps")
        }
    }) {
        val cw = StepW.toPx(); val h = size.height
        for (i in 0 until steps) {
            val x = i * cw
            val group = (i / 4) % 2 == 0
            drawRoundRect(if (group) Color(0xFF22262E) else Color(0xFF1A1D23), Offset(x + 1f, 3f), Size(cw - 2f, h - 6f), CornerRadius(4f))
            val n = byStep[i]; val active = (n != null && i !in pendingOff) || i in pendingOn
            if (active) drawRoundRect(t.color.copy(alpha = (0.45f + 0.55f * ((n?.velocity ?: 100) / 127f)).coerceIn(0.3f, 1f)), Offset(x + 1f, 3f), Size(cw - 2f, h - 6f), CornerRadius(4f))
        }
    }
}
