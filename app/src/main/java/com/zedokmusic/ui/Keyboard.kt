package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val WhiteSteps = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val BlackAfter = mapOf(0 to 1, 1 to 3, 3 to 6, 4 to 8, 5 to 10)  // white index -> black semitone

/** [key] restarts the gesture when the key's note changes (octave buttons), and a note held at that moment is released with the pitch it started with. */
private fun Modifier.press(key: Any, onDown: () -> Unit, onUp: () -> Unit) = pointerInput(key) {
    awaitEachGesture {
        awaitFirstDown(); onDown()
        try { do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed }) } finally { onUp() }
    }
}

/** Two-octave on-screen piano; multi-touch works because every key tracks its own pointer. */
@Composable
fun PianoKeyboard(baseOctave: Int, onOctave: (Int) -> Unit, color: Color, onNote: (Boolean, Int) -> Unit, modifier: Modifier = Modifier, onResize: ((Float) -> Unit)? = null, active: Set<Int> = emptySet()) {
    val base = (baseOctave + 1) * 12
    Column(modifier.background(Zk.panel)) {
        Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keys", color = Zk.dim, fontSize = 11.sp)
            LabelButton("−", w = 28.dp, h = 22.dp) { onOctave((baseOctave - 1).coerceAtLeast(0)) }
            Text("C$baseOctave – B${baseOctave + 1}", color = Zk.text, fontSize = 11.sp)
            LabelButton("+", w = 28.dp, h = 22.dp) { onOctave((baseOctave + 1).coerceAtMost(7)) }
            // Handle: drag up to make the keyboard taller, down to make it shorter (the size is remembered).
            if (onResize != null) {
                val d = androidx.compose.ui.platform.LocalDensity.current
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures { ch, drag -> ch.consume(); onResize(-drag.y / d.density) }
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.width(72.dp).height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).background(Zk.dim))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val whiteW = maxWidth / 14; val blackW = whiteW * 0.62f
            Row(Modifier.fillMaxSize()) {
                for (i in 0 until 14) {
                    val pitch = base + (i / 7) * 12 + WhiteSteps[i % 7]
                    Box(Modifier.width(whiteW).fillMaxHeight().border(0.5.dp, Color(0xFF9AA0AC)).background(if ((pitch in active)) color.copy(alpha = .75f) else Color(0xFFF1F2F5)).press(pitch, { onNote(true, pitch) }, { onNote(false, pitch) }), contentAlignment = Alignment.BottomCenter) {
                        Text(com.zedokmusic.model.Theory.name(pitch), fontSize = 9.sp, color = if (pitch in active) Color.Black else Color(0xFF555B68), modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            for (i in 0 until 14) {
                val semis = BlackAfter[i % 7] ?: continue
                val pitch = base + (i / 7) * 12 + semis
                Box(Modifier.offset(x = whiteW * (i + 1) - blackW / 2).width(blackW).fillMaxHeight(0.6f).background(if (pitch in active) color else Color(0xFF14161A)).border(0.5.dp, Color.Black).press(pitch, { onNote(true, pitch) }, { onNote(false, pitch) }), contentAlignment = Alignment.BottomCenter) {
                    Text(com.zedokmusic.model.Theory.pc(pitch) + (pitch / 12 - 1), fontSize = 8.sp, color = if (pitch in active) Color.Black else Color(0xFFC9CDD6), maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 4.dp))
                }
            }
        }
    }
}
