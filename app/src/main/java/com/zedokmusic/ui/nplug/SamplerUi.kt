package com.zedokmusic.ui.nplug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.Theory
import com.zedokmusic.ui.LabelButton
import com.zedokmusic.ui.SamplePickerDialog
import com.zedokmusic.ui.ScrollBottomPad
import com.zedokmusic.ui.Zk

private val SA = Color(0xFF4DA3FF)
private val SA2 = Color(0xFFFFB347)
private val SA3 = Color(0xFF7BE07B)
private val SA4 = Color(0xFFFF6B81)

/** Zedok Sampler: the Sampler channel of FL Studio, in four pages. */
@Composable
fun SamplerScreen(ps: PluginState) {
    var tab by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    val vm = ps.vm; val t = ps.t
    var browse by remember { mutableStateOf(false) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importSamples(uris) }
    if (browse) com.zedokmusic.ui.SoundPickerDialog(vm, { node -> browse = false; picker = false; vm.kits.loadAsSample(t, listOf(node)) }, { browse = false })
    if (picker && !browse) SamplePickerDialog(vm.projectSamples(), vm.sharedSamples(), vm.projectSamplesDir()?.path ?: "", { importer.launch(arrayOf("audio/*", "application/octet-stream")) }, { f -> vm.setPluginState(t, "sample", f.absolutePath); picker = false }, { picker = false }, onBrowse = { browse = true })
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Sample", "Envelopes", "Filter & LFO", "Main"), tab, SA) { tab = it }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = ScrollBottomPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> SampleTab(ps) { picker = true }
                1 -> {
                    Split({ SEnv(ps, "Amp", "Volume envelope", SA) }, { SEnv(ps, "Flt", "Filter envelope", SA2, "Filter Env", "Amount") })
                    SEnv(ps, "Pit", "Pitch envelope", SA3, "Pitch Env", "Amount (st)")
                }
                2 -> {
                    Split({
                        Section("Filter", SA2) {
                            Picker(ps, "Filter Type", SA2, "Type", width = 130.dp)
                            Spacer(Modifier.height(6.dp))
                            Knobs { PK(ps, "Cutoff", SA2, "Cutoff"); PK(ps, "Resonance", SA2, "Resonance"); PK(ps, "Filter Key Track", SA2, "Key track"); PK(ps, "Filter Env", SA2, "Env amount", bipolar = true) }
                        }
                    }, {
                        Section("LFO", SA3) {
                            Chips(ps, "LFO Shape", SA3, "Shape")
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) { Picker(ps, "LFO Sync", SA3, "Rate sync", width = 90.dp); Toggle(ps, "LFO Retrigger", SA3, "Retrigger") }
                            Spacer(Modifier.height(6.dp))
                            Knobs { PK(ps, "LFO Rate", SA3, "Rate"); PK(ps, "LFO Delay", SA3, "Delay") }
                        }
                    })
                    Section("LFO destinations", SA4) { Knobs { PK(ps, "LFO Pitch", SA4, "Pitch", bipolar = true); PK(ps, "LFO Volume", SA4, "Volume"); PK(ps, "LFO Pan", SA4, "Pan", bipolar = true); PK(ps, "LFO Cutoff", SA4, "Cutoff", bipolar = true) } }
                }
                else -> {
                    Section("Output", SA) { Knobs { PK(ps, "Volume", SA, "Volume"); PK(ps, "Pan", SA, "Pan", bipolar = true); PK(ps, "Velocity Sens", SA, "Velocity") } }
                    Section("Pitch", SA2) { Knobs { PK(ps, "Coarse", SA2, "Coarse", bipolar = true); PK(ps, "Fine", SA2, "Fine", bipolar = true); PK(ps, "Bend Range", SA2, "Bend range"); PK(ps, "Pitch Bend", SA2, "Bend", bipolar = true) } }
                    Section("Voices", SA3) {
                        Chips(ps, "Play Mode", SA3, "Mode: polyphonic, monophonic or legato")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) { Picker(ps, "Polyphony", SA3, "Voices", width = 90.dp); Toggle(ps, "Cut Itself", SA3, "Cut itself"); Chips(ps, "Quality", SA3, "Quality") }
                        Spacer(Modifier.height(6.dp))
                        Knobs { PK(ps, "Glide", SA3, "Glide") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SampleTab(ps: PluginState, onLoad: () -> Unit) {
    val t = ps.t
    val sample = t.states["sample"]
    Split({
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WaveEditor(ps)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelButton("Load sample…", false, w = 112.dp, h = 36.dp) { onLoad() }
                Toggle(ps, "Reverse", SA, "Reverse"); Toggle(ps, "Normalize", SA, "Normalize"); Toggle(ps, "Key Tracking", SA, "Key tracking"); Toggle(ps, "One Shot", SA, "One shot")
            }
            Text(if (sample.isNullOrEmpty()) "No sample yet: tap Load sample… (WAV files of your project or the shared library)." else "Sample: " + sample.substringAfterLast('/') + " · root " + Theory.name(ps.text("Root Note").toFloatOrNull()?.toInt() ?: 60), color = Zk.dim, fontSize = 11.sp)
            Chips(ps, "Loop Mode", SA2, "Loop")
            Chips(ps, "Slices", SA3, "Slices (one per key, starting at the root note)")
        }
    }, {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Section("Sample", SA) { Knobs { PK(ps, "Root Note", SA, "Root note"); PK(ps, "Fade In", SA, "Fade in"); PK(ps, "Fade Out", SA, "Fade out"); PK(ps, "Loop Crossfade", SA2, "Loop xfade") } }
            Section("Time", SA3) {
                Chips(ps, "Time Mode", SA3, "Method: Resample = speed and pitch together, Stretch = independent")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) { Toggle(ps, "Tempo Sync", SA3, "Tempo sync (needs the sample BPM)") }
                Spacer(Modifier.height(6.dp))
                Knobs { PK(ps, "Time Stretch", SA3, "Time"); PK(ps, "Sample BPM", SA3, "Sample BPM"); PK(ps, "Stretch Grain", SA3, "Grain") }
            }
        }
    })
}

