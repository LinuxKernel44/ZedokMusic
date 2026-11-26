package com.zedokmusic.ui.nplug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.ui.Zk

private val HY = Color(0xFF3DDCC4)
private val HY2 = Color(0xFFFF7A59)
private val HY3 = Color(0xFF8E7CFF)

/** Screen of Zedok Hybrid. */
@Composable
fun HybridScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Sound", "Filter", "Mod", "Matrix", "FX", "Arp"), tab, HY) { tab = it }
        val short = com.zedokmusic.ui.LocalShort.current
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> { SoundTab(ps); if (short) PerformBar(ps) }
                1 -> FilterTab(ps)
                2 -> ModTab(ps)
                3 -> MatrixTab(ps)
                4 -> FxTab(ps)
                else -> ArpTab(ps)
            }
        }
        if (!short) PerformBar(ps)
    }
}

@Composable
private fun PerformBar(ps: PluginState) {
    Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("BEND", color = Zk.dim, fontSize = 10.sp)
        var bend by remember { mutableFloatStateOf(0.5f) }
        Slider(bend, { bend = it; ps.set("Pitch Bend", it) }, onValueChangeFinished = { bend = 0.5f; ps.set("Pitch Bend", 0.5f) }, modifier = Modifier.weight(1f).height(30.dp),
            colors = SliderDefaults.colors(thumbColor = HY, activeTrackColor = Zk.line, inactiveTrackColor = Zk.line))
        Text("MOD", color = Zk.dim, fontSize = 10.sp)
        Slider(ps.v("Mod Wheel"), { ps.set("Mod Wheel", it) }, modifier = Modifier.weight(1f).height(30.dp), colors = SliderDefaults.colors(thumbColor = HY2, activeTrackColor = HY2, inactiveTrackColor = Zk.line))
        Text("VOL", color = Zk.dim, fontSize = 10.sp)
        Slider(ps.v("Volume"), { ps.set("Volume", it) }, modifier = Modifier.weight(0.7f).height(30.dp), colors = SliderDefaults.colors(thumbColor = HY3, activeTrackColor = HY3, inactiveTrackColor = Zk.line))
    }
}

@Composable
private fun OscSection(ps: PluginState, o: String, tel: Int, color: Color, t: com.zedokmusic.model.TrackM) {
    val type = ps.choice("$o Type")
    val wave by rememberTelemetry(t, tel)
    Section("Oscillator $o", color) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            WaveView(wave, color, Modifier.width(110.dp).height(64.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chips(ps, "$o Type", color)
                when (type) {
                    0 -> Chips(ps, "$o Wave", color)
                    1 -> Picker(ps, "$o Table", color, width = 170.dp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Knobs {
            when (type) {
                0 -> PK(ps, "$o Width", color, "Width")
                1 -> PK(ps, "$o Position", color, "Position")
                else -> { PK(ps, "$o FM Ratio", color, "Ratio"); PK(ps, "$o FM Index", color, "Index"); PK(ps, "$o FM Feedback", color, "Feedback") }
            }
            PK(ps, "$o Level", color, "Level")
            PK(ps, "$o Semi", color, "Semi", bipolar = true)
            PK(ps, "$o Fine", color, "Fine", bipolar = true)
            PK(ps, "$o Pan", color, "Pan", bipolar = true)
            PK(ps, "$o Detune", color, "Detune")
            PK(ps, "$o Spread", color, "Spread")
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Chips(ps, "$o Octave", color, "Octave"); Picker(ps, "$o Unison", color, "Unison voices", width = 100.dp)
        }
    }
}

@Composable
private fun SoundTab(ps: PluginState) {
    val t = ps.t
    Split({ OscSection(ps, "A", 0, HY, t) }, { OscSection(ps, "B", 1, HY2, t) })
    Split({
        Section("Sub & noise", HY3) {
            Knobs { PK(ps, "Sub Level", HY3, "Sub"); PK(ps, "Noise Level", HY3, "Noise"); PK(ps, "Noise Color", HY3, "Color") }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Chips(ps, "Sub Wave", HY3, "Sub wave"); Chips(ps, "Sub Octave", HY3, "Sub octave") }
        }
    }, {
        Section("Voices", HY) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                Picker(ps, "Polyphony", HY, "Polyphony", width = 90.dp); Chips(ps, "Play Mode", HY, "Play mode"); Chips(ps, "Quality", HY, "Quality")
            }
            Knobs { PK(ps, "Glide", HY, "Glide"); PK(ps, "Bend Range", HY, "Bend range") }
        }
    })
}

@Composable
private fun FilterTab(ps: PluginState) {
    Split({ Filter1Section(ps) }, { Filter2Section(ps) })
}

