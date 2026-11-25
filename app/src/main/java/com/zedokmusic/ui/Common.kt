package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Glyph { Play, Pause, Stop, Loop, FitLoop, Plus, Minus, Keys, Close, Rec, Gear }

/** Small vector icons drawn directly so the app needs no icon library. */
@Composable
fun GlyphIcon(g: Glyph, color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        when (g) {
            Glyph.Play -> drawPath(Path().apply { moveTo(w * .2f, h * .1f); lineTo(w * .9f, h * .5f); lineTo(w * .2f, h * .9f); close() }, color)
            Glyph.Pause -> { drawRect(color, Offset(w * .2f, h * .12f), Size(w * .22f, h * .76f)); drawRect(color, Offset(w * .58f, h * .12f), Size(w * .22f, h * .76f)) }
            Glyph.Stop -> drawRect(color, Offset(w * .18f, h * .18f), Size(w * .64f, h * .64f))
            Glyph.Loop -> {
                val st = Stroke(width = w * .1f)
                drawRoundRect(color, Offset(w * .08f, h * .22f), Size(w * .84f, h * .56f), androidx.compose.ui.geometry.CornerRadius(w * .28f), style = st)
                drawPath(Path().apply { moveTo(w * .62f, h * .1f); lineTo(w * .86f, h * .22f); lineTo(w * .62f, h * .36f); close() }, color)
            }
            Glyph.FitLoop -> {  // a loop region closed by two brackets: [ <-> ]
                val st = w * .1f
                drawLine(color, Offset(w * .14f, h * .2f), Offset(w * .14f, h * .8f), st); drawLine(color, Offset(w * .86f, h * .2f), Offset(w * .86f, h * .8f), st)
                drawLine(color, Offset(w * .14f, h * .2f), Offset(w * .3f, h * .2f), st); drawLine(color, Offset(w * .14f, h * .8f), Offset(w * .3f, h * .8f), st)
                drawLine(color, Offset(w * .86f, h * .2f), Offset(w * .7f, h * .2f), st); drawLine(color, Offset(w * .86f, h * .8f), Offset(w * .7f, h * .8f), st)
                drawLine(color, Offset(w * .3f, h * .5f), Offset(w * .7f, h * .5f), st * .8f)
                drawPath(Path().apply { moveTo(w * .22f, h * .5f); lineTo(w * .36f, h * .38f); lineTo(w * .36f, h * .62f); close() }, color)
                drawPath(Path().apply { moveTo(w * .78f, h * .5f); lineTo(w * .64f, h * .38f); lineTo(w * .64f, h * .62f); close() }, color)
            }
            Glyph.Rec -> drawCircle(color, w * .34f, Offset(w / 2, h / 2))
            Glyph.Plus -> { drawRect(color, Offset(w * .44f, h * .12f), Size(w * .12f, h * .76f)); drawRect(color, Offset(w * .12f, h * .44f), Size(w * .76f, h * .12f)) }
            Glyph.Minus -> drawRect(color, Offset(w * .12f, h * .44f), Size(w * .76f, h * .12f))
            Glyph.Close -> { val st = Stroke(width = w * .12f); drawLine(color, Offset(w * .2f, h * .2f), Offset(w * .8f, h * .8f), st.width); drawLine(color, Offset(w * .8f, h * .2f), Offset(w * .2f, h * .8f), st.width) }
            Glyph.Gear -> {
                val st = Stroke(width = w * .1f); val cx = w / 2; val cy = h / 2
                drawCircle(color, w * .2f, Offset(cx, cy), style = st)
                for (i in 0 until 8) { val a = Math.PI / 4 * i; val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
                    drawLine(color, Offset(cx + dx * w * .3f, cy + dy * h * .3f), Offset(cx + dx * w * .44f, cy + dy * h * .44f), w * .14f) }
            }
            Glyph.Keys -> { drawRect(color, Offset(w * .08f, h * .2f), Size(w * .84f, h * .6f), style = Stroke(w * .08f)); for (i in 1..3) drawRect(color, Offset(w * (.08f + .21f * i) - w * .015f, h * .2f), Size(w * .03f, h * .6f)) }
        }
    }
}

@Composable
fun SquareButton(g: Glyph, active: Boolean = false, tint: Color = Zk.text, activeColor: Color = Zk.accent, size: Dp = 40.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(if (active && enabled) activeColor.copy(alpha = .22f) else Zk.panel2).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        GlyphIcon(g, if (!enabled) tint.copy(alpha = .28f) else if (active) activeColor else tint)
    }
}