@Composable
private fun SEnv(ps: PluginState, pre: String, title: String, color: Color, extra: String? = null, extraLabel: String = "") {
    Section(title, color) {
        EnvView(ps.text("$pre Attack").toFloatOrNull() ?: 5f, ps.text("$pre Decay").toFloatOrNull() ?: 200f, ps.v("$pre Sustain"), ps.text("$pre Release").toFloatOrNull() ?: 200f, color, Modifier.fillMaxWidth().height(64.dp))
        Spacer(Modifier.height(6.dp))
        Knobs {
            PK(ps, "$pre Delay", color, "Delay"); PK(ps, "$pre Attack", color, "Attack"); PK(ps, "$pre Hold", color, "Hold"); PK(ps, "$pre Decay", color, "Decay"); PK(ps, "$pre Sustain", color, "Sustain"); PK(ps, "$pre Release", color, "Release")
            if (extra != null) PK(ps, extra, color, extraLabel, bipolar = true)
        }
    }
}

/** The sample with draggable start / end markers (and loop markers while a loop mode is on), and the live play heads. */
@Composable
private fun WaveEditor(ps: PluginState) {
    val over by rememberTelemetry(ps.t, 0, 250)
    val heads by rememberTelemetry(ps.t, 1, 50)
    val state = rememberUpdatedState(ps)
    val short = com.zedokmusic.ui.LocalShort.current
    val start = ps.v("Start"); val end = ps.v("End"); val ls = ps.v("Loop Start"); val le = ps.v("Loop End")
    val loopOn = ps.choice("Loop Mode") != 0
    val slices = listOf(0, 2, 4, 8, 16, 32).getOrElse(ps.choice("Slices")) { 0 }
    Canvas(Modifier.fillMaxWidth().height(if (short) 100.dp else 150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0B0E)).pointerInput(Unit) {
        var which = ""
        detectDragGestures(onDragStart = { o ->
            val s = state.value; val w = size.width.toFloat(); val x = o.x / w
            val cands = mutableListOf("Start" to s.v("Start"), "End" to s.v("End"))
            if (s.choice("Loop Mode") != 0) { cands.add("Loop Start" to s.v("Loop Start") * (s.v("End") - s.v("Start")) + s.v("Start")); cands.add("Loop End" to s.v("Loop End") * (s.v("End") - s.v("Start")) + s.v("Start")) }
            which = cands.minByOrNull { Math.abs(it.second - x) }?.first ?: "Start"
        }) { change, _ ->
            change.consume(); val s = state.value; val x = (change.position.x / size.width).coerceIn(0f, 1f)
            val a = s.v("Start"); val b = s.v("End"); val span = (b - a).coerceAtLeast(0.001f)
            when (which) {
                "Start" -> s.set("Start", x.coerceAtMost(b - 0.002f))
                "End" -> s.set("End", x.coerceAtLeast(a + 0.002f))
                "Loop Start" -> s.set("Loop Start", ((x - a) / span).coerceIn(0f, s.v("Loop End") - 0.01f))
                "Loop End" -> s.set("Loop End", ((x - a) / span).coerceIn(s.v("Loop Start") + 0.01f, 1f))
            }
        }
    }) {
        val w = size.width; val h = size.height
        drawLine(Zk.line, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        if (over.size >= 2) for (k in over.indices) { val x = w * k / over.size; val a = over[k].coerceIn(0f, 1f) * h * 0.46f; drawLine(SA, Offset(x, h / 2 - a), Offset(x, h / 2 + a), w / over.size * 0.9f) }
        // the part outside start / end is dimmed
        drawRect(Color(0xAA0A0B0E), Offset(0f, 0f), Size(start * w, h)); drawRect(Color(0xAA0A0B0E), Offset(end * w, 0f), Size((1f - end) * w, h))
        if (slices > 0) for (i in 1 until slices) { val x = (start + (end - start) * i / slices) * w; drawLine(SA3.copy(alpha = .8f), Offset(x, 0f), Offset(x, h), 1.5f) }
        if (loopOn) {
            val a = (start + ls * (end - start)) * w; val b = (start + le * (end - start)) * w
            drawRect(SA2.copy(alpha = .14f), Offset(a, 0f), Size(b - a, h)); drawLine(SA2, Offset(a, 0f), Offset(a, h), 3f); drawLine(SA2, Offset(b, 0f), Offset(b, h), 3f)
        }
        drawLine(SA3, Offset(start * w, 0f), Offset(start * w, h), 3f); drawLine(SA4, Offset(end * w, 0f), Offset(end * w, h), 3f)
        for (hd in heads) drawLine(Color.White, Offset(hd * w, 0f), Offset(hd * w, h), 2f)
    }
}