@Composable
private fun Filter1Section(ps: PluginState) {
    Section("Filter 1", HY) {
        Chips(ps, "F1 Type", HY)
        Spacer(Modifier.height(6.dp))
        Knobs { PK(ps, "F1 Cutoff", HY, "Cutoff", 60.dp); PK(ps, "F1 Resonance", HY, "Resonance", 60.dp); PK(ps, "F1 Drive", HY, "Drive"); PK(ps, "F1 Env Amount", HY, "Env amount", bipolar = true); PK(ps, "F1 Key Track", HY, "Key track") }
    }
}

@Composable
private fun Filter2Section(ps: PluginState) {
    Section("Filter 2", HY2) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Chips(ps, "F2 Type", HY2); }
        Spacer(Modifier.height(6.dp))
        Chips(ps, "Filter Routing", HY2, "Routing")
        Spacer(Modifier.height(6.dp))
        Knobs { PK(ps, "F2 Cutoff", HY2, "Cutoff", 60.dp); PK(ps, "F2 Resonance", HY2, "Resonance"); PK(ps, "F2 Key Track", HY2, "Key track") }
    }
}

@Composable
private fun EnvSection(ps: PluginState, title: String, pre: String, color: Color) {
    Section(title, color) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EnvView(ps.text("$pre Attack").toFloatOrNull() ?: 5f, ps.text("$pre Decay").toFloatOrNull() ?: 200f, ps.v("$pre Sustain"), ps.text("$pre Release").toFloatOrNull() ?: 200f, color, Modifier.width(110.dp).height(64.dp))
            Knobs { PK(ps, "$pre Attack", color, "A"); PK(ps, "$pre Decay", color, "D"); PK(ps, "$pre Sustain", color, "S"); PK(ps, "$pre Release", color, "R") }
        }
    }
}

@Composable
private fun LfoSection(ps: PluginState, n: Int, color: Color) {
    val p = "LFO$n"
    Section("LFO $n", color) {
        Chips(ps, "$p Shape", color, short = listOf("Sine", "Tri", "Saw", "Sq", "S&H", "Rand"))
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Toggle(ps, "$p Sync", color, "Sync"); Toggle(ps, "$p Retrigger", color, "Retrig")
        }
        Spacer(Modifier.height(6.dp))
        Knobs { if (ps.on("$p Sync")) Picker(ps, "$p Division", color, "Division", width = 100.dp) else PK(ps, "$p Rate", color, "Rate"); PK(ps, "$p Fade In", color, "Fade in") }
    }
}

@Composable
private fun ModTab(ps: PluginState) {
    Split({ EnvSection(ps, "Amp envelope", "Amp", HY) }, { EnvSection(ps, "Envelope 2 (filter)", "Env2", HY2) }, { EnvSection(ps, "Envelope 3", "Env3", HY3) })
    Split({ LfoSection(ps, 1, HY) }, { LfoSection(ps, 2, HY2) }, { LfoSection(ps, 3, HY3) })
}

@Composable
private fun MatrixTab(ps: PluginState) {
    Section("Modulation matrix", HY) {
        Text("Each row sends a source to a target. Amount can be negative.", color = Zk.dim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        for (m in 1..8) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$m", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.width(14.dp))
                Picker(ps, "Mod $m Source", HY, width = 110.dp)
                Picker(ps, "Mod $m Target", HY2, width = 150.dp)
                PK(ps, "Mod $m Amount", HY3, "", 40.dp, bipolar = true)
            }
        }
    }
}

@Composable
private fun FxTab(ps: PluginState) {
    Split({ Section("Chorus", HY) { Knobs { PK(ps, "Chorus Mix", HY, "Mix"); PK(ps, "Chorus Rate", HY, "Rate"); PK(ps, "Chorus Depth", HY, "Depth") } } }, {
        Section("Delay", HY2) {
            Chips(ps, "Delay Time", HY2, "Time (tempo synced)")
            Spacer(Modifier.height(6.dp))
            Knobs { PK(ps, "Delay Mix", HY2, "Mix"); PK(ps, "Delay Feedback", HY2, "Feedback"); PK(ps, "Delay Ping-Pong", HY2, "Ping-pong") }
        }
    }, { Section("Reverb", HY3) { Knobs { PK(ps, "Reverb Mix", HY3, "Mix"); PK(ps, "Reverb Size", HY3, "Size"); PK(ps, "Reverb Decay", HY3, "Decay"); PK(ps, "Reverb Damping", HY3, "Damping") } } })
}

@Composable
private fun ArpTab(ps: PluginState) {
    Section("Arpeggiator", HY) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { Toggle(ps, "Arp On", HY, "Arp on"); Toggle(ps, "Arp Hold", HY2, "Hold") }
        Spacer(Modifier.height(8.dp))
        Chips(ps, "Arp Mode", HY, "Pattern")
        Spacer(Modifier.height(6.dp))
        Chips(ps, "Arp Rate", HY, "Rate")
        Spacer(Modifier.height(6.dp))
        Chips(ps, "Arp Octaves", HY, "Octaves")
        Spacer(Modifier.height(6.dp))
        Knobs { PK(ps, "Arp Gate", HY, "Gate"); PK(ps, "Arp Swing", HY, "Swing") }
    }
}
