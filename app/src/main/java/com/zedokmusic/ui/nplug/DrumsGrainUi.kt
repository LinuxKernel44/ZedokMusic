package com.zedokmusic.ui.nplug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.ui.LabelButton
import com.zedokmusic.ui.SamplePickerDialog
import com.zedokmusic.ui.Zk

private val DR = Color(0xFFFF9F43)
private val GR = Color(0xFF7BE07B)
private val GR2 = Color(0xFF4DD0E1)
private val GR3 = Color(0xFFFFD166)

// ------------------------------------------------------------------ Drums

private fun padColor(model: Int): Color = when (model) {
    0, 1, 2 -> Color(0xFFFF6B6B)            // kicks
    3, 4, 5, 6 -> Color(0xFFFFA94D)         // snares, clap, rim
    7, 8, 9, 13 -> Color(0xFFFFD43B)        // hats, cymbal
    10 -> Color(0xFF69DB7C)                  // tom
    else -> Color(0xFF4DABF7)                // percussion
}

@Composable
fun DrumsScreen(ps: PluginState) {
    var pad by remember { mutableIntStateOf(0) }
    val light by rememberTelemetry(ps.t, 0, 40)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 640.dp
        val grid: @Composable (Modifier) -> Unit = { m -> PadGrid(ps, pad, light, m) { pad = it } }
        if (wide) Row(Modifier.fillMaxSize()) {
            grid(Modifier.weight(0.9f).fillMaxHeight().padding(6.dp))
            Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { PadEditor(ps, pad); MasterSection(ps) }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            grid(Modifier.fillMaxWidth().padding(8.dp))
            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { PadEditor(ps, pad); MasterSection(ps) }
        }
    }
}

@Composable
private fun PadGrid(ps: PluginState, selected: Int, light: FloatArray, modifier: Modifier, onSelect: (Int) -> Unit) {
    val fill = modifier.let { true }   // in a bounded-height (landscape) layout the rows share the height; in a scrolling one they keep a fixed pad shape
    BoxWithConstraints(modifier) {
    val bounded = constraints.hasBoundedHeight
    Column(Modifier.fillMaxSize().let { if (bounded) it else it.wrapContentHeight() }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 3 downTo 0) {
            Row(Modifier.fillMaxWidth().let { if (bounded) it.weight(1f) else it }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until 4) {
                    val i = row * 4 + col; val model = ps.choice("P${i + 1} Type"); val c = padColor(model); val glow = light.getOrElse(i) { 0f }
                    val vm = ps.vm; val t = ps.t
                    Box(Modifier.weight(1f).let { if (bounded) it.fillMaxHeight() else it.aspectRatio(1.25f) }.clip(RoundedCornerShape(10.dp))
                        .background(androidx.compose.ui.graphics.lerp(Zk.panel2, c, (0.12f + 0.7f * glow).coerceIn(0f, 1f)))
                        .border(if (i == selected) 2.dp else 1.dp, if (i == selected) c else Zk.line, RoundedCornerShape(10.dp))
                        .pointerInput(i) { detectTapGestures(onPress = { onSelect(i); vm.liveNote(t, true, 36 + i); tryAwaitRelease(); vm.liveNote(t, false, 36 + i) }) }, contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Text(ps.text("P${i + 1} Type"), color = Zk.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            androidx.compose.material3.Text("${i + 1}", color = Zk.dim, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
    }
    if (fill) Unit
}

@Composable
private fun PadEditor(ps: PluginState, pad: Int) {
    val n = "P${pad + 1} "; val c = padColor(ps.choice(n + "Type"))
    Section("Pad ${pad + 1} · note ${36 + pad}", c) {
        Picker(ps, n + "Type", c, "Sound", width = 170.dp)
        Spacer(Modifier.height(8.dp))
        Knobs { PK(ps, n + "Tune", c, "Tune", bipolar = true); PK(ps, n + "Decay", c, "Decay"); PK(ps, n + "Tone", c, "Tone"); PK(ps, n + "Punch", c, "Punch"); PK(ps, n + "Drive", c, "Drive"); PK(ps, n + "Level", c, "Level"); PK(ps, n + "Pan", c, "Pan", bipolar = true) }
        Spacer(Modifier.height(4.dp)); Chips(ps, n + "Choke", c, "Choke group (hats cut each other)")
    }
}

@Composable
private fun MasterSection(ps: PluginState) {
    Section("Kit", DR) { Knobs { PK(ps, "Volume", DR, "Volume", bipolar = false); PK(ps, "Glue", DR, "Glue"); PK(ps, "Saturation", DR, "Saturation"); PK(ps, "Kit Tone", DR, "Tone", bipolar = true); PK(ps, "Room", DR, "Room"); PK(ps, "Room Size", DR, "Room size"); PK(ps, "Velocity Sens", DR, "Velocity") } }
}

// ------------------------------------------------------------------ Grain

@Composable
fun GrainScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val vm = ps.vm; val t = ps.t
    if (picker) SamplePickerDialog(vm.projectSamples(), vm.sharedSamples(), vm.projectSamplesDir()?.path ?: "", { picker = false }, { f -> vm.setPluginState(t, "sample", f.absolutePath); ps.setChoice("Source", 0); picker = false }, { picker = false })
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Cloud", "Voice", "Sound", "Mod", "FX"), tab, GR) { tab = it }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> Split({ Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudView(ps)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Picker(ps, "Source", GR, "Source", width = 150.dp)
                        LabelButton("Load sample…", false, w = 112.dp, h = 36.dp) { picker = true }
                        Toggle(ps, "Freeze", GR2, "Freeze")
                    }
                    val sample = t.states["sample"]
                    androidx.compose.material3.Text(if (ps.choice("Source") == 0) (if (sample.isNullOrEmpty()) "No sample yet: tap Load sample… (WAV files of your project or the shared library)." else "Sample: " + sample.substringAfterLast('/')) else "Built-in texture. Choose Sample as the source to play your own audio.", color = Zk.dim, fontSize = 11.sp)
                    } }, { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Section("Grains", GR) {
                        Knobs { PK(ps, "Position", GR, "Position", 60.dp); PK(ps, "Scan", GR, "Scan", 60.dp, bipolar = true); PK(ps, "Size", GR, "Size", 60.dp); PK(ps, "Density", GR, "Density", 60.dp); PK(ps, "Pitch", GR, "Pitch", bipolar = true) }
                    }
                    Section("Randomness", GR2) { Knobs { PK(ps, "Position Spread", GR2, "Position"); PK(ps, "Pitch Jitter", GR2, "Pitch"); PK(ps, "Pan Spread", GR2, "Pan"); PK(ps, "Timing Jitter", GR2, "Timing"); PK(ps, "Level Jitter", GR2, "Level") } }
                    Section("Grain shape", GR3) { Chips(ps, "Direction", GR3); Spacer(Modifier.height(6.dp)); Chips(ps, "Shape", GR3); Spacer(Modifier.height(6.dp)); Knobs { PK(ps, "Skew", GR3, "Skew") } }
                    } })
                1 -> {
                    Section("Playing", GR) {
                        Chips(ps, "Play Mode", GR, "Mode: polyphonic, monophonic or legato")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Picker(ps, "Polyphony", GR, "Voices", width = 90.dp); Chips(ps, "Quality", GR, "Quality") }
                        Spacer(Modifier.height(6.dp))
                        Knobs { PK(ps, "Volume", GR, "Volume"); PK(ps, "Glide", GR, "Glide"); PK(ps, "Pitch Track", GR, "Pitch track"); PK(ps, "Root Note", GR, "Root note"); PK(ps, "Bend Range", GR, "Bend range"); PK(ps, "Pitch Bend", GR, "Bend", bipolar = true); PK(ps, "Mod Wheel", GR, "Mod wheel") }
                    }
                }
                2 -> Split({ AutoGroup(ps, "Filter", GR2) }, { EnvBlock(ps, "Amp", "Amp", GR) }, { EnvBlock(ps, "Env2", "Envelope 2 (filter)", GR2) })
                3 -> {
                    Split({ AutoGroup(ps, "LFO 1", GR) }, { AutoGroup(ps, "LFO 2", GR2) })
                    Section("Modulation matrix", GR3) {
                        for (m in 1..6) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.Text("$m", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.width(14.dp))
                            Picker(ps, "Mod $m Source", GR3, width = 110.dp); Picker(ps, "Mod $m Target", GR, width = 150.dp); PK(ps, "Mod $m Amount", GR2, "", 40.dp, bipolar = true)
                        }
                    }
                }
                else -> AutoGroup(ps, "Effects", GR)
            }
        }
    }
}

