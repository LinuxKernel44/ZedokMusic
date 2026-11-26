package com.zedokmusic.ui.nplug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.ui.ScrollBottomPad
import com.zedokmusic.ui.Zk

private val SP = Color(0xFF8E7CFF)
private val SP2 = Color(0xFF4DABF7)
private val CO = Color(0xFFFF6B81)
private val CO2 = Color(0xFFFFA94D)

@Composable
private fun Scroll(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = ScrollBottomPad), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun LevelBar(ps: PluginState, color: Color) {
    val m by rememberTelemetry(ps.t, 1, 60)
    if (m.size < 2) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.Text("IN", color = Zk.dim, fontSize = 10.sp)
        Bar(m[0], color, Modifier.weight(1f))
        androidx.compose.material3.Text("OUT", color = Zk.dim, fontSize = 10.sp)
        Bar(m[1], color, Modifier.weight(1f))
    }
}
@Composable
private fun Bar(db: Float, color: Color, modifier: Modifier) {
    Canvas(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF0A0B0E))) { drawRect(color, Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width * ((db + 60f) / 60f).coerceIn(0f, 1f), size.height)) }
}

/** Zedok Space. */
@Composable
fun SpaceScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Reverb", "Delay", "Main"), tab, SP) { tab = it }
        Box(Modifier.weight(1f)) {
            Scroll {
                when (tab) {
                    0 -> Split({
                        Section("Reverb", SP) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, "Reverb On", SP, "On"); Chips(ps, "Reverb Mode", SP) }
                            Spacer(Modifier.height(8.dp))
                            ReverbView(ps, SP)
                            Spacer(Modifier.height(6.dp))
                            Knobs { PK(ps, "Reverb Mix", SP, "Mix", 60.dp); PK(ps, "Size", SP, "Size", 60.dp); PK(ps, "Decay", SP, "Decay", 60.dp); PK(ps, "Pre-Delay", SP, "Pre-delay") }
                        }
                    }, {
                        Section("Tone", SP2) { Knobs { PK(ps, "Damping", SP2, "Damping"); PK(ps, "Diffusion", SP2, "Diffusion"); PK(ps, "Modulation", SP2, "Modulation"); PK(ps, "Reverb Low Cut", SP2, "Low cut"); PK(ps, "Reverb High Cut", SP2, "High cut"); PK(ps, "Width", SP2, "Width"); PK(ps, "Shimmer", SP2, "Shimmer") } }
                    })
                    1 -> Split({
                        Section("Delay", SP) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, "Delay On", SP, "On"); Chips(ps, "Time Mode", SP) }
                            Spacer(Modifier.height(8.dp))
                            if (ps.choice("Time Mode") == 0) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Picker(ps, "Time L", SP, "Left", width = 110.dp); Picker(ps, "Time R", SP, "Right", width = 110.dp) }
                            else Knobs { PK(ps, "Free Time L", SP, "Left", 60.dp); PK(ps, "Free Time R", SP, "Right", 60.dp) }
                            Spacer(Modifier.height(6.dp))
                            Knobs { PK(ps, "Delay Mix", SP, "Mix", 60.dp); PK(ps, "Feedback", SP, "Feedback", 60.dp); PK(ps, "Ping-Pong", SP, "Ping-pong") }
                        }
                    }, { Section("Character", SP2) { Knobs { PK(ps, "Delay Low Cut", SP2, "Low cut"); PK(ps, "Delay High Cut", SP2, "High cut"); PK(ps, "Tape Drive", SP2, "Tape"); PK(ps, "Wobble Rate", SP2, "Wobble rate"); PK(ps, "Wobble Depth", SP2, "Wobble"); PK(ps, "Duck", SP2, "Duck") } } })
                    else -> Section("Main", SP) { Chips(ps, "Routing", SP); Spacer(Modifier.height(8.dp)); Knobs { PK(ps, "Output Gain", SP, "Output", 60.dp, bipolar = true) } }
                }
                LevelBar(ps, SP)
            }
        }
    }
}

/** A sketch of the reverb tail: an exponential decay whose length follows Decay, with the pre-delay gap. */
@Composable
private fun ReverbView(ps: PluginState, color: Color) {
    val decay = 0.2f * Math.pow(100.0, ps.v("Decay").toDouble()).toFloat(); val pre = 250f * Math.pow(ps.v("Pre-Delay").toDouble(), 2.0).toFloat(); val damp = ps.v("Damping")
    Canvas(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0B0E))) {
        val w = size.width; val h = size.height; val tMax = maxOf(decay * 1.3f, 0.6f)
        val x0 = (pre / 1000f) / tMax * w
        val p = Path(); p.moveTo(x0, h)
        for (i in 0..80) { val t = tMax * i / 80f; val x = (pre / 1000f + t) / tMax * w; val a = Math.pow(10.0, (-3.0 * t / decay)).toFloat(); p.lineTo(x.coerceAtMost(w), h - a * h * (0.9f - 0.2f * damp)) }
        p.lineTo(w, h); p.close(); drawPath(p, color.copy(alpha = .35f)); drawPath(p, color, style = Stroke(2.5f))
        drawLine(Zk.line, Offset(x0, 0f), Offset(x0, h), 1.5f)
    }
}

