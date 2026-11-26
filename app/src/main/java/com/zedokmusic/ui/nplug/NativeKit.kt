package com.zedokmusic.ui.nplug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.engine.NativeEngine
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.ParamM
import com.zedokmusic.model.TrackM
import com.zedokmusic.ui.Zk
import com.zedokmusic.ui.doubleTapReset
import kotlinx.coroutines.delay

/**
 * Live view of one built-in plugin's parameters for its custom screen. Values are polled from the engine a few times a second
 * (automation, presets and A/B change them behind the controls' back); only the entries that changed trigger a recomposition.
 */
class PluginState(val vm: DawViewModel, val t: TrackM) {
    val params: List<ParamM> = vm.loadParams(t)
    private val byName = params.associateBy { it.name }
    val values = mutableStateListOf<Float>().also { l -> params.forEach { l.add(vm.paramValue(t, it.index)) } }
    val texts = mutableStateListOf<String>().also { l -> params.forEach { l.add(vm.paramText(t, it.index)) } }
    private val labelCache = HashMap<String, List<String>>()
    fun index(name: String): Int = byName[name]?.index ?: -1
    fun v(name: String): Float { val i = index(name); return if (i >= 0) values[i] else 0f }
    fun text(name: String): String { val i = index(name); return if (i >= 0) texts[i] else "" }
    fun def(name: String): Float = byName[name]?.default ?: 0f
    /** Real-unit value of a Choice parameter (its index) or a toggle (0/1). */
    fun choice(name: String): Int { val p = byName[name] ?: return 0; return Math.round(v(name) * p.steps.coerceAtLeast(1)) }
    fun on(name: String) = v(name) >= 0.5f
    fun labels(name: String): List<String> = labelCache.getOrPut(name) {
        val p = byName[name] ?: return@getOrPut emptyList()
        (0..p.steps).map { NativeEngine.paramTextAt(t.id, p.index, it / p.steps.coerceAtLeast(1).toFloat()) }
    }
    fun set(name: String, v: Float) {
        val p = byName[name] ?: return
        val nv = if (p.steps > 0) Math.round(v * p.steps) / p.steps.toFloat() else v.coerceIn(0f, 1f)
        vm.setParam(t, p.index, nv); values[p.index] = nv; texts[p.index] = vm.paramText(t, p.index)
    }
    fun setChoice(name: String, i: Int) { val p = byName[name] ?: return; set(name, i / p.steps.coerceAtLeast(1).toFloat()) }
    fun reset(name: String) { val p = byName[name] ?: return; vm.resetParam(t, p); values[p.index] = p.default; texts[p.index] = vm.paramText(t, p.index) }
    fun refresh() {
        for (p in params) {
            val nv = vm.paramValue(t, p.index)
            if (nv != values[p.index]) { values[p.index] = nv; texts[p.index] = vm.paramText(t, p.index) }
        }
    }
}

@Composable
fun rememberPluginState(vm: DawViewModel, t: TrackM): PluginState {
    val ps = remember(t.id, t.pluginState) { PluginState(vm, t) }
    LaunchedEffect(ps, vm.paramsRevision) { while (true) { ps.refresh(); delay(90) } }
    return ps
}

/** Polls plugin telemetry (waveform, spectrum, meters) while visible. */
@Composable
fun rememberTelemetry(t: TrackM, what: Int, everyMs: Long = 60): State<FloatArray> {
    val s = remember(t.id, what) { mutableStateOf(FloatArray(0)) }
    LaunchedEffect(t.id, what) { while (true) { s.value = NativeEngine.pluginTelemetry(t.id, what); delay(everyMs) } }
    return s
}

// ------------------------------------------------------------------ controls

