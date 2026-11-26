package com.zedokmusic.ui.nplug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.ui.Zk

private val EQ = Color(0xFFFFC857)
private val BAND = listOf(Color(0xFFFF6B6B), Color(0xFFFFA94D), Color(0xFFFFD43B), Color(0xFF69DB7C), Color(0xFF4DABF7), Color(0xFFB197FC))

@Composable
fun EqCompScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    var band by remember { mutableIntStateOf(2) }
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("EQ", "Dynamics", "Main"), tab, EQ) { tab = it }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> EqTab(ps, band) { band = it }
                1 -> DynTab(ps)
                else -> MainTab(ps)
            }
        }
    }
}

private fun fToX(f: Float, w: Float) = (Math.log((f / 20f).toDouble()) / Math.log(1000.0)).toFloat() * w
private fun xToF(x: Float, w: Float) = 20f * Math.pow(1000.0, (x / w).toDouble().coerceIn(0.0, 1.0)).toFloat()

@Composable
private fun EqTab(ps: PluginState, band: Int, onBand: (Int) -> Unit) {
    val spectrum by rememberTelemetry(ps.t, 0, 60)
    val curve by rememberTelemetry(ps.t, 2, 80)
    val bandCurve by rememberTelemetry(ps.t, 10 + band, 80)
    // per-band values used by the handles
    val state = rememberUpdatedState(ps)
    Canvas(Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0B0E)).pointerInput(Unit) {
        var drag = -1
        detectDragGestures(onDragStart = { o ->
            val w = size.width.toFloat(); val h = size.height.toFloat(); var best = -1; var bd = 56.dp.toPx()
            for (b in 0 until 6) {
                val n = "B${b + 1} "; val f = state.value; if (!f.on(n + "On") && b != band) continue
                val freq = realFreq(f, n); val g = if (hasGain(f, n)) realGain(f, n) else 0f
                val d = (Offset(fToX(freq, w), h / 2 - g / 18f * h / 2) - o).getDistance(); if (d < bd) { bd = d; best = b }
            }
            drag = best; if (best >= 0) { onBand(best); val f = state.value; if (!f.on("B${best + 1} On")) f.set("B${best + 1} On", 1f) }
        }, onDragEnd = { drag = -1 }, onDragCancel = { drag = -1 }) { change, _ ->
            if (drag < 0) return@detectDragGestures
            change.consume(); val w = size.width.toFloat(); val h = size.height.toFloat(); val f = state.value; val n = "B${drag + 1} "
            val freq = xToF(change.position.x, w).coerceIn(20f, 20000f)
            f.set(n + "Freq", (Math.log((freq / 20f).toDouble()) / Math.log(1000.0)).toFloat())
            if (hasGain(f, n)) f.set(n + "Gain", ((0.5f - (change.position.y / h - 0.5f) * 1f).coerceIn(0f, 1f)).let { 1f - (change.position.y / h).coerceIn(0f, 1f) })
        }
    }.pointerInput(Unit) { detectTapGestures { o ->
        val w = size.width.toFloat(); val h = size.height.toFloat(); val f = state.value; var best = -1; var bd = 56.dp.toPx()
        for (b in 0 until 6) { val n = "B${b + 1} "; val d = (Offset(fToX(realFreq(f, n), w), h / 2 - (if (hasGain(f, n)) realGain(f, n) else 0f) / 18f * h / 2) - o).getDistance(); if (d < bd) { bd = d; best = b } }
        if (best >= 0) onBand(best)
    } }) {
        val w = size.width; val h = size.height
        // grid
        for (fr in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f)) drawLine(Zk.line, Offset(fToX(fr, w), 0f), Offset(fToX(fr, w), h), 1f)
        for (db in listOf(-12f, -6f, 0f, 6f, 12f)) drawLine(if (db == 0f) Zk.dim.copy(alpha = .5f) else Zk.line, Offset(0f, h / 2 - db / 18f * h / 2), Offset(w, h / 2 - db / 18f * h / 2), 1f)
        // spectrum (dB -100..0 mapped on the full height)
        if (spectrum.size >= 2) {
            val p = Path(); p.moveTo(0f, h)
            for (i in spectrum.indices) { val x = w * i / (spectrum.size - 1); val y = h - ((spectrum[i] + 100f) / 100f).coerceIn(0f, 1f) * h; p.lineTo(x, y) }
            p.lineTo(w, h); p.close(); drawPath(p, EQ.copy(alpha = .16f))
        }
        // selected band's own curve and the total curve
        if (bandCurve.size >= 2) { val p = Path(); for (i in bandCurve.indices) { val x = w * i / (bandCurve.size - 1); val y = h / 2 - bandCurve[i] / 18f * h / 2; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }; drawPath(p, BAND[band].copy(alpha = .5f), style = Stroke(2f)) }
        if (curve.size >= 2) { val p = Path(); for (i in curve.indices) { val x = w * i / (curve.size - 1); val y = (h / 2 - curve[i].coerceIn(-18f, 18f) / 18f * h / 2); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }; drawPath(p, EQ, style = Stroke(3.2f)) }
        // handles
        for (b in 0 until 6) {
            val n = "B${b + 1} "; val on = ps.on(n + "On"); val x = fToX(realFreq(ps, n), w); val y = h / 2 - (if (hasGain(ps, n)) realGain(ps, n) else 0f) / 18f * h / 2
            val sel = b == band
            drawCircle(if (on) BAND[b] else Color.Transparent, if (sel) 15f else 12f, Offset(x, y)); drawCircle(BAND[b], if (sel) 15f else 12f, Offset(x, y), style = Stroke(if (sel) 3f else 2f))
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in 0 until 6) {
            val on = ps.on("B${b + 1} On"); val sel = b == band
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) BAND[b].copy(alpha = .25f) else Zk.panel2).pointerInput(b) { detectTapGestures(onTap = { onBand(b) }, onLongPress = { ps.set("B${b + 1} On", if (on) 0f else 1f) }) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text("${b + 1}", color = if (on) BAND[b] else Zk.dim, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }
    val n = "B${band + 1} "
    Section("Band ${band + 1}", BAND[band]) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, n + "On", BAND[band], "On"); Picker(ps, n + "Type", BAND[band], width = 130.dp) }
        Spacer(Modifier.height(6.dp))
        Knobs {
            PK(ps, n + "Freq", BAND[band], "Freq", 60.dp)
            if (hasGain(ps, n)) PK(ps, n + "Gain", BAND[band], "Gain", 60.dp, bipolar = true)
            if (hasQ(ps, n)) PK(ps, n + "Q", BAND[band], "Q", 60.dp)
        }
        if (isCut(ps, n)) { Spacer(Modifier.height(4.dp)); Chips(ps, n + "Slope", BAND[band], "Slope") }
    }
    Section("Processing", EQ) { Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, "EQ On", EQ, "EQ on"); Chips(ps, "EQ Mode", EQ) } }
    androidx.compose.material3.Text("Drag a dot to set frequency and gain. Long-press a number to switch a band on or off.", color = Zk.dim, fontSize = 11.sp)
}

