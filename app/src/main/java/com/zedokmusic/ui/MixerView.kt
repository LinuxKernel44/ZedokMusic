package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*

@Composable
fun MixerView(vm: DawViewModel, onInserts: (TrackM?) -> Unit = {}) {
    var routeTrack by remember { mutableStateOf<TrackM?>(null) }; var routeBus by remember { mutableStateOf<BusM?>(null) }
    routeTrack?.let { t -> if (t in vm.tracks) TrackRoutingDialog(vm, t) { routeTrack = null } else routeTrack = null }
    routeBus?.let { b -> if (b in vm.buses) BusDialog(vm, b) { routeBus = null } else routeBus = null }
    Row(Modifier.fillMaxSize().background(Zk.bg).horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.tracks.forEach { t ->
            Strip((if (t.frozen) "❄ " else "") + t.name, t.color, t.gain, t.pan, t.meter, t.mute, t.solo, vm.selected == t, t.fx.size, t.fx.any { !it.bypass }, { onInserts(t) }, stereo = t.stereo, onStereo = { vm.setStereo(t, it) },
                onGain = { vm.setGain(t, it) }, onPan = { vm.setPan(t, it) }, onMute = { vm.setMute(t, !t.mute) }, onSolo = { vm.setSolo(t, !t.solo) }, onSelect = { vm.selected = t },
                route = routeLabel(vm, t), routeActive = t.output != 0 || t.sends.isNotEmpty(), onRoute = { routeTrack = t })
        }
        vm.buses.forEach { b ->
            Strip(b.name, b.color, b.gain, b.pan, 0f, b.mute, false, false, 0, false, {}, stereo = b.stereo, onStereo = { vm.setBusStereo(b, it) }, onGain = { vm.setBusGain(b, it) }, onPan = { vm.setBusPan(b, it) },
                onMute = { vm.setBusMute(b, !b.mute) }, onSolo = {}, onSelect = {}, showFx = false, showSolo = false,
                route = "→ " + (if (b.output == 0) "Master" else vm.busById(b.output)?.name ?: "Master"), routeActive = b.output != 0, onRoute = { routeBus = b })
        }
        Box(Modifier.width(64.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Zk.panel).border(1.dp, Zk.line, RoundedCornerShape(12.dp)).pointerInput(Unit) { detectTapGestures { vm.addBus() } }, contentAlignment = Alignment.Center) {
            Text("+ Bus", color = Zk.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Strip("Master", Zk.text, vm.masterGain, null, vm.masterMeter, false, false, false, vm.masterFx.size, vm.masterFx.any { !it.bypass }, { onInserts(null) }, stereo = vm.masterStereo, onStereo = { vm.applyMasterStereo(it) }, onGain = { vm.setMaster(it) }, onPan = {}, onMute = {}, onSolo = {}, onSelect = {})
        if (vm.tracks.isEmpty()) Text("No tracks yet", color = Zk.dim, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Strip(name: String, color: Color, gain: Float, pan: Float?, meter: Float, mute: Boolean, solo: Boolean, selected: Boolean, fxCount: Int, fxOn: Boolean, onFx: () -> Unit, stereo: Float, onStereo: (Float) -> Unit,
                  onGain: (Float) -> Unit, onPan: (Float) -> Unit, onMute: () -> Unit, onSolo: () -> Unit, onSelect: () -> Unit,
                  route: String? = null, routeActive: Boolean = false, onRoute: () -> Unit = {}, showFx: Boolean = true, showSolo: Boolean = true) {
    // While the volume fader is used it grows over the whole height of the strip, and shrinks back when it is released.
    var faderActive by remember { mutableStateOf(false) }
    val grow by androidx.compose.animation.core.animateFloatAsState(if (faderActive) 1f else 0f, androidx.compose.animation.core.tween(if (faderActive) 240 else 360), label = "fader")
    var phTop by remember { mutableFloatStateOf(0f) }; var phH by remember { mutableFloatStateOf(0f) }; var boxH by remember { mutableFloatStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current; val edge = with(density) { 8.dp.toPx() }
    Box(Modifier.width(92.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Zk.panel).border(1.dp, if (selected) color else Zk.line, RoundedCornerShape(12.dp))
        .pointerInput(Unit) { detectTapGestures { onSelect() } }.androidx_onSize { boxH = it }) {
        Column(Modifier.fillMaxSize().padding(8.dp).graphicsLayer { alpha = 1f - 0.75f * grow }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showFx) LabelButton(if (fxCount == 0) "FX" else "FX $fxCount", fxOn, color, w = 60.dp, h = 26.dp, onClick = onFx) else Spacer(Modifier.height(26.dp))
            if (route != null) Box(Modifier.padding(top = 3.dp).width(76.dp).height(22.dp).clip(RoundedCornerShape(6.dp)).background(if (routeActive) color.copy(alpha = .28f) else Zk.panel2).pointerInput(Unit) { detectTapGestures { onRoute() } }, contentAlignment = Alignment.Center) {
                Text(route, color = if (routeActive) color else Zk.dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (pan != null) {
                Slider(pan, onPan, valueRange = -1f..1f, modifier = Modifier.height(28.dp).doubleTapReset { onPan(0f) }, colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = Zk.line, inactiveTrackColor = Zk.line))
                Text(if (pan == 0f) "C" else if (pan < 0) "L${(-pan * 100).toInt()}" else "R${(pan * 100).toInt()}", color = Zk.dim, fontSize = 10.sp)
            } else Spacer(Modifier.height(28.dp))
            StereoWheel(stereo, color, onStereo)
            // the place of the fader at rest: the fader itself is drawn on top (below) so that it can grow over the strip
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp).androidx_onPlaced { top, h -> phTop = top; phH = h })
            Text(gainToDb(gain) + " dB", color = Zk.dim, fontSize = 10.sp, modifier = Modifier.graphicsLayer { alpha = 1f - grow })
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { LabelButton("M", mute, Zk.warn, onClick = onMute); if (showSolo) LabelButton("S", solo, Zk.accent, onClick = onSolo) }
        }
        val top = phTop + (edge - phTop) * grow; val bottom = (phTop + phH) + ((boxH - edge) - (phTop + phH)) * grow
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(edge.toInt(), top.toInt()) }.width(76.dp).height(with(density) { (bottom - top).coerceAtLeast(1f).toDp() })) {
            // the bigger readout takes the bottom of the grown fader; the fader gives up that room progressively
            Row(Modifier.fillMaxSize().padding(bottom = (26f * grow).dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Fader(gainToFader(gain), color, onChange = { onGain(faderToGain(it)) }, Modifier.weight(1f).fillMaxHeight(), onActive = { faderActive = it }, onReset = { onGain(1f) })   // double tap = 0 dB
                Meter(meter, Modifier.width(14.dp).fillMaxHeight())
            }
            Text(gainToDb(gain) + " dB", color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = grow })
        }
    }
}

private fun Modifier.androidx_onSize(f: (Float) -> Unit): Modifier = this.onSizeChanged { f(it.height.toFloat()) }
private fun Modifier.androidx_onPlaced(f: (Float, Float) -> Unit): Modifier = this.onGloballyPositioned { f(it.positionInParent().y, it.size.height.toFloat()) }

@Composable
fun Fader(value: Float, color: Color, onChange: (Float) -> Unit, modifier: Modifier, onActive: ((Boolean) -> Unit)? = null, onReset: (() -> Unit)? = null) {
    var rootY by remember { mutableFloatStateOf(0f) }
    val gestures = if (onActive == null) Modifier.pointerInput(Unit) { detectTapGestures { onChange(1f - (it.y / size.height).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectDragGestures { ch, _ -> ch.consume(); onChange(1f - (ch.position.y / size.height).coerceIn(0f, 1f)) } }
    else Modifier.pointerInput(Unit) {
        // A touch sets the value where it lands; moving the finger then changes it by the distance moved (measured on the screen: the fader grows while it is used).
        // The double tap is detected here, in screen coordinates: the fader moves and grows while it is touched, so its own coordinates cannot be compared between two taps.
        var lastTapAt = 0L; var lastTapY = 0f
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onActive(true)
            val t0 = System.currentTimeMillis()
            var v = (1f - down.position.y / size.height).coerceIn(0f, 1f); onChange(v)
            var last = rootY + down.position.y; val startY = last; var moved = 0f
            try {
                while (true) {
                    val ev = awaitPointerEvent(); val ch = ev.changes.firstOrNull() ?: break
                    if (!ch.pressed) break
                    val now = rootY + ch.position.y; val dy = now - last; last = now; moved = Math.max(moved, Math.abs(now - startY))
                    if (dy != 0f) { v = (v - dy / size.height).coerceIn(0f, 1f); onChange(v) }
                    ch.consume()
                }
            } finally { onActive(false) }
            val t1 = System.currentTimeMillis()
            if (moved < viewConfiguration.touchSlop && t1 - t0 < 350) {
                if (onReset != null && t1 - lastTapAt < 400 && Math.abs(startY - lastTapY) < 64.dp.toPx()) { onReset(); lastTapAt = 0L } else { lastTapAt = t1; lastTapY = startY }
            }
        }
    }
    Canvas(modifier.onGloballyPositioned { rootY = it.positionInRoot().y }.then(gestures)) {
        val cx = size.width / 2
        drawRoundRect(Zk.line, Offset(cx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height), CornerRadius(2.dp.toPx()))
        val unity = size.height * (1f - 0.75f)
        drawLine(Zk.dim, Offset(cx - 10.dp.toPx(), unity), Offset(cx + 10.dp.toPx(), unity), 1.dp.toPx())
        val y = size.height * (1f - value)
        drawRoundRect(color, Offset(cx - 14.dp.toPx(), y - 7.dp.toPx()), Size(28.dp.toPx(), 14.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawLine(Color(0xFF00201B), Offset(cx - 9.dp.toPx(), y), Offset(cx + 9.dp.toPx(), y), 2.dp.toPx())
    }
}

@Composable
fun Meter(level: Float, modifier: Modifier) {
    Canvas(modifier) {
        drawRoundRect(Zk.bg, Offset.Zero, size, CornerRadius(3.dp.toPx()))
        val db = if (level < 0.0005f) -60f else 20f * kotlin.math.log10(level)
        val frac = ((db + 60f) / 66f).coerceIn(0f, 1f)  // -60 dB .. +6 dB
        val h = size.height * frac
        drawRoundRect(Brush.verticalGradient(listOf(Zk.danger, Zk.warn, Zk.accent), startY = 0f, endY = size.height), Offset(0f, size.height - h), Size(size.width, h), CornerRadius(3.dp.toPx()))
    }
}

/** Stereo separation wheel (as in FL Studio's mixer): turn left to widen the stereo image, right to narrow it towards mono, double tap to reset. */
@Composable
private fun StereoWheel(value: Float, color: Color, onChange: (Float) -> Unit) {
    val cur by rememberUpdatedState(value)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 2.dp)) {
        Canvas(Modifier.size(34.dp).pointerInput(Unit) {
            var acc = 0f
            detectDragGestures(onDragStart = { acc = cur }) { change, drag -> change.consume(); acc = (acc - drag.y / 120.dp.toPx() + drag.x / 160.dp.toPx()).coerceIn(-1f, 1f); onChange(if (Math.abs(acc) < 0.02f) 0f else acc) }
        }.doubleTapReset { onChange(0f) }) {
            val w = size.minDimension; val stroke = w * 0.12f; val r = (w - stroke) / 2f; val c = Offset(size.width / 2f, size.height / 2f)
            val tl = Offset(c.x - r, c.y - r); val sz = Size(2 * r, 2 * r)
            drawArc(Zk.line, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            // the arc starts at the middle (top) and fills towards the side the wheel is turned to
            val v = value.coerceIn(-1f, 1f); if (Math.abs(v) > 0.01f) drawArc(color, 270f, 135f * v, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val a = Math.toRadians((270f + 135f * v).toDouble())
            drawLine(Zk.text, Offset(c.x + (r * 0.2f) * Math.cos(a).toFloat(), c.y + (r * 0.2f) * Math.sin(a).toFloat()), Offset(c.x + (r * 0.8f) * Math.cos(a).toFloat(), c.y + (r * 0.8f) * Math.sin(a).toFloat()), stroke * 0.8f, StrokeCap.Round)
        }
        Text(if (value == 0f) "Stereo" else if (value < 0) "Wide ${(-value * 100).toInt()}%" else "Mono ${(value * 100).toInt()}%", color = Zk.dim, fontSize = 9.sp, maxLines = 1)
    }
}