/** Zedok Color. */
@Composable
fun ColorScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Modulation", "Distortion", "Crusher", "Main"), tab, CO) { tab = it }
        Box(Modifier.weight(1f)) {
            Scroll {
                when (tab) {
                    0 -> {
                        val type = ps.choice("Mod Type")
                        Section("Modulation", CO) {
                            Chips(ps, "Mod Type", CO)
                            Spacer(Modifier.height(8.dp))
                            if (type != 0) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, "Mod Sync", CO, "Tempo sync"); if (ps.on("Mod Sync")) Picker(ps, "Mod Division", CO, width = 100.dp) }
                                Spacer(Modifier.height(6.dp))
                                Knobs {
                                    if (!ps.on("Mod Sync")) PK(ps, "Rate", CO, "Rate", 60.dp)
                                    PK(ps, "Depth", CO, "Depth", 60.dp); PK(ps, "Mod Mix", CO, "Mix", 60.dp)
                                    if (type == 1) PK(ps, "Center", CO, "Delay")
                                    if (type == 3) { PK(ps, "Center", CO, "Delay"); PK(ps, "Mod Feedback", CO, "Feedback", bipolar = true) }
                                    if (type == 4) { PK(ps, "Phaser Center", CO, "Center"); PK(ps, "Mod Feedback", CO, "Feedback", bipolar = true) }
                                    PK(ps, "Stereo Spread", CO, "Spread"); PK(ps, "Mod Tone", CO, "Tone")
                                }
                                if (type == 1) { Spacer(Modifier.height(4.dp)); Chips(ps, "Voices", CO, "Voices") }
                                if (type == 4) { Spacer(Modifier.height(4.dp)); Chips(ps, "Stages", CO, "Stages") }
                            }
                        }
                    }
                    1 -> {
                        Section("Distortion", CO) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { Toggle(ps, "Dist On", CO, "On"); Toggle(ps, "Oversampling", CO2, "HQ oversampling") }
                            Spacer(Modifier.height(8.dp)); Chips(ps, "Dist Type", CO)
                            Spacer(Modifier.height(8.dp))
                            TransferView(ps)
                            Spacer(Modifier.height(6.dp))
                            Knobs { PK(ps, "Drive", CO, "Drive", 60.dp); PK(ps, "Bias", CO, "Bias", bipolar = true); PK(ps, "Tone", CO, "Tone"); PK(ps, "Pre High-Pass", CO, "Pre HP"); PK(ps, "Dist Mix", CO, "Mix"); PK(ps, "Dist Output", CO, "Output", bipolar = true) }
                        }
                    }
                    2 -> Section("Bit crusher", CO) { Toggle(ps, "Crush On", CO, "On"); Spacer(Modifier.height(8.dp)); Knobs { PK(ps, "Bits", CO, "Bits", 60.dp); PK(ps, "Downsample", CO, "Downsample", 60.dp); PK(ps, "Jitter", CO, "Jitter"); PK(ps, "Crush Mix", CO, "Mix") } }
                    else -> Section("Main", CO) { Chips(ps, "Order", CO, "Signal order"); Spacer(Modifier.height(8.dp)); Knobs { PK(ps, "Dry/Wet", CO, "Dry / wet", 60.dp); PK(ps, "Output Gain", CO, "Output", 60.dp, bipolar = true) } }
                }
            }
        }
    }
}

@Composable
private fun TransferView(ps: PluginState) {
    val curve by rememberTelemetry(ps.t, 0, 100)
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0B0E))) {
        val w = size.width; val h = size.height
        drawLine(Zk.line, Offset(0f, h / 2), Offset(w, h / 2), 1f); drawLine(Zk.line, Offset(w / 2, 0f), Offset(w / 2, h), 1f)
        drawLine(Zk.line, Offset(0f, h), Offset(w, 0f), 1f)
        if (curve.size < 2) return@Canvas
        val p = Path(); for (i in curve.indices) { val x = w * i / (curve.size - 1); val y = h / 2 - curve[i].coerceIn(-1.2f, 1.2f) * h / 2 / 1.2f; if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        drawPath(p, CO, style = Stroke(3f))
    }
}
