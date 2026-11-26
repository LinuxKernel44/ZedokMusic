package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.PluginInfo

private fun kindLabel(k: Int) = when (k) { 1 -> "instrument"; 2 -> "effect"; else -> "not known" }

/**
 * Plugin manager (Settings → Plugins): how each Windows plugin is offered. A plugin that reports the wrong type (an effect seen as an instrument, say)
 * can be set to Instrument, Effect or Both; Re-detect asks the plugin again; Hide removes it from the pickers without deleting it.
 */
@Composable
fun PluginManagerDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    val list = remember(vm.pluginsVersion) { vm.scanPlugins(includeHidden = true) }
    val builtIn = list.filter { it.path.startsWith(com.zedokmusic.engine.PluginHost.NATIVE_PREFIX) }
    val windows = list.filter { !it.path.startsWith(com.zedokmusic.engine.PluginHost.NATIVE_PREFIX) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Zk.bg).statusBarsPadding().navigationBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Plugin manager", color = Zk.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (vm.probing) Text("Checking…", color = Zk.accent, fontSize = 12.sp)
                LabelButton("Rescan", false, w = 66.dp, h = 32.dp) { vm.refreshPlugins(); vm.probeUnknown(vm.scanPlugins()) }
                LabelButton("Close", true, w = 62.dp, h = 32.dp, onClick = onDismiss)
            }
            Spacer(Modifier.height(6.dp))
            Text("A plugin that was seen as the wrong kind (an effect listed as an instrument, for example) can be set here. Tracks and effects you already created keep working; the choice applies to the lists you pick plugins from.", color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                if (windows.isEmpty()) item { Text("No Windows plugin found yet. Install one (Add → Install plugin) or copy its .vst3 into ${vm.pluginDir()?.path ?: "the Plugins folder"}.", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp)) }
                items(windows, key = { it.path }) { p -> PluginRow(vm, p); HorizontalDivider(color = Zk.line) }
                if (builtIn.isNotEmpty()) {
                    item { Text("BUILT-IN (ZEDOK)", color = Zk.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                    items(builtIn, key = { it.path }) { p ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(p.name, color = Zk.text, fontSize = 14.sp); Text(p.blurb, color = Zk.dim, fontSize = 11.sp) }
                            Text(kindLabel(p.kind), color = Zk.dim, fontSize = 12.sp)
                        }
                        HorizontalDivider(color = Zk.line)
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(vm: DawViewModel, p: PluginInfo) {
    val ov = vm.pluginOverride(p.path)
    val selected = when (ov) { null -> 0; 1 -> 1; 2 -> 2; else -> 3 }   // Auto, Instrument, Effect, Both
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, color = if (p.hidden) Zk.dim else Zk.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Reports: ${kindLabel(p.detected)}" + if (p.hidden) " · hidden" else "", color = Zk.dim, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Auto" to null, "Instrument" to 1, "Effect" to 2, "Both" to 0).forEachIndexed { i, (label, v) ->
                val on = i == selected
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Zk.accent else Zk.panel2).clickable { vm.setPluginOverride(p.path, v) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(label, color = if (on) Color(0xFF00201B) else Zk.dim, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ vm.redetectPlugin(p.path) }, enabled = !vm.probing) { Text("Re-detect", fontSize = 12.sp) }
            TextButton({ vm.setPluginHidden(p.path, !p.hidden) }) { Text(if (p.hidden) "Show in lists" else "Hide from lists", fontSize = 12.sp) }
        }
    }
}
