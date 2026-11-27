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
    var editing by remember { mutableStateOf(false) }
    val vm = ps.vm; val t = ps.t
    var browse by remember { mutableStateOf(false) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.importSamples(uris) }
    if (editing) t.states["sample"]?.takeIf { it.isNotBlank() }?.let { com.zedokmusic.ui.SampleEditDialog(vm, t, it) { editing = false } }
    if (browse) com.zedokmusic.ui.SoundPickerDialog(vm, { node -> browse = false; picker = false; vm.kits.loadAsSample(t, listOf(node)) }, { browse = false })
    if (picker && !browse) SamplePickerDialog(vm.projectSamples(), vm.sharedSamples(), vm.projectSamplesDir()?.path ?: "", { importer.launch(arrayOf("audio/*", "application/octet-stream")) }, { f -> vm.setPluginState(t, "sample", f.absolutePath); picker = false }, { picker = false }, onBrowse = { browse = true })
    Column(Modifier.fillMaxSize()) {
        TabBar(listOf("Sample", "Envelopes", "Filter & LFO", "Main"), tab, SA) { tab = it }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = ScrollBottomPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (tab) {
                0 -> SampleTab(ps, { picker = true }, { editing = true })
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
private fun SampleTab(ps: PluginState, onLoad: () -> Unit, onEdit: () -> Unit) {
    val t = ps.t
    val sample = t.states["sample"]
    Split({
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WaveEditor(ps)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelButton("Load sample…", false, w = 112.dp, h = 36.dp) { onLoad() }
                LabelButton("Edit sample…", false, w = 112.dp, h = 36.dp, enabled = !sample.isNullOrEmpty()) { onEdit() }
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

/**
 * The sample as it will be played: reversed, normalized, with its fades, between the start and end markers (and the loop markers while a loop mode is on), with the live play heads.
 * With Reverse the picture is flipped, so the left marker is always where playing starts.
 */
@Composable
private fun WaveEditor(ps: PluginState) {
    val over by rememberTelemetry(ps.t, 0, 250)
    val heads by rememberTelemetry(ps.t, 1, 50)
    val info by rememberTelemetry(ps.t, 2, 500)   // length in seconds, peak
    val state = rememberUpdatedState(ps)
    val short = com.zedokmusic.ui.LocalShort.current
    val rev = ps.on("Reverse"); val norm = ps.on("Normalize")
    val start = ps.v("Start"); val end = ps.v("End"); val ls = ps.v("Loop Start"); val le = ps.v("Loop End")
    val dS = if (rev) 1f - end else start; val dE = if (rev) 1f - start else end   // the region as drawn
    val loopOn = ps.choice("Loop Mode") != 0
    val slices = listOf(0, 2, 4, 8, 16, 32).getOrElse(ps.choice("Slices")) { 0 }
    val seconds = info.getOrElse(0) { 0f }; val peak = info.getOrElse(1) { 1f }.coerceAtLeast(0.001f)
    val fadeIn = (ps.text("Fade In").toFloatOrNull() ?: 0f) / 1000f; val fadeOut = (ps.text("Fade Out").toFloatOrNull() ?: 0f) / 1000f
    Canvas(Modifier.fillMaxWidth().height(if (short) 100.dp else 150.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0B0E)).pointerInput(Unit) {
        var which = ""
        detectDragGestures(onDragStart = { o ->
            val s = state.value; val x = o.x / size.width.toFloat(); val r = s.on("Reverse")
            val a = s.v("Start"); val b = s.v("End"); val ds = if (r) 1f - b else a; val de = if (r) 1f - a else b
            val cands = mutableListOf("L" to ds, "R" to de)
            if (s.choice("Loop Mode") != 0) { cands.add("LS" to ds + s.v("Loop Start") * (de - ds)); cands.add("LE" to ds + s.v("Loop End") * (de - ds)) }
            which = cands.minByOrNull { Math.abs(it.second - x) }?.first ?: "L"
        }) { change, _ ->
            change.consume(); val s = state.value; val x = (change.position.x / size.width).coerceIn(0f, 1f); val r = s.on("Reverse")
            val a = s.v("Start"); val b = s.v("End"); val ds = if (r) 1f - b else a; val de = if (r) 1f - a else b; val span = (de - ds).coerceAtLeast(0.001f)
            when (which) {
                "L" -> if (!r) s.set("Start", x.coerceAtMost(b - 0.002f)) else s.set("End", (1f - x).coerceAtLeast(a + 0.002f))
                "R" -> if (!r) s.set("End", x.coerceAtLeast(a + 0.002f)) else s.set("Start", (1f - x).coerceAtMost(b - 0.002f))
                "LS" -> s.set("Loop Start", ((x - ds) / span).coerceIn(0f, s.v("Loop End") - 0.01f))
                "LE" -> s.set("Loop End", ((x - ds) / span).coerceIn(s.v("Loop Start") + 0.01f, 1f))
            }
        }
    }) {
        val w = size.width; val h = size.height
        drawLine(Zk.line, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        val n = over.size
        if (n >= 2) {
            val gainN = if (norm) 1f / peak else 1f
            val regionSec = ((dE - dS) * seconds).coerceAtLeast(0.001f)
            for (k in 0 until n) {
                val xf = k.toFloat() / n
                val v = over[if (rev) n - 1 - k else k] * gainN
                // fades act inside the region, in play direction
                val u = ((xf - dS) / (dE - dS).coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
                val fi = if (fadeIn > 0f) (u * regionSec / fadeIn).coerceIn(0f, 1f) else 1f
                val fo = if (fadeOut > 0f) ((1f - u) * regionSec / fadeOut).coerceIn(0f, 1f) else 1f
                val a = (v * fi * fo).coerceIn(0f, 1f) * h * 0.46f
                val x = w * xf
                drawLine(SA, Offset(x, h / 2 - a), Offset(x, h / 2 + a), w / n * 0.9f)
            }
        }
        // the part outside the region is dimmed
        drawRect(Color(0xAA0A0B0E), Offset(0f, 0f), Size(dS * w, h)); drawRect(Color(0xAA0A0B0E), Offset(dE * w, 0f), Size((1f - dE) * w, h))
        if (slices > 0) for (i in 1 until slices) { val x = (dS + (dE - dS) * i / slices) * w; drawLine(SA3.copy(alpha = .8f), Offset(x, 0f), Offset(x, h), 1.5f) }
        if (loopOn) {
            val a = (dS + ls * (dE - dS)) * w; val b = (dS + le * (dE - dS)) * w
            drawRect(SA2.copy(alpha = .14f), Offset(a, 0f), Size(b - a, h)); drawLine(SA2, Offset(a, 0f), Offset(a, h), 3f); drawLine(SA2, Offset(b, 0f), Offset(b, h), 3f)
        }
        drawLine(SA3, Offset(dS * w, 0f), Offset(dS * w, h), 3f); drawLine(SA4, Offset(dE * w, 0f), Offset(dE * w, h), 3f)
        for (hd in heads) { val xp = if (rev) 1f - hd else hd; drawLine(Color.White, Offset(xp * w, 0f), Offset(xp * w, h), 2f) }
    }
}
