package com.zedokmusic.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.engine.XDisplay
import com.zedokmusic.model.DawViewModel
import com.zedokmusic.model.TrackM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The plugin's own editor (a Windows window drawn by Wine on the embedded X screen), mirrored live and driven by touch. */
@Composable
fun PluginEditor(vm: DawViewModel, t: TrackM) {
    var info by remember(t.id) { mutableStateOf(intArrayOf(0, 0, 0)) }
    LaunchedEffect(t.id) {
        vm.openEditor(t)
        while (true) { info = vm.editorState(t); delay(250) }
    }
    DisposableEffect(t.id) { onDispose { vm.closeEditor(t) } }
    when {
        !vm.guiAvailable -> Message("This build has no GUI runtime. Reinstall the app to update the Windows runtime.")
        info[0] == 1 && info[1] > 0 && info[2] > 0 -> EditorSurface(info[1], info[2])
        info[0] == 2 -> Message("This plugin could not open its editor.")
        else -> Message("Opening the editor…")
    }
}

@Composable
private fun Message(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Zk.dim, fontSize = 13.sp) }

@Composable
private fun EditorSurface(w: Int, h: Int) {
    val bmp = remember(w, h) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    var tick by remember(bmp) { mutableIntStateOf(0) }
    LaunchedEffect(bmp) {
        while (true) { withContext(Dispatchers.Default) { XDisplay.fbBlit(bmp, 0, 0, w, h) }; tick++; delay(33) }
    }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopStart) {
        val maxWpx = with(LocalDensity.current) { maxWidth.toPx() }; val maxHpx = with(LocalDensity.current) { maxHeight.toPx() }
        val scale = minOf(maxWpx / w, maxHpx / h)
        val wDp = with(LocalDensity.current) { (w * scale).toDp() }; val hDp = with(LocalDensity.current) { (h * scale).toDp() }
        val paint = remember { Paint().apply { isFilterBitmap = true } }
        Canvas(Modifier.size(wDp, hDp).pointerInput(bmp, scale) {
            awaitPointerEventScope {
                var down = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull() ?: continue
                    val x = (c.position.x / scale).toInt().coerceIn(0, w - 1); val y = (c.position.y / scale).toInt().coerceIn(0, h - 1)
                    when (ev.type) {
                        PointerEventType.Press -> { XDisplay.pointer(0, x, y); XDisplay.pointer(1, x, y); down = true }
                        PointerEventType.Move -> XDisplay.pointer(0, x, y)
                        PointerEventType.Release -> if (down) { XDisplay.pointer(2, x, y); down = false }
                        else -> {}
                    }
                    c.consume()
                }
            }
        }) {
            tick  // redraw whenever a new frame has been copied
            drawIntoCanvas { it.nativeCanvas.drawBitmap(bmp, Rect(0, 0, w, h), Rect(0, 0, size.width.toInt(), size.height.toInt()), paint) }
        }
    }
}
