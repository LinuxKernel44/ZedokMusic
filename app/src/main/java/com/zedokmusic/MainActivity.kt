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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.zedokmusic.settings.AppSettings.init(this)
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
