package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import kotlin.math.roundToInt

/** The piano roll tools that open a dialog. */
enum class NoteTool(val label: String) { Quantize("Quantize…"), Humanize("Humanize…"), Strum("Strum…"), Arpeggiate("Arpeggiate…"), Riff("Riff machine…"), Lfo("Event LFO…") }

@Composable private fun ToolChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Zk.accent else Zk.panel2).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(text, color = if (on) Color(0xFF00201B) else Zk.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
@Composable private fun <T> Chips(options: List<Pair<String, T>>, value: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for ((l, v) in options) ToolChip(l, v == value) { onPick(v) } }
}
@Composable private fun Sl(label: String, text: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, color = Zk.dim, fontSize = 12.sp, modifier = Modifier.width(78.dp))
        Slider(value, onChange, Modifier.weight(1f), valueRange = range, colors = SliderDefaults.colors(thumbColor = Zk.accent, activeTrackColor = Zk.accent))
        Text(text, color = Zk.text, fontSize = 12.sp, modifier = Modifier.width(56.dp))
    }
}
@Composable private fun Sec(t: String) { Text(t, color = Zk.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) }

private val Steps = listOf("1/4" to 1f, "1/8" to 0.5f, "1/16" to 0.25f, "1/32" to 0.125f)

/**
 * The dialog of a note tool. [src] are the notes it works on (the selection, or every note of the track when nothing is selected); [onApply] gets what to
 * remove and what to add (one undo step) and a name for the history.
 */
