package com.zedokmusic.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.update.Updater
import com.zedokmusic.update.Updater.Phase

private fun mb(b: Long) = if (b >= 1L shl 20) "%.1f MB".format(b / 1048576.0) else "%d KB".format(b / 1024)

/** Shown when a newer version exists (automatic check or "Check for updates"), and while it downloads. Closes itself when idle. */
@Composable
fun UpdateDialog(vm: DawViewModel, open: Boolean, onClose: () -> Unit) {
    val phase = Updater.phase
    val r = Updater.release
    val show = open && (phase == Phase.Available || phase == Phase.Downloading || phase == Phase.Verifying || phase == Phase.Ready || phase == Phase.NeedsPermission || (phase == Phase.Failed && r != null))
    if (!show || r == null) return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Coming back from the "install unknown apps" screen continues where we stopped.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) Updater.resumeAfterPermission() }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    AlertDialog(onDismissRequest = { if (phase != Phase.Downloading && phase != Phase.Verifying) onClose() }, containerColor = Zk.panel,
        title = { Text("Update ${r.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("You have ${Updater.installedVersionName()}.  ·  ${mb(r.apkSize)}" + if (r.prerelease) "  ·  pre-release" else "", color = Zk.dim, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                when (phase) {
                    Phase.Downloading -> {
                        val p = if (Updater.total > 0) Updater.downloaded.toFloat() / Updater.total else 0f
                        LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text("${mb(Updater.downloaded)} of ${mb(Updater.total)}", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Phase.Verifying -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking the file…", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Phase.Ready -> Text("Downloaded and checked: the file matches its checksum and is signed by the same key as this app. Your project is saved before the installer opens.", color = Zk.accent, fontSize = 13.sp)
                    Phase.NeedsPermission -> Text("Android needs your permission to let ZedokMusic install updates. Allow it on the screen that just opened, then come back.", color = Zk.warn, fontSize = 13.sp)
                    Phase.Failed -> Text(Updater.error ?: "Something went wrong.", color = Zk.danger, fontSize = 13.sp)
                    else -> if (Updater.metered()) Text("You are on mobile data: the download is ${mb(r.apkSize)}.", color = Zk.warn, fontSize = 13.sp)
                }
                if (r.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("WHAT'S NEW", color = Zk.accent, fontSize = 10.sp)
                    Text(r.notes.replace(Regex("[*_`#]"), "").trim(), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            when (phase) {
                Phase.Available, Phase.Failed -> TextButton({ Updater.download() }) { Text(if (phase == Phase.Failed) "Try again" else "Download") }
                Phase.Ready, Phase.NeedsPermission -> TextButton({ Updater.install { vm.save() } }) { Text("Install") }
                Phase.Downloading -> TextButton({ Updater.cancelDownload() }) { Text("Cancel download") }
                else -> {}
            }
        },
        dismissButton = {
            if (phase == Phase.Available) Row {
                TextButton({ Updater.skipThisVersion(); onClose() }) { Text("Skip this version") }
                TextButton(onClose) { Text("Later") }
            } else if (phase != Phase.Downloading && phase != Phase.Verifying) TextButton(onClose) { Text("Close") }
        })
}