private fun realFreq(ps: PluginState, n: String): Float = 20f * Math.pow(1000.0, ps.v(n + "Freq").toDouble()).toFloat()
private fun realGain(ps: PluginState, n: String): Float = -18f + 36f * ps.v(n + "Gain")
private fun typeOf(ps: PluginState, n: String) = ps.choice(n + "Type")      // 0 bell 1 low shelf 2 high shelf 3 low cut 4 high cut 5 notch 6 band pass 7 tilt
private fun hasGain(ps: PluginState, n: String) = typeOf(ps, n).let { it == 0 || it == 1 || it == 2 || it == 7 }
private fun hasQ(ps: PluginState, n: String) = typeOf(ps, n).let { it == 0 || it == 5 || it == 6 || it == 1 || it == 2 }
private fun isCut(ps: PluginState, n: String) = typeOf(ps, n).let { it == 3 || it == 4 }

@Composable
private fun Meter(label: String, db: Float, color: Color, modifier: Modifier = Modifier, reverse: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.material3.Text(label, color = Zk.dim, fontSize = 10.sp)
        Canvas(Modifier.width(18.dp).height(110.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A0B0E))) {
            val f = if (reverse) (db / 24f).coerceIn(0f, 1f) else ((db + 60f) / 60f).coerceIn(0f, 1f)
            if (reverse) drawRect(color, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, size.height * f)) else drawRect(color, Offset(0f, size.height * (1f - f)), androidx.compose.ui.geometry.Size(size.width, size.height * f))
        }
        androidx.compose.material3.Text(String.format("%.0f", db), color = Zk.dim, fontSize = 10.sp)
    }
}

