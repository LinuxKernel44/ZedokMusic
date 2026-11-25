package com.zedokmusic.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
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
fun PluginEditor(vm: DawViewModel, t: TrackM, keyboard: Boolean = false) {
    var info by remember(t.id) { mutableStateOf(intArrayOf(0, 0, 0)) }
    LaunchedEffect(t.id) {
        vm.openEditor(t)
        while (true) { info = vm.editorState(t); delay(250) }
    }
    when {
        !vm.guiAvailable -> Message("This build has no GUI runtime. Reinstall the app to update the Windows runtime.")
        info[0] == 1 && info[1] > 0 && info[2] > 0 -> EditorSurface(info[1], info[2], info[3], info[4], keyboard)
        info[0] == 2 -> Message("This plugin could not open its editor.")
        else -> Message("Opening the editor…")
    }
}

@Composable
private fun Message(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, color = Zk.dim, fontSize = 13.sp) }

@Composable
fun EditorSurface(w: Int, h: Int, ox: Int, oy: Int, keyboard: Boolean) {
    val bmp = remember(w, h) { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    var tick by remember(bmp) { mutableIntStateOf(0) }
    LaunchedEffect(bmp, ox, oy) {
        while (true) { withContext(Dispatchers.Default) { XDisplay.fbBlit(bmp, ox, oy, w, h) }; tick++; delay(33) }
    }
    if (keyboard) KeyCapture()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.TopStart) {
        val maxWpx = with(LocalDensity.current) { maxWidth.toPx() }; val maxHpx = with(LocalDensity.current) { maxHeight.toPx() }
        val scale = minOf(maxWpx / w, maxHpx / h)
        val wDp = with(LocalDensity.current) { (w * scale).toDp() }; val hDp = with(LocalDensity.current) { (h * scale).toDp() }
        val paint = remember { Paint().apply { isFilterBitmap = true } }
        Canvas(Modifier.size(wDp, hDp).pointerInput(bmp, scale) {
            // One finger = left mouse button. Two fingers: drag = mouse wheel, quick tap = right click.
            awaitPointerEventScope {
                var leftDown = false; var multi = false
                var multiStart = 0L; var multiMoved = false; var lastY = 0f; var scrollAcc = 0f; var cx = 0; var cy = 0
                fun sx(v: Float) = ox + (v / scale).toInt().coerceIn(0, w - 1)
                fun sy(v: Float) = oy + (v / scale).toInt().coerceIn(0, h - 1)
                while (true) {
                    val ev = awaitPointerEvent()
                    val down = ev.changes.filter { it.pressed }
                    if (down.size >= 2) {
                        if (leftDown) { XDisplay.pointer(2, cx, cy, 1); leftDown = false }
                        val my = down.map { it.position.y }.average().toFloat(); val mx = down.map { it.position.x }.average().toFloat()
                        cx = sx(mx); cy = sy(my)
                        if (!multi) { multi = true; multiStart = System.currentTimeMillis(); multiMoved = false; lastY = my; scrollAcc = 0f }
                        else {
                            scrollAcc += my - lastY; lastY = my
                            if (kotlin.math.abs(scrollAcc) > 6f) multiMoved = true
                            val step = 14f * scale.coerceAtLeast(0.5f)
                            while (kotlin.math.abs(scrollAcc) >= step) {  // finger moving up scrolls content down, like a trackpad
                                val button = if (scrollAcc < 0) 5 else 4
                                XDisplay.pointer(1, cx, cy, button); XDisplay.pointer(2, cx, cy, button)
                                scrollAcc -= if (scrollAcc < 0) -step else step
                            }
                        }
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    if (multi) {
                        if (down.isEmpty()) {
                            if (!multiMoved && System.currentTimeMillis() - multiStart < 400) { XDisplay.pointer(1, cx, cy, 3); XDisplay.pointer(2, cx, cy, 3) }
                            multi = false
                        }
                        ev.changes.forEach { it.consume() }
                        continue
                    }
                    val c = ev.changes.firstOrNull() ?: continue
                    val x = sx(c.position.x); val y = sy(c.position.y); cx = x; cy = y
                    when (ev.type) {
                        PointerEventType.Press -> { XDisplay.pointer(0, x, y, 1); XDisplay.pointer(1, x, y, 1); leftDown = true }
                        PointerEventType.Move -> XDisplay.pointer(0, x, y, 1)
                        PointerEventType.Release -> if (leftDown) { XDisplay.pointer(2, x, y, 1); leftDown = false }
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

/** An invisible text field that owns the soft keyboard and forwards what is typed to the plugin editor as key presses. */
@Composable
private fun KeyCapture() {
    val focus = remember { FocusRequester() }
    var value by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }  // a sentinel space lets us see backspaces
    val sym = { code: Int -> XDisplay.tapKeysym(code) }
    BasicTextField(value, { nv ->
        val text = nv.text
        if (text.isEmpty()) sym(0xff08)
        else if (text.length > 1) text.substring(1).forEach { ch -> val ok = if (ch == '\n') sym(0xff0d) else XDisplay.typeChar(ch.code); if (!ok) android.util.Log.w("ZedokKeys", "no X key for U+%04X".format(ch.code)) }
        value = TextFieldValue(" ", TextRange(1))
    }, Modifier.size(1.dp).alpha(0.01f).focusRequester(focus).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val k = when (e.key) {
            Key.Enter -> 0xff0d; Key.Tab -> 0xff09; Key.Escape -> 0xff1b; Key.DirectionLeft -> 0xff51; Key.DirectionUp -> 0xff52
            Key.DirectionRight -> 0xff53; Key.DirectionDown -> 0xff54; Key.Delete -> 0xffff; else -> 0
        }
        if (k != 0) { sym(k); true } else false
    }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.None))
    LaunchedEffect(Unit) { focus.requestFocus() }
}
