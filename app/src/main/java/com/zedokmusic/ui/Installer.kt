package com.zedokmusic.ui

import androidx.activity.compose.BackHandler
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
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.wine.GuiServer
import java.io.File

/** Lists the installers in the workspace's Installers folder, imports new ones, and runs them. */
@Composable
fun InstallersDialog(vm: DawViewModel, onImport: () -> Unit, onDismiss: () -> Unit) {
    var files by remember { mutableStateOf(vm.installerFiles()) }
    var confirm by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(vm.installerCopying) { files = vm.installerFiles() }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Install a Windows plugin") }, text = {
        Column {
            Text("Run a plugin's Windows installer (.exe or .msi). It is shown here and you use it with touch; the plugin ends up in Add → Instrument track.",
                color = Zk.dim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) Text("No installer yet. Tap Import to pick one, or copy it into:\n${vm.installersDir()?.path ?: ""}", color = Zk.dim, fontSize = 13.sp)
            else LazyColumn(Modifier.heightIn(max = dialogListMax(300.dp))) {
                items(files, key = { it.absolutePath }) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { vm.runInstaller(f); onDismiss() }) {
                            Text(f.name, color = Zk.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("%.1f MB".format(f.length() / 1048576.0), color = Zk.dim, fontSize = 11.sp)
                        }
                        TextButton({ vm.runInstaller(f); onDismiss() }) { Text("Run") }
                        TextButton({ confirm = f }) { Text("Delete", color = Zk.danger) }
                    }
                    HorizontalDivider(color = Zk.line)
                }
            }
            if (vm.installerCopying) { Spacer(Modifier.height(8.dp)); Text("Copying…", color = Zk.accent, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onImport, enabled = !vm.installerCopying) { Text("Import…") } }, dismissButton = { TextButton(onDismiss) { Text("Close") } })
    confirm?.let { f ->
        AlertDialog(onDismissRequest = { confirm = null }, containerColor = Zk.panel, title = { Text("Delete ${f.name}?") },
            confirmButton = { TextButton({ vm.deleteInstaller(f); files = vm.installerFiles(); confirm = null }) { Text("Delete", color = Zk.danger) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

/** Full-screen view of the installer: the Windows desktop mirrored live, driven by touch (one finger = mouse, two = scroll / right click). */
@Composable
fun InstallerScreen(vm: DawViewModel) {
    val name = vm.installerName ?: return
    var keys by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(true) }
    var confirmStop by remember { mutableStateOf(false) }
    BackHandler { if (vm.installerRunning) confirmStop = true else vm.closeInstaller() }
    Surface(Modifier.fillMaxSize(), color = Zk.bg) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(screenInsets())) {
            Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = Zk.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (vm.installerRunning) "Running — tap and type like on a Windows PC" else "Finished", color = if (vm.installerRunning) Zk.accent else Zk.dim, fontSize = 11.sp)
                }
                LabelButton("Zoom", zoom, w = 64.dp, h = 32.dp) { zoom = !zoom }
                LabelButton("Keys", keys, w = 60.dp, h = 32.dp) { keys = !keys }
                if (vm.installerRunning) LabelButton("Stop", false, Zk.danger, w = 60.dp, h = 32.dp) { confirmStop = true }
                else LabelButton("Close", true, w = 64.dp, h = 32.dp) { vm.closeInstaller() }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.installerRunning) {
                    // Windows centre themselves on the 1600x3000 desktop: show the middle of it, closer or wider.
                    val w = if (zoom) GuiServer.INSTALLER_ZOOM_W else GuiServer.INSTALLER_VIEW_W
                    val h = if (zoom) GuiServer.INSTALLER_ZOOM_H else GuiServer.INSTALLER_VIEW_H
                    key(w) { EditorSurface(w, h, (GuiServer.WIDTH - w) / 2, GuiServer.HEIGHT / 2 - h / 2, keys) }
                }
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("The installer has ended.", color = Zk.text, fontSize = 15.sp) }
            }
        }
    }
    if (confirmStop) AlertDialog(onDismissRequest = { confirmStop = false }, containerColor = Zk.panel, title = { Text("Stop the installer?") },
        text = { Text("An unfinished installation may leave the plugin incomplete.", color = Zk.dim) },
        confirmButton = { TextButton({ confirmStop = false; vm.stopInstaller() }) { Text("Stop", color = Zk.danger) } },
        dismissButton = { TextButton({ confirmStop = false }) { Text("Keep running") } })
}