@Composable
private fun DynTab(ps: PluginState) {
    val meters by rememberTelemetry(ps.t, 1, 50)
    Section("Compressor", EQ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                CompCurve(ps)
                Spacer(Modifier.height(6.dp)); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Toggle(ps, "Comp On", EQ, "Comp on"); Toggle(ps, "Auto Release", EQ, "Auto rel."); Toggle(ps, "Auto Makeup", EQ, "Auto makeup") }
            }
            if (meters.size >= 4) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Meter("IN", meters[0], Color(0xFF69DB7C)); Meter("GR", meters[2], Zk.danger, reverse = true); Meter("OUT", meters[1], Color(0xFF4DABF7)) }
        }
        Spacer(Modifier.height(6.dp))
        Knobs { PK(ps, "Threshold", EQ, "Threshold", 58.dp); PK(ps, "Ratio", EQ, "Ratio", 58.dp); PK(ps, "Knee", EQ, "Knee"); PK(ps, "Attack", EQ, "Attack"); PK(ps, "Release", EQ, "Release"); PK(ps, "Makeup", EQ, "Makeup", bipolar = true); PK(ps, "Comp Mix", EQ, "Mix") }
        Spacer(Modifier.height(4.dp)); Chips(ps, "Detector", EQ, "Detector")
        Knobs { PK(ps, "Lookahead", EQ, "Lookahead"); PK(ps, "Sidechain HP", EQ, "SC high-pass"); PK(ps, "Stereo Link", EQ, "Link") }
    }
    Section("Limiter", Zk.danger) { Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, "Limiter On", Zk.danger, "Limiter on"); PK(ps, "Ceiling", Zk.danger, "Ceiling"); PK(ps, "Limiter Release", Zk.danger, "Release") } }
}

/** Static compression curve (threshold, ratio, knee) with the threshold marked. */
@Composable
private fun CompCurve(ps: PluginState) {
    val thr = -60f + 60f * ps.v("Threshold"); val ratio = 1f + 19f * Math.pow(ps.v("Ratio").toDouble(), 2.0).toFloat(); val knee = 24f * ps.v("Knee")
    Canvas(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0B0E))) {
        val w = size.width; val h = size.height
        fun x(db: Float) = (db + 60f) / 60f * w
        fun y(db: Float) = h - (db + 60f) / 60f * h
        for (g in listOf(-48f, -36f, -24f, -12f)) { drawLine(Zk.line, Offset(x(g), 0f), Offset(x(g), h), 1f); drawLine(Zk.line, Offset(0f, y(g)), Offset(w, y(g)), 1f) }
        drawLine(Zk.line, Offset(x(-60f), y(-60f)), Offset(x(0f), y(0f)), 1.5f)
        val p = Path()
        for (i in 0..120) {
            val inDb = -60f + i * 0.5f; val over = inDb - thr
            val out = if (knee > 0.01f && 2f * over > -knee && 2f * over < knee) { val t = over + knee / 2f; inDb + (1f / ratio - 1f) * t * t / (2f * knee) } else if (over > 0f) thr + over / ratio else inDb
            if (i == 0) p.moveTo(x(inDb), y(out)) else p.lineTo(x(inDb), y(out))
        }
        drawPath(p, EQ, style = Stroke(3f))
        drawCircle(EQ, 6f, Offset(x(thr), y(thr)))
    }
}

@Composable
private fun MainTab(ps: PluginState) {
    val meters by rememberTelemetry(ps.t, 1, 50)
    Section("Level", EQ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Knobs(Modifier.weight(1f)) { PK(ps, "Input Gain", EQ, "Input", 60.dp, bipolar = true); PK(ps, "Output Gain", EQ, "Output", 60.dp, bipolar = true) }
            if (meters.size >= 2) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Meter("IN", meters[0], Color(0xFF69DB7C)); Meter("OUT", meters[1], Color(0xFF4DABF7)) }
        }
        Spacer(Modifier.height(6.dp)); Chips(ps, "Order", EQ, "Signal order")
    }
}