@Composable
fun NoteToolDialog(vm: DawViewModel, tool: NoteTool, src: List<NoteM>, gridStep: Float, onApply: (remove: List<NoteM>, add: List<NoteM>, name: String) -> Unit, onDismiss: () -> Unit) {
    var step by remember { mutableFloatStateOf(if (tool == NoteTool.Arpeggiate || tool == NoteTool.Riff) 0.25f else gridStep.coerceIn(0.125f, 1f)) }
    var strength by remember { mutableFloatStateOf(100f) }; var swing by remember { mutableFloatStateOf(0f) }; var window by remember { mutableFloatStateOf(0f) }
    var qTarget by remember { mutableStateOf(NoteTools.QuantTarget.Starts) }
    var hTiming by remember { mutableFloatStateOf(0.04f) }; var hVel by remember { mutableFloatStateOf(12f) }; var hLen by remember { mutableFloatStateOf(0f) }
    var seed by remember { mutableIntStateOf((System.nanoTime() % 1000).toInt()) }
    var spread by remember { mutableFloatStateOf(0.25f) }; var tension by remember { mutableFloatStateOf(1f) }; var fromTop by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf(NoteTools.ArpPattern.Up) }; var octaves by remember { mutableIntStateOf(1) }; var gate by remember { mutableFloatStateOf(0.8f) }
    var lo by remember { mutableFloatStateOf(60f) }; var hi by remember { mutableFloatStateOf(84f) }; var bars by remember { mutableIntStateOf(2) }
    var density by remember { mutableFloatStateOf(0.7f) }; var leap by remember { mutableFloatStateOf(0.3f) }; var replace by remember { mutableStateOf(false) }
    var prop by remember { mutableIntStateOf(1) }; var shape by remember { mutableStateOf(NoteTools.LfoShape.Sine) }; var cycles by remember { mutableFloatStateOf(2f) }
    var depth by remember { mutableFloatStateOf(1f) }; var center by remember { mutableFloatStateOf(0f) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text(tool.label.removeSuffix("…")) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if (tool == NoteTool.Riff) "Writes a phrase in the project scale, at the bar of the playhead." else "Works on the selected notes (every note of the track when none is selected): ${src.size} note${if (src.size == 1) "" else "s"}.", color = Zk.dim, fontSize = 12.sp)
            when (tool) {
                NoteTool.Quantize -> {
                    Sec("GRID"); Chips(Steps + listOf("Snap" to gridStep), step) { step = it }
                    Sec("WHAT MOVES"); Chips(listOf("Starts" to NoteTools.QuantTarget.Starts, "Ends" to NoteTools.QuantTarget.Ends, "Both" to NoteTools.QuantTarget.Both), qTarget) { qTarget = it }
                    Sl("Strength", "${strength.roundToInt()} %", strength, 0f..100f) { strength = it }
                    Sl("Swing", "${swing.roundToInt()} %", swing, 0f..100f) { swing = it }
                    Sl("Window", if (window == 0f) "off" else "%.2f".format(window), window, 0f..0.5f) { window = it }
                }
                NoteTool.Humanize -> {
                    Sl("Timing", "±%.3f".format(hTiming), hTiming, 0f..0.25f) { hTiming = it }
                    Sl("Velocity", "±${hVel.roundToInt()}", hVel, 0f..40f) { hVel = it }
                    Sl("Length", "±${(hLen * 100).roundToInt()} %", hLen, 0f..0.5f) { hLen = it }
                    Row(Modifier.padding(top = 6.dp)) { ToolChip("New random ($seed)", false) { seed = (System.nanoTime() % 100000).toInt() } }
                }
                NoteTool.Strum -> {
                    Text("Chords (notes that start together) are spread out one note after the other; the ends stay.", color = Zk.dim, fontSize = 11.sp)
                    Sl("Spread", "%.2f beat".format(spread), spread, 0.05f..2f) { spread = it }
                    Sl("Tension", "%.1f".format(tension), tension, 0.4f..2.5f) { tension = it }
                    Sec("STARTS FROM"); Chips(listOf("Lowest note" to false, "Highest note" to true), fromTop) { fromTop = it }
                }
                NoteTool.Arpeggiate -> {
                    Text("Each chord becomes a run of single notes over its length.", color = Zk.dim, fontSize = 11.sp)
                    Sec("PATTERN"); Chips(NoteTools.ArpPattern.values().map { it.name to it }, pattern) { pattern = it }
                    Sec("RATE"); Chips(Steps, step) { step = it }
                    Sec("OCTAVES"); Chips((1..4).map { "$it" to it }, octaves) { octaves = it }
                    Sl("Gate", "${(gate * 100).roundToInt()} %", gate, 0.1f..1f) { gate = it }
                    if (pattern == NoteTools.ArpPattern.Random) Row(Modifier.padding(top = 6.dp)) { ToolChip("New random ($seed)", false) { seed = (System.nanoTime() % 100000).toInt() } }
                }
                NoteTool.Riff -> {
                    Text("Scale: ${Theory.label(vm.scaleRoot, vm.scaleType)}", color = Zk.text, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    Sec("LENGTH"); Chips(listOf(1, 2, 4).map { "$it bar${if (it > 1) "s" else ""}" to it }, bars) { bars = it }
                    Sec("RATE"); Chips(Steps, step) { step = it }
                    Sl("Lowest", Theory.name(lo.roundToInt()), lo, 24f..100f) { lo = it; if (hi < lo + 5) hi = lo + 5 }
                    Sl("Highest", Theory.name(hi.roundToInt()), hi, 30f..108f) { hi = it; if (lo > hi - 5) lo = hi - 5 }
                    Sl("Density", "${(density * 100).roundToInt()} %", density, 0.1f..1f) { density = it }
                    Sl("Leaps", "${(leap * 100).roundToInt()} %", leap, 0f..1f) { leap = it }
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToolChip("New random ($seed)", false) { seed = (System.nanoTime() % 100000).toInt() }
                        ToolChip("Replace the bars", replace) { replace = !replace }
                    }
                }
                NoteTool.Lfo -> {
                    Sec("PROPERTY"); Chips(NoteProps.mapIndexed { i, d -> d.name to i }, prop) { prop = it; center = if (it == 0) 64f else 0f }
                    Sec("WAVE"); Chips(NoteTools.LfoShape.values().map { it.name to it }, shape) { shape = it }
                    Sl("Cycles", "%.1f".format(cycles), cycles, 0.25f..8f) { cycles = it }
                    Sl("Depth", "${(depth * 100).roundToInt()} %", depth, 0f..1f) { depth = it }
                    val d = NoteProps[prop]
                    Sl("Centre", d.unit(center.roundToInt()), center, d.lo.toFloat()..d.hi.toFloat()) { center = it }
                    if (shape == NoteTools.LfoShape.Random) Row(Modifier.padding(top = 6.dp)) { ToolChip("New random ($seed)", false) { seed = (System.nanoTime() % 100000).toInt() } }
                }
            }
        }
    }, confirmButton = {
        TextButton({
            when (tool) {
                NoteTool.Quantize -> { val r = NoteTools.quantize(src, step, strength / 100f, qTarget, swing / 100f, window); onApply(src, r, "Quantize") }
                NoteTool.Humanize -> onApply(src, NoteTools.humanize(src, hTiming, hVel.roundToInt(), hLen, seed), "Humanize")
                NoteTool.Strum -> onApply(src, NoteTools.strum(src, spread, fromTop, tension), "Strum")
                NoteTool.Arpeggiate -> onApply(src, NoteTools.arpeggiate(src, pattern, step, octaves, gate, seed), "Arpeggiate")
                NoteTool.Riff -> {
                    val start = kotlin.math.floor(vm.playhead / 4f) * 4f
                    val made = NoteTools.riff({ Theory.inScale(it, vm.scaleRoot, vm.scaleType) }, lo.roundToInt(), hi.roundToInt(), start, bars, step, density, leap, seed)
                    val gone = if (replace) src.filter { it.start < start + bars * 4f && it.start + it.length > start } else emptyList()
                    onApply(gone, made, "Riff")
                }
                NoteTool.Lfo -> {
                    val d = NoteProps[prop]; val vals = NoteTools.lfo(src, shape, cycles, depth, center, d.lo, d.hi, seed)
                    onApply(src, src.map { n -> n.withProp(prop, vals[n] ?: n.prop(prop)) }, "Event LFO")
                }
            }
            onDismiss()
        }, enabled = src.isNotEmpty() || tool == NoteTool.Riff) { Text("Apply") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
