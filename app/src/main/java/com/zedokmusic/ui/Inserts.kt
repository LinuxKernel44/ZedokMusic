package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.TrackM

/** The insert effects of a mixer track (or the master when [owner] is null): in series, top to bottom. */
@Composable
fun InsertsDialog(vm: DawViewModel, owner: TrackM?, onEdit: (TrackM) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val list = vm.fxOf(owner)
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Effects · " + (owner?.name ?: "Master")) }, text = {
        Column {
            Text("The audio goes through these effects in order. Each one adds about 3 ms; tracks stay in sync automatically.", color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) Text("No effect yet.", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            else LazyColumn(Modifier.heightIn(max = dialogListMax(330.dp))) {
                items(list.toList(), key = { it.id }) { fx ->
                    val i = list.indexOf(fx)
                    // The name gets the whole width of the dialog (a row with the buttons beside it left room for "…" only); the controls sit under it.
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { onEdit(fx) }, verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(when { fx.pluginState == 3 -> Zk.danger; fx.pluginState != 2 -> Zk.warn; fx.bypass -> Zk.dim; else -> Zk.accent }))
                            Spacer(Modifier.width(8.dp))
                            Text(fx.pluginName.ifBlank { fx.name }, color = if (fx.bypass) Zk.dim else Zk.text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Text(when { fx.pluginState == 3 -> "failed to start"; fx.pluginState != 2 -> "starting…"; fx.bypass -> "bypassed"; else -> "slot ${i + 1}" }, color = Zk.dim, fontSize = 11.sp, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            LabelButton(if (fx.bypass) "Off" else "On", !fx.bypass, w = 44.dp, h = 30.dp) { vm.setBypass(fx, !fx.bypass) }
                            LabelButton("Edit", false, w = 48.dp, h = 30.dp) { onEdit(fx) }
                            Spacer(Modifier.weight(1f))
                            TextButton({ vm.moveEffect(owner, fx, -1) }, enabled = i > 0, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.widthIn(min = 32.dp)) { Text("↑") }
                            TextButton({ vm.moveEffect(owner, fx, 1) }, enabled = i < list.size - 1, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.widthIn(min = 32.dp)) { Text("↓") }
                            TextButton({ vm.removeEffect(owner, fx) }, contentPadding = PaddingValues(horizontal = 6.dp), modifier = Modifier.widthIn(min = 32.dp)) { Text("✕", color = Zk.danger) }
                        }
                    }
                    HorizontalDivider(color = Zk.line)
                }
            }
        }
    }, confirmButton = { TextButton(onAdd, enabled = list.size < 10) { Text("Add effect…") } }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
}

/** Plugins that can be an effect: those known to be effects, and those whose type is not known yet. */
@Composable
fun EffectPickerDialog(vm: DawViewModel, owner: TrackM?, onInstall: () -> Unit, onDismiss: () -> Unit) {
    var plugins by remember { mutableStateOf(vm.scanPlugins()) }
    LaunchedEffect(vm.pluginsVersion) { plugins = vm.scanPlugins() }
    LaunchedEffect(Unit) { vm.probeUnknown(vm.scanPlugins()) }
    val effects = plugins.filter { it.kind != 1 }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Add effect · " + (owner?.name ?: "Master")) }, text = {
        Column {
            if (vm.probing) Text("Checking which plugins are effects…", color = Zk.accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            if (effects.isEmpty()) Text("No effect plugin found.\n\nInstall one with Install .exe, or copy its .vst3 or VST2 .dll into:\n${vm.pluginDir()?.path ?: ""}", color = Zk.dim, fontSize = 13.sp)
            else LazyColumn(Modifier.heightIn(max = dialogListMax(320.dp))) {
                val own = effects.filter { it.path.startsWith(com.zedokmusic.engine.PluginHost.NATIVE_PREFIX) }
                if (own.isNotEmpty()) item { Text("ZEDOK", color = Zk.accent, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
                items(own, key = { it.path }) { p ->
                    Column(Modifier.fillMaxWidth().clickable { vm.addEffect(owner, p); onDismiss() }.padding(vertical = 10.dp)) { Text(p.name, color = Zk.text); Text(p.blurb, color = Zk.dim, fontSize = 11.sp) }
                    HorizontalDivider(color = Zk.line)
                }
                val vst = effects.filter { !it.path.startsWith(com.zedokmusic.engine.PluginHost.NATIVE_PREFIX) }
                if (vst.isNotEmpty()) item { Text("WINDOWS PLUGINS", color = Zk.dim, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
                items(vst, key = { it.path }) { p ->
                    Row(Modifier.fillMaxWidth().clickable { vm.addEffect(owner, p); onDismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, Modifier.weight(1f), color = Zk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (p.kind == 2) "effect" else "type unknown", color = Zk.dim, fontSize = 11.sp)
                    }
                    HorizontalDivider(color = Zk.line)
                }
            }
        }
    }, confirmButton = { TextButton(onInstall) { Text("Install .exe…") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Chips to pick which plugin the Plugin / Auto panel shows: the track's instrument, its effects, and the master's effects. */
@Composable
fun PluginChooser(vm: DawViewModel, current: TrackM) {
    val sel = vm.selected
    val items = buildList {
        if (sel != null && sel.pluginPath != null) add("Instrument" to sel)
        sel?.fx?.forEach { add(("FX · " + it.name) to it) }
        vm.masterFx.forEach { add(("Master · " + it.name) to it) }
    }
    if (items.size < 2) return
    val short = LocalShort.current
    Row(Modifier.fillMaxWidth().background(Zk.panel).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = if (short) 2.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, t) ->
            val active = t === current
            Box(Modifier.clip(RoundedCornerShape(14.dp)).background(if (active) Zk.accent.copy(alpha = .2f) else Zk.panel2).clickable { vm.pluginTarget = t }.padding(horizontal = 10.dp, vertical = if (short) 3.dp else 6.dp)) {
                Text(label, color = if (active) Zk.accent else Zk.dim, fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}
