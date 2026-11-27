package com.zedokmusic.ui.nplug

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.TrackM
import com.zedokmusic.ui.Zk

/** The custom screen of a built-in plugin, under its preset bar. [generic] draws the plain parameter list instead (a switch in the bar). */
@Composable
fun NativePluginView(vm: DawViewModel, t: TrackM, generic: @Composable () -> Unit) {
    val id = vm.nativeId(t) ?: return
    var showList by remember(t.id) { mutableStateOf(false) }
    val color = accentOf(id)
    Column(Modifier.fillMaxSize()) {
        PresetBar(vm, t, color, showList) { showList = !showList }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showList) generic()
            else {
                val ps = rememberPluginState(vm, t)
                when (id) {
                    "hybrid" -> HybridScreen(ps)
                    "eqcomp" -> EqCompScreen(ps)
                    "space" -> SpaceScreen(ps)
                    "color" -> ColorScreen(ps)
                    "drums" -> DrumsScreen(ps)
                    "grain" -> GrainScreen(ps)
                    "sampler" -> SamplerScreen(ps)
                    else -> generic()
                }
            }
        }
    }
}

fun accentOf(id: String): Color = when (id) {
    "hybrid" -> Color(0xFF3DDCC4); "drums" -> Color(0xFFFF9F43); "eqcomp" -> Color(0xFFFFC857); "space" -> Color(0xFF8E7CFF); "color" -> Color(0xFFFF6B81); "grain" -> Color(0xFF7BE07B); "sampler" -> Color(0xFF4DA3FF)
    else -> Zk.accent
}