@Composable
fun Knob(label: String, value: Float, text: String, color: Color, modifier: Modifier = Modifier, size: Dp = 54.dp, bipolar: Boolean = false,
         onChange: (Float) -> Unit, onReset: () -> Unit) {
    val cur by rememberUpdatedState(value)
    Column(modifier.width(size + 14.dp).pointerInput(Unit) {
        var acc = 0f
        detectDragGestures(onDragStart = { acc = cur }) { change, drag ->
            change.consume()
            acc = (acc - drag.y / (190.dp.toPx()) + drag.x / (380.dp.toPx())).coerceIn(0f, 1f)
            onChange(acc)
        }
    }.doubleTapReset(onReset), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size)) {
            val w = this.size.minDimension; val stroke = w * 0.11f; val r = (w - stroke) / 2f; val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val tl = Offset(c.x - r, c.y - r); val sz = Size(2 * r, 2 * r)
            drawArc(Zk.line, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val v = value.coerceIn(0f, 1f)
            if (bipolar) { val mid = 0.5f; drawArc(color, 135f + 270f * minOf(mid, v), 270f * Math.abs(v - mid), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round)) }
            else if (v > 0.002f) drawArc(color, 135f, 270f * v, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            val a = Math.toRadians((135f + 270f * v).toDouble())
            drawLine(Zk.text, Offset(c.x + (r * 0.25f) * Math.cos(a).toFloat(), c.y + (r * 0.25f) * Math.sin(a).toFloat()), Offset(c.x + (r * 0.78f) * Math.cos(a).toFloat(), c.y + (r * 0.78f) * Math.sin(a).toFloat()), stroke * 0.75f, StrokeCap.Round)
        }
        Text(text, color = Zk.text, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.Center)
        Text(label, color = Zk.dim, fontSize = 10.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
fun PK(ps: PluginState, name: String, color: Color, label: String = name, size: Dp = 54.dp, bipolar: Boolean = false, modifier: Modifier = Modifier) {
    val units = ps.params.firstOrNull { it.name == name }?.units ?: ""
    Knob(label, ps.v(name), (ps.text(name) + if (units.isNotEmpty() && units != "Hz") " $units" else if (units == "Hz" && !ps.text(name).endsWith("k")) " Hz" else if (units == "Hz") "Hz" else "").trim(), color, modifier, size, bipolar,
        onChange = { ps.set(name, it) }, onReset = { ps.reset(name) })
}

/** Segmented selector for a Choice parameter: a row of chips (wraps on small screens). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(ps: PluginState, name: String, color: Color, label: String? = null, modifier: Modifier = Modifier, short: List<String>? = null) {
    val labels = ps.labels(name); val sel = ps.choice(name)
    Column(modifier) {
        if (label != null) Text(label, color = Zk.dim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { i, l ->
                val on = i == sel
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) color else Zk.panel2).clickable { ps.setChoice(name, i) }.padding(horizontal = 9.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(short?.getOrNull(i) ?: l, color = if (on) Color(0xFF0B0D10) else Zk.dim, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Selector for choice parameters with many options: shows the value, tap to open the list. */
@Composable
fun Picker(ps: PluginState, name: String, color: Color, label: String? = null, modifier: Modifier = Modifier, width: Dp = 120.dp) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.width(width)) {
        if (label != null) Text(label, color = Zk.dim, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Zk.panel2).border(1.dp, Zk.line, RoundedCornerShape(6.dp)).clickable { open = true }.padding(horizontal = 8.dp, vertical = 8.dp)) {
            Text(ps.text(name), color = color, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (open) androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = Zk.panel2) {
            ps.labels(name).forEachIndexed { i, l -> androidx.compose.material3.DropdownMenuItem(text = { Text(l, color = if (i == ps.choice(name)) color else Zk.text, fontSize = 13.sp) }, onClick = { ps.setChoice(name, i); open = false }) }
        }
    }
}

@Composable
fun Toggle(ps: PluginState, name: String, color: Color, label: String = name, modifier: Modifier = Modifier) {
    val on = ps.on(name)
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(if (on) color else Zk.panel2).clickable { ps.set(name, if (on) 0f else 1f) }.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (on) Color(0xFF0B0D10) else Zk.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
    }
}

/** A titled group of controls. */
@Composable
fun Section(title: String, color: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Zk.panel).border(1.dp, Zk.line, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text(title.uppercase(), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Knobs(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
fun TabBar(tabs: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, s ->
            val on = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) color.copy(alpha = .2f) else Zk.panel2).clickable { onSelect(i) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(s, color = if (on) color else Zk.dim, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, softWrap = false)
            }
        }
    }
}

// ------------------------------------------------------------------ displays

/** Draws a one-cycle waveform (values -1..1). */
@Composable
fun WaveView(data: FloatArray, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0B0E))) {
        val w = size.width; val h = size.height
        drawLine(Zk.line, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        if (data.size < 2) return@Canvas
        val path = Path()
        for (i in data.indices) { val x = w * i / (data.size - 1); val y = h / 2 - data[i].coerceIn(-1f, 1f) * h * 0.42f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, color, style = Stroke(2.2f))
    }
}

/** ADSR shape from the four times (ms) and the sustain level. */
@Composable
fun EnvView(a: Float, d: Float, s: Float, r: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0B0E))) {
        val w = size.width; val h = size.height; val pad = 4f
        // time axis is compressed (square root) so that short and long times are both visible
        fun seg(ms: Float) = Math.sqrt(ms.coerceAtLeast(0.5f).toDouble()).toFloat()
        val sa = seg(a); val sd = seg(d); val sr = seg(r); val hold = (sa + sd + sr) * 0.25f
        val total = sa + sd + hold + sr
        val x1 = pad + (w - 2 * pad) * sa / total; val x2 = x1 + (w - 2 * pad) * sd / total; val x3 = x2 + (w - 2 * pad) * hold / total; val x4 = w - pad
        val top = pad + 2f; val bot = h - pad; val sy = bot - (bot - top) * s.coerceIn(0f, 1f)
        val path = Path().apply { moveTo(pad, bot); lineTo(x1, top); quadraticTo(x1 + (x2 - x1) * 0.25f, sy, x2, sy); lineTo(x3, sy); quadraticTo(x3 + (x4 - x3) * 0.2f, bot, x4, bot) }
        drawPath(path, color, style = Stroke(2.2f))
        val fill = Path().apply { addPath(path); lineTo(x4, bot); lineTo(pad, bot); close() }
        drawPath(fill, color.copy(alpha = .12f))
    }
}

@Composable
fun Header(text: String, color: Color) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
