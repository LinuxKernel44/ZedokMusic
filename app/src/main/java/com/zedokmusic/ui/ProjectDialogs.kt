package com.zedokmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.TrackM
import java.io.File
import java.text.DateFormat

private fun whenText(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(ms)
private fun mb(b: Long) = if (b >= 1L shl 30) "%.1f GB".format(b / 1073741824.0) else if (b >= 1L shl 20) "%.1f MB".format(b / 1048576.0) else "%d KB".format(b / 1024)

/** A single text field (project name, preset name). */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, hint: String? = null, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text(title) }, text = {
        Column {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            if (hint != null) { Spacer(Modifier.height(6.dp)); Text(hint, color = Zk.dim, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton({ if (text.isNotBlank()) { onConfirm(text.trim()); onDismiss() } }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** File → Open project: every project, most recently saved first. */
@Composable
fun ProjectsDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(vm.projects()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Open project") }, text = {
        if (list.isEmpty()) Text("No project yet.", color = Zk.dim)
        else LazyColumn(Modifier.heightIn(max = dialogListMax(380.dp))) {
            items(list, key = { it.name }) { p ->
                val current = p.name == vm.projectName
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(enabled = !current) { vm.openProject(p.name); onDismiss() }) {
                        Text(p.name + if (current) "  (open)" else "", color = if (current) Zk.accent else Zk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Saved " + whenText(p.modified), color = Zk.dim, fontSize = 11.sp)
                    }
                    if (!current) TextButton({ confirmDelete = p.name }) { Text("Delete", color = Zk.danger) }
                }
                HorizontalDivider(color = Zk.line)
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
    confirmDelete?.let { n ->
        AlertDialog(onDismissRequest = { confirmDelete = null }, containerColor = Zk.panel, title = { Text("Delete project \"$n\"?") },
            text = { Text("Its samples, exports, presets and backups are deleted too. This cannot be undone.", color = Zk.dim) },
            confirmButton = { TextButton({ vm.deleteProject(n); list = vm.projects(); confirmDelete = null }) { Text("Delete", color = Zk.danger) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancel") } })
    }
}

/** File → Project info: where everything is, how big, and the automatic backups. */
@Composable
fun ProjectInfoDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    val store = vm.store
    var backups by remember { mutableStateOf(vm.backups()) }
    var restore by remember { mutableStateOf<File?>(null) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text(vm.projectName) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Folder", color = Zk.dim, fontSize = 11.sp); Text(vm.projectDir()?.path ?: "-", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            if (store != null) listOf("Samples" to store.samples(vm.projectName), "Exports" to store.exports(vm.projectName), "Presets" to store.presets(vm.projectName), "Backup" to store.backups(vm.projectName)).forEach { (l, d) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(l, Modifier.weight(1f), fontSize = 13.sp); Text(mb(store.size(d)), color = Zk.dim, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Text("BACKUPS (A COPY IS KEPT WHEN YOU SAVE, AT MOST ONE EVERY 10 MINUTES)", color = Zk.accent, fontSize = 10.sp)
            if (backups.isEmpty()) Text("None yet.", color = Zk.dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            backups.take(6).forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(whenText(f.lastModified()), Modifier.weight(1f), fontSize = 13.sp)
                    TextButton({ restore = f }) { Text("Restore") }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } })
    restore?.let { f ->
        AlertDialog(onDismissRequest = { restore = null }, containerColor = Zk.panel, title = { Text("Restore this backup?") },
            text = { Text("The project goes back to its state of ${whenText(f.lastModified())}. The current state is kept as a backup first.", color = Zk.dim) },
            confirmButton = { TextButton({ vm.restoreBackup(f); restore = null; onDismiss() }) { Text("Restore") } },
            dismissButton = { TextButton({ restore = null }) { Text("Cancel") } })
    }
}

/** Plugin presets of this project: save the current sound, recall or delete earlier ones. */
@Composable
fun PresetsDialog(vm: DawViewModel, t: TrackM, onDismiss: () -> Unit) {
    var list by remember { mutableStateOf(vm.presets(t)) }
    var naming by remember { mutableStateOf(false) }
    var confirmInit by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("${t.pluginName.ifBlank { t.name }} presets") }, text = {
        Column {
            Text("Stored in this project's Presets folder.", color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Column(Modifier.fillMaxWidth().clickable { if (vm.isTouched(t)) confirmInit = true else { vm.initPlugin(t); onDismiss() } }.padding(vertical = 8.dp)) {
                Text("Init preset", color = Zk.accent, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text("Every control back to its default", color = Zk.dim, fontSize = 10.sp)
            }
            HorizontalDivider(color = Zk.line)
            if (list.isEmpty()) Text("No preset yet. Set the sound you like, then tap Save current.", color = Zk.dim, fontSize = 13.sp)
            else LazyColumn(Modifier.heightIn(max = dialogListMax(300.dp))) {
                items(list, key = { it.absolutePath }) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.nameWithoutExtension, Modifier.weight(1f).clickable { vm.loadPreset(t, f); onDismiss() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton({ vm.loadPreset(t, f); onDismiss() }) { Text("Load") }
                        TextButton({ vm.deletePreset(f); list = vm.presets(t) }) { Text("Delete", color = Zk.danger) }
                    }
                    HorizontalDivider(color = Zk.line)
                }
            }
        }
    }, confirmButton = { TextButton({ naming = true }) { Text("Save current…") } }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
    if (confirmInit) com.zedokmusic.ui.nplug.InitConfirmDialog(vm, t) { confirmInit = false; onDismiss() }
    if (naming) NameDialog("Save preset", "", "Save", onConfirm = { if (vm.savePreset(t, it)) list = vm.presets(t) }, onDismiss = { naming = false })
}
