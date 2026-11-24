package com.zedokmusic.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

enum class TrackKind { Instrument, Audio }

/** A MIDI note. Positions are in beats (quarter notes) so the arrangement survives tempo changes. */
class NoteM(val pitch: Int, val start: Float, val length: Float, val velocity: Int = 100)

class ClipM(val engineId: Int, val path: String, val name: String, start: Float, val lengthFrames: Long) {
    var start by mutableFloatStateOf(start)  // beats
}

class TrackM(val id: Int, name: String, val kind: TrackKind, val color: Color, val pluginPath: String?) {
    var name by mutableStateOf(name)
    var gain by mutableFloatStateOf(1f)
    var pan by mutableFloatStateOf(0f)
    var mute by mutableStateOf(false)
    var solo by mutableStateOf(false)
    /** 0 none, 1 starting, 2 ready, 3 error */
    var pluginState by mutableIntStateOf(0)
    var pluginName by mutableStateOf("")
    var meter by mutableFloatStateOf(0f)
    val notes = mutableStateListOf<NoteM>()
    val clips = mutableStateListOf<ClipM>()

    /** End of the content in beats, rounded up to whole bars (min 1 bar). */
    fun lengthBeats(bpm: Float): Float {
        var end = 0f
        notes.forEach { end = maxOf(end, it.start + it.length) }
        clips.forEach { end = maxOf(end, it.start + it.lengthFrames / 48000f * bpm / 60f) }
        return maxOf(4f, kotlin.math.ceil(end / 4f) * 4f)
    }
}

class PluginInfo(val name: String, val path: String)

val TrackColors = listOf(
    Color(0xFF3DDCC4), Color(0xFFFF7A59), Color(0xFF8E7CFF), Color(0xFFFFC857),
    Color(0xFF5CC8FF), Color(0xFFFF6FA5), Color(0xFF9BE564), Color(0xFFFFA24C),
)

/** Fader position (0..1) <-> linear gain. 0.75 = unity (0 dB), 1.0 = about +4.4 dB, 0 = silence. */
fun faderToGain(p: Float): Float { val x = p / 0.75f; return x * x }
fun gainToFader(g: Float): Float = (kotlin.math.sqrt(g.coerceAtLeast(0f)) * 0.75f).coerceIn(0f, 1f)
fun gainToDb(g: Float): String = if (g < 0.001f) "-∞" else "%.1f".format(20f * kotlin.math.log10(g))
