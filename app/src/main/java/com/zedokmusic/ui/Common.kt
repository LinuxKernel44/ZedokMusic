package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

enum class Glyph { Play, Pause, Stop, Loop, Plus, Minus, Keys, Close, Rec }

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
            Glyph.Rec -> drawCircle(color, w * .34f, Offset(w / 2, h / 2))
            Glyph.Plus -> { drawRect(color, Offset(w * .44f, h * .12f), Size(w * .12f, h * .76f)); drawRect(color, Offset(w * .12f, h * .44f), Size(w * .76f, h * .12f)) }
            Glyph.Minus -> drawRect(color, Offset(w * .12f, h * .44f), Size(w * .76f, h * .12f))
            Glyph.Close -> { val st = Stroke(width = w * .12f); drawLine(color, Offset(w * .2f, h * .2f), Offset(w * .8f, h * .8f), st.width); drawLine(color, Offset(w * .8f, h * .2f), Offset(w * .2f, h * .8f), st.width) }
            Glyph.Keys -> { drawRect(color, Offset(w * .08f, h * .2f), Size(w * .84f, h * .6f), style = Stroke(w * .08f)); for (i in 1..3) drawRect(color, Offset(w * (.08f + .21f * i) - w * .015f, h * .2f), Size(w * .03f, h * .6f)) }
        }
    }
}

@Composable
fun SquareButton(g: Glyph, active: Boolean = false, tint: Color = Zk.text, activeColor: Color = Zk.accent, size: Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(if (active) activeColor.copy(alpha = .22f) else Zk.panel2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        GlyphIcon(g, if (active) activeColor else tint)
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