@Composable
fun LabelButton(text: String, active: Boolean = false, activeColor: Color = Zk.accent, w: Dp = 30.dp, h: Dp = 26.dp, onClick: () -> Unit) {
    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(if (active) activeColor else Zk.panel2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = if (active) Color(0xFF00201B) else Zk.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

fun beatLabel(beat: Float): String {
    val bar = (beat / 4f).toInt() + 1; val b = (beat % 4f).toInt() + 1; val t = (((beat % 1f) * 100f).toInt()).coerceIn(0, 99)
    return "%03d.%d.%02d".format(bar, b, t)
}

/** Insets the screens keep clear of: system bars, and the notch unless "Use the whole screen" is on. */
@Composable
fun screenInsets(): androidx.compose.foundation.layout.WindowInsets {
    val bars = androidx.compose.foundation.layout.WindowInsets.systemBars
    return if (com.zedokmusic.settings.AppSettings.ignoreCutout.on) bars else bars.union(androidx.compose.foundation.layout.WindowInsets.displayCutout)
}

/** True in landscape on a phone: little vertical room, so every panel uses denser rows. */
val LocalShort = compositionLocalOf { false }

/** Max height for a scrolling list inside a dialog: [base] on tall windows, less when the window is short (landscape). */
@Composable
fun dialogListMax(base: Dp): Dp = minOf(base, (LocalConfiguration.current.screenHeightDp * 0.42f).dp)

// ---------- play cursor ----------

/** Draws the play cursor: a line across the whole height and, when [head] is set, a small triangle at the top (rulers). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayhead(x: Float, color: Color, head: Boolean) {
    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    if (head) {
        val w = 6.dp.toPx(); val h = 9.dp.toPx()
        drawPath(Path().apply { moveTo(x - w, 0f); lineTo(x + w, 0f); lineTo(x, h); close() }, color)
    }
}

/**
 * Keeps the play cursor in view: while playing, the timeline pages along when the cursor nears the right edge (or falls off the
 * left); when stopped at the start it returns to the beginning. Hand scrolling is never fought, and [vm.followPlayhead] turns it off.
 */
@Composable
fun FollowPlayhead(vm: com.zedokmusic.model.DawViewModel, scroll: androidx.compose.foundation.ScrollState, pxPerBeat: Float, viewportPx: Int) {
    val d = androidx.compose.ui.platform.LocalDensity.current.density
    val wasPlayingState = androidx.compose.runtime.remember { booleanArrayOf(vm.playing) }
    androidx.compose.runtime.LaunchedEffect(vm.playhead, vm.playing, vm.followPlayhead, viewportPx) {
        val stoppedNow = wasPlayingState[0] && !vm.playing; wasPlayingState[0] = vm.playing
        if (!vm.followPlayhead || viewportPx <= 0 || scroll.isScrollInProgress) return@LaunchedEffect
        val x = vm.playhead * pxPerBeat * d
        if (vm.playing) {
            if (x > scroll.value + viewportPx * 0.88f || x < scroll.value) scroll.scrollTo((x - viewportPx * 0.12f).toInt().coerceAtLeast(0))
        } else if (stoppedNow && vm.playhead == 0f && scroll.value != 0) scroll.scrollTo(0)  // only when Stop was just pressed: not when the screen is (re)opened
    }
}

/** Tap or drag on a ruler to move the play cursor. */
fun Modifier.scrubRuler(pxPerBeat: Float, onSeek: (Float) -> Unit): Modifier =
    this.pointerInput(pxPerBeat) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onSeek(down.position.x / density / pxPerBeat)
            do {
                val e = awaitPointerEvent()
                e.changes.firstOrNull()?.let { c -> if (c.pressed) { onSeek(c.position.x / density / pxPerBeat); c.consume() } }
            } while (e.changes.any { it.pressed })
        }
    }

/**
 * Double tap (two quick taps close together) resets a control to its default. It only watches the touches: the slider underneath
 * keeps working normally, so the first tap may still move it a little before the second one puts it back.
 */
fun Modifier.doubleTapReset(onReset: () -> Unit): Modifier = this.pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val near = 64.dp.toPx()
    var lastTapAt = 0L; var lastTapPos = androidx.compose.ui.geometry.Offset.Unspecified
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val t0 = System.currentTimeMillis()
        while (true) {
            val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
            val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((c.position - down.position).getDistance() > slop) return@awaitEachGesture  // a drag, not a tap
            if (!c.pressed) {
                val now = System.currentTimeMillis()
                if (now - t0 > 300) { lastTapAt = 0L; return@awaitEachGesture }
                if (lastTapAt != 0L && now - lastTapAt < 350 && lastTapPos != androidx.compose.ui.geometry.Offset.Unspecified && (c.position - lastTapPos).getDistance() < near) {
                    lastTapAt = 0L
                    // a moment later: controls that apply the touch on release (the faders) would otherwise overwrite the reset
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onReset() }, 40)
                } else { lastTapAt = now; lastTapPos = c.position }
                return@awaitEachGesture
            }
        }
    }
}
