package com.zedokmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.ui.DawScreen
import com.zedokmusic.ui.Onboarding
import com.zedokmusic.ui.ZedokTheme

class MainActivity : ComponentActivity() {
    /** Saves the project (with zoom and scroll positions) whenever the app leaves the screen: Android may end the process without warning. */
    override fun onStop() { super.onStop(); com.zedokmusic.model.DawViewModel.current?.flush() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.zedokmusic.settings.AppSettings.init(this)
        com.zedokmusic.update.Updater.init(application)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)  // the app lays itself out around the insets (see screenInsets), so it can also draw under the notch
        val autoFolder = DebugHooks.autoFolder(intent)
        setContent {
            ZedokTheme {
                var ready by remember { mutableStateOf(false) }
                if (!ready) Onboarding(autoFolder) { ready = true } else DawScreen(viewModel<DawViewModel>())
            }
        }
        DebugHooks.install(this, intent)
    }
}
