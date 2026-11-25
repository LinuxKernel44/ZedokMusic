package com.zedokmusic.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.DawViewModel

/** The complete Windows desktop, full screen: one finger is the mouse, two fingers scroll or right-click, Keys opens the soft keyboard. */
@Composable
fun DesktopScreen(vm: DawViewModel) {
    if (!vm.desktopOpen && !vm.desktopStarting) return
    var keys by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    BackHandler { if (vm.desktopOpen) confirmExit = true }
    Surface(Modifier.fillMaxSize(), color = Zk.bg) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(screenInsets())) {
            Row(Modifier.fillMaxWidth().background(Zk.panel).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Windows desktop", color = Zk.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                LabelButton("Keys", keys, w = 60.dp, h = 32.dp) { keys = !keys }
                LabelButton("Close", false, Zk.danger, w = 64.dp, h = 32.dp) { confirmExit = true }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (vm.desktopOpen) key(vm.desktopW, vm.desktopH) { EditorSurface(vm.desktopW, vm.desktopH, 0, 0, keys) }
                else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(if (vm.desktopOpen) "" else "Starting the Windows desktop…", color = Zk.dim, fontSize = 13.sp)
                }
            }
        }
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, containerColor = Zk.panel, title = { Text("Close the Windows desktop?") },
        text = { Text("Programs still running in it are ended. Your project is loaded again with its plugins.", color = Zk.dim) },
        confirmButton = { TextButton({ confirmExit = false; vm.closeDesktop() }) { Text("Close", color = Zk.danger) } },
        dismissButton = { TextButton({ confirmExit = false }) { Text("Keep open") } })
}