@Composable
private fun EnvBlock(ps: PluginState, pre: String, title: String, color: Color) {
    Section(title, color) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EnvView(ps.text("$pre Attack").toFloatOrNull() ?: 5f, ps.text("$pre Decay").toFloatOrNull() ?: 200f, ps.v("$pre Sustain"), ps.text("$pre Release").toFloatOrNull() ?: 200f, color, Modifier.width(110.dp).height(64.dp))
            Knobs { PK(ps, "$pre Attack", color, "A"); PK(ps, "$pre Decay", color, "D"); PK(ps, "$pre Sustain", color, "S"); PK(ps, "$pre Release", color, "R") }
        }
    }
}

/** The source waveform with the live grains drawn over it. Touch or drag to move the playback position (scrub); it also works while Freeze is on. */
@Composable
private fun CloudView(ps: PluginState) {
    val over by rememberTelemetry(ps.t, 0, 250)
    val grains by rememberTelemetry(ps.t, 1, 50)
    val pos = ps.v("Position")
    val state = rememberUpdatedState(ps)
    val short = com.zedokmusic.ui.LocalShort.current
    Canvas(Modifier.fillMaxWidth().height(if (short) 100.dp else 150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0B0E)).pointerInput(Unit) {
        detectDragGestures(onDragStart = { o -> state.value.set("Position", (o.x / size.width).coerceIn(0f, 1f)) }) { change, _ -> change.consume(); state.value.set("Position", (change.position.x / size.width).coerceIn(0f, 1f)) }
    }.pointerInput(Unit) { detectTapGestures { o -> state.value.set("Position", (o.x / size.width).coerceIn(0f, 1f)) } }) {
        val w = size.width; val h = size.height
        drawLine(Zk.line, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        // live grains: one translucent bar per grain, placed at its source position
        var i = 0
        while (i + 1 < grains.size) { val gx = grains[i] * w; val gw = (grains[i + 1] * w).coerceAtLeast(3f); drawRect(GR.copy(alpha = .16f), Offset(gx, 0f), Size(gw, h)); i += 2 }
        if (over.size >= 2) for (k in over.indices) { val x = w * k / over.size; val a = over[k].coerceIn(0f, 1f) * h * 0.46f; drawLine(GR, Offset(x, h / 2 - a), Offset(x, h / 2 + a), w / over.size * 0.9f) }
        drawLine(Color.White, Offset(pos * w, 0f), Offset(pos * w, h), 3f)
    }
}
