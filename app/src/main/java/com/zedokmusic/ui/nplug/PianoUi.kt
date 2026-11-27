package com.zedokmusic.ui.nplug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.ui.ScrollBottomPad
import com.zedokmusic.ui.Zk

private val PI1 = Color(0xFFE8E2D0)
private val PI2 = Color(0xFFFFB347)
private val PI3 = Color(0xFF4DD0E1)
private val PI4 = Color(0xFFB39DDB)

/** Zedok Piano: a sampled concert grand with pedal, release, string resonance, tuning, tone and space. */
@Composable
fun PianoScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Piano", "Touch & Pedal", "Tone", "Tuning", "Space"), tab, PI1) { tab = it }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = ScrollBottomPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> {
                    PackStatus(ps)
                    KeyLevels(ps)
                    Text("Sounds: Salamander Grand Piano by Alexander Holm, CC BY 3.0.", color = Zk.dim, fontSize = 10.sp)
                    Split({
                        Section("Output", PI1) { Knobs { PK(ps, "Volume", PI1, "Volume"); PK(ps, "Pan", PI1, "Pan", bipolar = true); PK(ps, "Width", PI1, "Width"); PK(ps, "Transpose", PI1, "Transpose", bipolar = true) } }
                    }, {
                        Section("Voices", PI2) { Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) { Picker(ps, "Polyphony", PI2, "Voices", width = 90.dp); Chips(ps, "Quality", PI2, "Quality") } }
                    })
                }
                1 -> {
                    Split({
                        Section("Touch", PI1) { Knobs { PK(ps, "Velocity Curve", PI1, "Curve"); PK(ps, "Touch", PI1, "Touch", bipolar = true); PK(ps, "Humanize", PI1, "Humanize") } }
                    }, {
                        Section("Pedals", PI2) { Knobs { PK(ps, "Sustain Pedal", PI2, "Sustain pedal"); PK(ps, "Una Corda", PI2, "Una corda"); PK(ps, "Pedal Noise", PI2, "Pedal noise") } }
                    })
                    Section("Release and resonance", PI3) {
                        Knobs { PK(ps, "Release", PI3, "Release"); PK(ps, "Release Key Track", PI3, "Key track"); PK(ps, "Key Off Noise", PI3, "Key-off noise (dB)"); PK(ps, "String Resonance", PI3, "Resonance"); PK(ps, "Resonance Decay", PI3, "Res. decay") }
                    }
                    Text("The sustain pedal is a parameter (draw it in Auto): above 50 % the dampers are lifted, below it a partial pedal lengthens the release. Soft pedal (Una corda) lowers and darkens the sound.", color = Zk.dim, fontSize = 11.sp)
                }
                2 -> Section("Tone", PI1) { Knobs { PK(ps, "Low Tone", PI1, "Low", bipolar = true); PK(ps, "Presence", PI1, "Presence", bipolar = true); PK(ps, "High Tone", PI1, "High", bipolar = true); PK(ps, "Lid", PI1, "Lid") } }
                3 -> {
                    Section("Tuning", PI4) {
                        Picker(ps, "Temperament", PI4, "Temperament", width = 170.dp)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) { Picker(ps, "Temperament Key", PI4, "Key of the temperament", width = 90.dp) }
                        Spacer(Modifier.height(6.dp))
                        Knobs { PK(ps, "A4", PI4, "A4 reference"); PK(ps, "Fine Tune", PI4, "Fine tune", bipolar = true); PK(ps, "Stretch", PI4, "Stretch") }
                    }
                    Text("Stretch widens the tuning of the extremes like a real piano (low notes flat, high notes sharp). A4 = 415 is the usual pitch for baroque music.", color = Zk.dim, fontSize = 11.sp)
                }
                else -> Section("Reverb", PI3) { Chips(ps, "Reverb Type", PI3, "Space"); Spacer(Modifier.height(6.dp)); Knobs { PK(ps, "Reverb Mix", PI3, "Mix"); PK(ps, "Reverb Size", PI3, "Size"); PK(ps, "Reverb Decay", PI3, "Decay"); PK(ps, "Reverb Damping", PI3, "Damping") } }
            }
        }
    }
}

/** "Loading the piano…" while the sound pack is prepared (the first start copies it and decodes the beginning of every sound). */
@Composable
private fun PackStatus(ps: PluginState) {
    val st by rememberTelemetry(ps.t, 0, 300)
    val frac = st.getOrElse(0) { 0f }; val state = st.getOrElse(1) { 0f }.toInt()
    if (state >= 2) return
    Text(when (state) { 3 -> "The piano sounds could not be loaded."; 1 -> "Loading the piano… ${(frac * 100).toInt()} %"; else -> "Preparing the piano sounds (first start only)…" }, color = if (state == 3) Zk.danger else PI2, fontSize = 13.sp)
}

/** The 88 keys as thin bars that light with the sound each key is making. */
@Composable
private fun KeyLevels(ps: PluginState) {
    val lv by rememberTelemetry(ps.t, 1, 60)
    val short = com.zedokmusic.ui.LocalShort.current
    Canvas(Modifier.fillMaxWidth().height(if (short) 46.dp else 64.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0B0E))) {
        val n = 88; val w = size.width / n; val h = size.height
        val black = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)   // C..B starting at C: C# D# F# G# A#
        for (k in 0 until n) {
            val pc = (k + 21) % 12; val isBlack = black[pc]
            drawRect(if (isBlack) Color(0xFF1B1D23) else Color(0xFF262A32), Offset(k * w, 0f), Size(w - 1f, h))
            val a = (lv.getOrElse(k) { 0f } * 1.4f).coerceIn(0f, 1f)
            if (a > 0.01f) drawRect(PI2.copy(alpha = 0.35f + 0.65f * a), Offset(k * w, h * (1f - a)), Size(w - 1f, h * a))
        }
    }
}
