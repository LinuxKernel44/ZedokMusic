package com.zedokmusic.ui.nplug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.KitManager
import com.zedokmusic.ui.LabelButton
import com.zedokmusic.ui.Zk
import com.zedokmusic.ui.dialogListMax

/** The kit tools of Zedok Drums: load a kit folder (one-shots, multikit, SFZ), add samples to the selected pad, switch between the kits of the project. */
@Composable
fun KitSection(ps: PluginState, pad: Int, color: Color) {
    val vm = ps.vm; val t = ps.t; val kits = vm.kits
    var projectKits by remember { mutableStateOf(false) }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) kits.scanTree(t, uri) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) kits.assignFiles(t, pad, uris) }
    val name = kits.kitName(t); val padsUsed = (0 until 16).count { kits.zones(t, it).isNotEmpty() }
    Section("Sample kit", color) {
        Text(if (name.isNotBlank() || padsUsed > 0) "${name.ifBlank { "Kit" }} · $padsUsed pad${if (padsUsed == 1) "" else "s"} with samples" else "No sample kit. Load a folder of drum samples, or add samples to a pad.", color = Zk.dim, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabelButton("Load kit…", true, color, w = 92.dp, h = 34.dp) { tree.launch(null) }
            LabelButton("Project kits", false, w = 100.dp, h = 34.dp) { projectKits = true }
            LabelButton("Add to pad ${pad + 1}…", false, w = 128.dp, h = 34.dp) { files.launch(arrayOf("audio/*", "application/octet-stream")) }
            LabelButton("Clear pad", false, w = 84.dp, h = 34.dp) { kits.clearPad(t, pad) }
            LabelButton("Clear kit", false, w = 84.dp, h = 34.dp) { kits.clearKit(t) }
        }
        val z = remember(t.states["pad${pad + 1}"]) { kits.zones(t, pad) }
        if (z.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Pad ${pad + 1}: ${z.size} sample${if (z.size > 1) "s" else ""}", color = Zk.text, fontSize = 12.sp)
            z.forEachIndexed { i, zone ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(zone.entry.name, color = Zk.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (zone.vlo > 1 || zone.vhi < 127) "vel ${zone.vlo}–${zone.vhi}" else "all", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    Text("✕", color = Zk.danger, fontSize = 14.sp, modifier = Modifier.clickable { kits.removeZone(t, pad, i) }.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Text("Samples that share a velocity range play in turn (round robin).", color = Zk.dim, fontSize = 10.sp)
        }
    }
    if (projectKits) ProjectKitsDialog(kits, ps, color) { projectKits = false }
}

@Composable
fun KitJobDialog(kits: KitManager, color: Color) {
    when (kits.phase) {
        KitManager.Phase.Idle -> {}
        KitManager.Phase.Scanning -> AlertDialog(onDismissRequest = {}, containerColor = Zk.panel, title = { Text("Reading the folder") },
            text = { Column { Text(kits.status, color = Zk.dim, fontSize = 13.sp); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) } },
            confirmButton = {}, dismissButton = { TextButton({ kits.cancel() }) { Text("Cancel") } })
        KitManager.Phase.Choose -> AlertDialog(onDismissRequest = { kits.dismiss() }, containerColor = Zk.panel, title = { Text("Which kit?") },
            text = {
                Column {
                    Text("This folder holds several kits. Pick one: its files are copied into your project.", color = Zk.dim, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = dialogListMax(320.dp))) {
                        items(kits.candidates) { c ->
                            Column(Modifier.fillMaxWidth().clickable { kits.import(c) }.padding(vertical = 10.dp)) {
                                Text(c.name, color = Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.kind} · ${c.count} file${if (c.count == 1) "" else "s"}", color = Zk.dim, fontSize = 11.sp)
                            }
                            HorizontalDivider(color = Zk.line)
                        }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton({ kits.dismiss() }) { Text("Cancel") } })
        KitManager.Phase.Importing -> AlertDialog(onDismissRequest = {}, containerColor = Zk.panel, title = { Text("Importing the kit") },
            text = { Column { Text(kits.status, color = Zk.dim, fontSize = 13.sp); Spacer(Modifier.height(10.dp)); LinearProgressIndicator(progress = { kits.progress }, Modifier.fillMaxWidth(), color = color) } },
            confirmButton = {}, dismissButton = { TextButton({ kits.cancel() }) { Text("Cancel") } })
        KitManager.Phase.Failed -> AlertDialog(onDismissRequest = { kits.dismiss() }, containerColor = Zk.panel, title = { Text("Kit import") },
            text = { Text(kits.status, color = Zk.dim, fontSize = 13.sp) }, confirmButton = { TextButton({ kits.dismiss() }) { Text("OK") } })
    }
}

@Composable
private fun ProjectKitsDialog(kits: KitManager, ps: PluginState, color: Color, onDismiss: () -> Unit) {
    val list = remember(kits.revision) { kits.projectKits() }
    var confirmDelete by remember { mutableStateOf<java.io.File?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Kits in this project") }, text = {
        Column {
            Text("Every kit you import is copied into the project's Kits folder. Tap one to put it on the pads.", color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            if (list.isEmpty()) Text("No kit yet. Use Load kit… to import one.", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
            else LazyColumn(Modifier.heightIn(max = dialogListMax(300.dp))) {
                items(list, key = { it.second.absolutePath }) { (name, dir) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { kits.loadProjectKit(ps.t, dir); onDismiss() }) {
                            Text(name, color = if (name == kits.kitName(ps.t)) color else Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${dir.walkTopDown().count { it.isFile && it.extension != "zkit" }} files", color = Zk.dim, fontSize = 11.sp)
                        }
                        TextButton({ confirmDelete = dir }) { Text("Delete", color = Zk.danger) }
                    }
                    HorizontalDivider(color = Zk.line)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
    confirmDelete?.let { d ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, containerColor = Zk.panel, title = { Text("Delete this kit?") },
            text = { Text("Its audio files are removed from the project folder. A track that still uses them goes silent on those pads.", color = Zk.dim, fontSize = 13.sp) },
            confirmButton = { TextButton({ kits.deleteProjectKit(d); confirmDelete = null }) { Text("Delete", color = Zk.danger) } }, dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
}
