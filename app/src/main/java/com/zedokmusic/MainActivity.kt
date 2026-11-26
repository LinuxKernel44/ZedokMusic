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
        // Display settings apply from the very first screen (the setup screen too), not only once the studio is open.
        run {
            val st = com.zedokmusic.settings.AppSettings
            requestedOrientation = when (st.orientation.value) { 1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; 2 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE; else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            if (st.ignoreCutout.on) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30) android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)  // the app lays itself out around the insets (see screenInsets), so it can also draw under the notch
        if (com.zedokmusic.settings.AppSettings.immersive.on) androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).let { c -> c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
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
