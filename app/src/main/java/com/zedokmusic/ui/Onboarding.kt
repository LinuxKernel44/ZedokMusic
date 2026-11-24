package com.zedokmusic.ui

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zedokmusic.wine.PrefixManager
import com.zedokmusic.wine.RootfsInstaller
import com.zedokmusic.wine.StorageFolder
import java.io.File

private enum class Step { Checking, Welcome, Installing, ChooseFolder, SettingUp, Done, Error }

/** First-run flow: install the bundled Windows runtime, pick the workspace folder, create the Windows environment. */
@Composable
fun Onboarding(autoFolder: File? = null, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(Step.Checking) }
    var progress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf(StorageFolder.saved(ctx) ?: autoFolder) }

    fun runSetup(dir: File) {
        step = Step.SettingUp; message = "Creating Windows environment…"; progress = 0f
        Thread {
            val err = StorageFolder.prepare(dir) ?: run { StorageFolder.save(ctx, dir); PrefixManager.setup(ctx, dir) { p, m -> progress = p; message = m } }
            if (err != null) { error = err; step = Step.Error } else step = Step.Done
        }.start()
    }
    fun runInstall() {
        step = Step.Installing; progress = 0f
        Thread {
            val err = RootfsInstaller.install(ctx) { progress = it }
            if (err != null) { error = err; step = Step.Error }
            else { val f = folder; if (f != null) runSetup(f) else step = Step.ChooseFolder }
        }.start()
    }
    LaunchedEffect(Unit) {
        val installed = RootfsInstaller.isInstalled(ctx)
        val f = folder
        step = when {
            installed && f != null && PrefixManager.isReady(ctx, f) -> Step.Done
            else -> Step.Welcome
        }
        if (step == Step.Welcome && autoFolder != null) { if (installed) runSetup(autoFolder) else runInstall() }
    }
    LaunchedEffect(step) { if (step == Step.Done) onDone() }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runInstall() else { error = "ZedokMusic needs storage access to keep your Windows files and plugins in the folder you choose."; step = Step.Error }
    }
    fun startInstall() {
        val ok = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (ok) runInstall() else permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri: Uri? = r.data?.data
        if (r.resultCode == Activity.RESULT_OK && uri != null) {
            val dir = StorageFolder.resolve(uri)
            if (dir == null) { error = "That location is not on local storage. Choose a folder on your phone or SD card."; step = Step.Error }
            else { folder = dir; runSetup(dir) }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ZedokMusic", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(24.dp))
            when (step) {
                Step.Checking -> CircularProgressIndicator()
                Step.Welcome -> {
                    Text("Set up your studio", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("ZedokMusic runs real Windows VST2/VST3 plugins. First it installs a compact Windows runtime, then it asks where to keep your Windows files, plugins and projects.")
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { startInstall() }) { Text("Install") }
                }
                Step.Installing -> { Text("Installing Windows runtime…"); Spacer(Modifier.height(16.dp)); LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()); Text("${(progress * 100).toInt()}%") }
                Step.ChooseFolder -> {
                    Text("Choose your ZedokMusic folder", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text("Windows' C: drive, your plugins, projects and samples will live in this folder, so you can see and back them up. It needs about 400 MB.\n\nAndroid does not allow the top level of your storage: pick an existing folder or create a new one (for example “ZedokMusic”).")
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) }) { Text("Choose folder") }
                }
                Step.SettingUp -> { Text(message); Spacer(Modifier.height(16.dp)); LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth()); Text("This takes about a minute, once.") }
                Step.Done -> CircularProgressIndicator()
                Step.Error -> {
                    Text("Something went wrong", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text(error)
                    Spacer(Modifier.height(24.dp)); Button(onClick = { step = Step.Welcome }) { Text("Try again") }
                }
            }
        }
    }
}
