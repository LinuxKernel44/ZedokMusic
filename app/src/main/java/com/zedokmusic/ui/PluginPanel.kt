package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*
import kotlinx.coroutines.delay

/** Generic editor for the selected instrument's parameters (VST3 IEditController), with search. */
@Composable
fun PluginPanel(vm: DawViewModel) {
    val t = vm.selected?.takeIf { it.kind == TrackKind.Instrument } ?: vm.tracks.firstOrNull { it.kind == TrackKind.Instrument }
    if (t == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Add an instrument track first.", color = Zk.dim) }; return }
    if (t.pluginState != 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (t.pluginState == 3) "The plugin failed to start." else "Plugin is starting…", color = if (t.pluginState == 3) Zk.danger else Zk.dim)
        }
        return
    }
    val editor = t.editorOpen
    val short = LocalShort.current
    var keys by remember(t.id) { mutableStateOf(false) }
    var query by remember(t.id) { mutableStateOf("") }
    var onlyChanged by remember(t.id) { mutableStateOf(false) }
    val params = remember(t.id, t.pluginState) { vm.loadParams(t) }
    val shown = remember(params, query, onlyChanged, t.paramOverrides.size) {
        params.filter { (query.isBlank() || it.name.contains(query, true)) && (!onlyChanged || t.paramOverrides.containsKey(it.index)) }
    }
    Column(Modifier.fillMaxSize().background(Zk.bg)) {
        Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = if (short) 3.dp else 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(t.pluginName.ifBlank { t.name }, color = t.color, fontSize = 14.sp, maxLines = 1)
                if (!short) Text("${params.size} parameters · ${t.paramOverrides.size} changed", color = Zk.dim, fontSize = 11.sp)
            }
            if (editor && !short && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 840) LabelButton(if (vm.panelExpanded) "Shrink" else "Full", vm.panelExpanded, w = 64.dp) { vm.panelExpanded = !vm.panelExpanded }
            if (editor) LabelButton("Keys", keys, w = 60.dp) { keys = !keys }
            LabelButton("Editor", editor, w = 70.dp) { if (editor) vm.closeEditor(t) else vm.openEditor(t) }
            if (!editor) LabelButton("Changed", onlyChanged, w = 76.dp) { onlyChanged = !onlyChanged }
        }
        if (editor) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // On wide screens a second open editor is shown next to this one.
                val other = vm.tracks.firstOrNull { it !== t && it.editorOpen && it.kind == TrackKind.Instrument }
                if (maxWidth >= 840.dp && other != null) Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) { Text(t.name, color = t.color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)); Box(Modifier.weight(1f)) { PluginEditor(vm, t, keys) } }
                    VerticalDivider(color = Zk.line)
                    Column(Modifier.weight(1f).fillMaxHeight()) { Text(other.name, color = other.color, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp)); Box(Modifier.weight(1f)) { PluginEditor(vm, other, false) } }
                } else PluginEditor(vm, t, keys)
            }
            return@Column
        }
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search parameters") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (short) 0.dp else 4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            items(shown, key = { it.index }) { p -> ParamRow(vm, t, p) }
        }
    }
}

@Composable
private fun ParamRow(vm: DawViewModel, t: TrackM, p: ParamM) {
    var value by remember(t.id, p.index) { mutableFloatStateOf(vm.paramValue(t, p.index)) }
    var text by remember(t.id, p.index) { mutableStateOf(vm.paramText(t, p.index)) }
    val changed = t.paramOverrides.containsKey(p.index)
    LaunchedEffect(value) { delay(60); text = vm.paramText(t, p.index) }  // the host refreshes the text one block after the change
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.name, color = if (changed) Zk.accent else Zk.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable { vm.resetParam(t, p); value = p.default; text = vm.paramText(t, p.index) })
            Text((text + " " + p.units).trim(), color = Zk.dim, fontSize = 12.sp, maxLines = 1)
        }
        Slider(value, { v ->
            val nv = if (p.steps > 0) Math.round(v * p.steps) / p.steps.toFloat() else v
            value = nv; vm.setParam(t, p.index, nv); text = vm.paramText(t, p.index)
        }, modifier = Modifier.height(30.dp), colors = SliderDefaults.colors(thumbColor = if (changed) Zk.accent else Zk.dim, activeTrackColor = if (changed) Zk.accent else Zk.line, inactiveTrackColor = Zk.line))
    }
}
