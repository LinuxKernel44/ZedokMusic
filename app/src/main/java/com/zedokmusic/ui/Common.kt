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
                // A cog wheel drawn from scratch: a round body with 8 trapezoid teeth and a hole in the middle.
                val cx = w / 2; val cy = h / 2; val rOut = w * .46f; val rRoot = w * .34f; val rHole = w * .15f
                val teeth = 8; val step = 2 * Math.PI / teeth
                val path = androidx.compose.ui.graphics.Path()
                for (i in 0 until teeth) {
                    val c = step * i - Math.PI / 2
                    val pts = listOf(c - step * .28 to rRoot, c - step * .17 to rOut, c + step * .17 to rOut, c + step * .28 to rRoot)
                    for ((k, pt) in pts.withIndex()) { val x = cx + (Math.cos(pt.first) * pt.second).toFloat(); val y = cy + (Math.sin(pt.first) * pt.second).toFloat(); if (i == 0 && k == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                }
                path.close()
                path.addOval(androidx.compose.ui.geometry.Rect(cx - rHole, cy - rHole, cx + rHole, cy + rHole))
                path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                drawPath(path, color)
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
fun LabelButton(text: String, active: Boolean = false, activeColor: Color = Zk.accent, w: Dp = 30.dp, h: Dp = 26.dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(w, h).clip(RoundedCornerShape(6.dp)).background(if (active && enabled) activeColor else Zk.panel2).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier), contentAlignment = Alignment.Center) {
        Text(text, color = if (!enabled) Zk.dim.copy(alpha = .35f) else if (active) Color(0xFF00201B) else Zk.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
    val base = if (com.zedokmusic.settings.AppSettings.ignoreCutout.on) bars else bars.union(androidx.compose.foundation.layout.WindowInsets.displayCutout)
    // Rounded screen corners cut the edges of the top bar and the sides: keep a margin there (automatic from the phone's corner radius, or chosen in Settings -> Display).
    val view = androidx.compose.ui.platform.LocalView.current; val density = androidx.compose.ui.platform.LocalDensity.current
    val setting = com.zedokmusic.settings.AppSettings.cornerMargin.value
    val margin = when (setting) {
        1 -> 0.dp; 2 -> 12.dp; 3 -> 24.dp; 4 -> 36.dp
        else -> if (android.os.Build.VERSION.SDK_INT >= 31) {
            val ins = view.rootWindowInsets
            val r = listOf(android.view.RoundedCorner.POSITION_TOP_LEFT, android.view.RoundedCorner.POSITION_TOP_RIGHT, android.view.RoundedCorner.POSITION_BOTTOM_LEFT, android.view.RoundedCorner.POSITION_BOTTOM_RIGHT)
                .maxOf { ins?.getRoundedCorner(it)?.radius ?: 0 }
            with(density) { (r * 0.6f).toDp() }
        } else 0.dp
    }
    return if (margin > 0.dp) base.union(androidx.compose.foundation.layout.WindowInsets(left = margin, right = margin)) else base
}

/** True in landscape on a phone: little vertical room, so every panel uses denser rows. */
val LocalShort = compositionLocalOf { false }

/** Max height for a scrolling list inside a dialog: [base] on tall windows, less when the window is short (landscape). */
@Composable
fun dialogListMax(base: Dp): Dp = minOf(base, (LocalConfiguration.current.screenHeightDp * 0.42f).dp)

// ---------- grid alignment ----------

/** A position in pixels snapped to a whole pixel: everything on a timeline (ruler, grid lines, clips, notes, play cursor) uses it, so they line up exactly. */
fun gridPx(x: Float): Float = Math.round(x).toFloat()
/** Where to draw a 1 px grid line for position [x]: the middle of that pixel (a crisp line, never straddling two pixels). */
fun gridLineX(x: Float): Float = gridPx(x) + 0.5f

// ---------- play cursor ----------

/** Draws the play cursor: a line across the whole height and, when [head] is set, a small triangle at the top (rulers). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayhead(px: Float, color: Color, head: Boolean) {
    val x = gridPx(px)
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
            var last = down.position.x
            onSeek(down.position.x / density / pxPerBeat)
            do {
                val e = awaitPointerEvent()
                // A touch screen reports pressure and size changes of a finger that does not move: seek only when it did, otherwise
                // playback would restart from the same spot over and over (silence, and a cursor that seems frozen).
                e.changes.firstOrNull()?.let { c -> if (c.pressed) { if (c.position.x != last) { last = c.position.x; onSeek(c.position.x / density / pxPerBeat) }; c.consume() } }
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
