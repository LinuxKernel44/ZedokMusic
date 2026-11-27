package com.zedokmusic.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zedokmusic.model.*
import com.zedokmusic.settings.AppSettings

/**
 * The arrangement with the sound browser: docked on its left, or a drawer over it (the ⇄ button of the browser switches; a phone held upright always
 * uses the drawer). While a sound is being dragged the drawer slides mostly out of the way so the tracks are visible, and it closes after a drop.
 */
@Composable
fun ArrangeHost(vm: DawViewModel, ppb: Float, onZoom: (Float) -> Unit, onEdit: (TrackM) -> Unit, onImport: (TrackM) -> Unit) {
    val dnd = LocalDnd.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 600.dp
        val docked = vm.browserOpen && AppSettings.browserDocked.on && !narrow
        val drawer = vm.browserOpen && !docked
        val panelW = minOf(300.dp, maxWidth * 0.82f)
        DisposableEffect(drawer) {
            val l: (Boolean) -> Unit = { ok -> if (ok && drawer) vm.browserOpen = false }
            dnd.finishListeners.add(l); onDispose { dnd.finishListeners.remove(l) }
        }
        Row(Modifier.fillMaxSize()) {
            if (docked) { SoundBrowser(vm, Modifier.width(300.dp).fillMaxHeight(), canSwitch = true) { vm.browserOpen = false }; VerticalDivider(color = Zk.line) }
            Box(Modifier.weight(1f).fillMaxHeight()) { ArrangeView(vm, ppb, onZoom, onEdit, onImport) }
        }
        if (drawer) {
            val shift by animateDpAsState(if (dnd.payload != null) -(panelW * 0.64f) else 0.dp, label = "drawer")
            SoundBrowser(vm, Modifier.width(panelW).fillMaxHeight().offset(x = shift), canSwitch = !narrow) { vm.browserOpen = false }
        }
    }
}

/** What a drop does. */
fun handleDrop(vm: DawViewModel, dnd: DndState, p: DragPayload, h: Hover) {
    val kits = vm.kits
    when (h) {
        is Hover.Lane -> {
            val t = h.track
            when {
                p.plugin != null -> vm.addEffect(t, p.plugin)
                p.isFolder -> p.folderImport?.invoke(t)
                t.kind == TrackKind.Audio -> kits.addAsClips(t, p.sounds, h.beat)
                vm.nativeId(t) == "sampler" || vm.nativeId(t) == "grain" -> kits.loadAsSample(t, p.sounds)
                else -> { val pad = (0 until 16).firstOrNull { kits.zones(t, it).isEmpty() } ?: 0; kits.dropOnPads(t, pad, p.sounds) }
            }
        }
        Hover.NewTrack -> when {
            p.plugin != null -> vm.addInstrument(p.plugin)
            p.isFolder -> vm.nativePlugins().firstOrNull { it.path == "zedok:drums" }?.let { info -> vm.addInstrument(info); vm.selected?.let { t -> p.folderImport?.invoke(t) } }
            else -> vm.addAudioTrack()?.let { t -> kits.addAsClips(t, p.sounds, 0f) }
        }
        is Hover.Pad -> dnd.lastDrums?.let { t -> kits.dropOnPads(t, h.n - 1, p.sounds) }
        Hover.PadGrid -> {}
    }
}
