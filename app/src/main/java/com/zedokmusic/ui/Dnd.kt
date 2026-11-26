package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.KitNode
import com.zedokmusic.model.PluginInfo
import com.zedokmusic.model.TrackM
import kotlin.math.floor
import kotlin.math.roundToInt

/** What is being dragged out of the sound browser: sounds, a whole folder (a kit) or a plugin. */
class DragPayload(val title: String, val sounds: List<KitNode> = emptyList(), val folderImport: ((TrackM) -> Unit)? = null, val plugin: PluginInfo? = null) {
    val isFolder get() = folderImport != null
    val isSound get() = sounds.isNotEmpty()
}

/** What is under the finger while dragging. */
sealed class Hover {
    class Lane(val track: TrackM, val beat: Float) : Hover()
    object NewTrack : Hover()
    class Pad(val n: Int) : Hover()
    object PadGrid : Hover()
}

/**
 * Drag and drop between the sound browser and the arrangement. The browser starts a drag ([start]); the arrangement reports where its track rows,
 * the "new track" zone and the pad grid are (in window coordinates); [move] works out what is under the finger and [drop] hands the result to [onDrop].
 */
class DndState {
    var payload by mutableStateOf<DragPayload?>(null); private set
    var pos by mutableStateOf(Offset.Zero); private set
    var hover by mutableStateOf<Hover?>(null); private set
    val lanes = HashMap<TrackM, Rect>()
    var newZone: Rect? = null
    var arrangeRect: Rect? = null
    val padRects = HashMap<Int, Rect>()
    var padGrid: Rect? = null
    var ppb = 44f; var density = 1f
    var isDrums: (TrackM) -> Boolean = { false }
    var onDrop: (DragPayload, Hover) -> Unit = { _, _ -> }
    val finishListeners = ArrayList<(Boolean) -> Unit>()
    var lastDrums: TrackM? = null
    private fun finished(ok: Boolean) { finishListeners.toList().forEach { it(ok) } }

    /** Can [h] take what is being dragged? */
    fun accepts(p: DragPayload, h: Hover): Boolean = when (h) {
        is Hover.Lane -> when {
            p.plugin != null -> p.plugin.kind != 1
            p.isFolder -> isDrums(h.track)
            else -> h.track.kind == com.zedokmusic.model.TrackKind.Audio || isDrums(h.track)
        }
        Hover.NewTrack -> if (p.plugin != null) p.plugin.kind != 2 else true
        is Hover.Pad -> p.isSound
        Hover.PadGrid -> false
    }
    /** The pad grid: opens when a sound is held over a Zedok Drums track, stays open while the finger travels to it (the grid sits up and to the left of where it opened) and closes when the finger leaves that corridor. */
    var padsOpen by mutableStateOf(false); private set
    var padAnchor by mutableStateOf<Offset?>(null); private set
    private fun padZone(): Rect? {
        val g = padGrid; val a = padAnchor ?: return null; if (g == null) return null
        val m = 30f * density
        return Rect(minOf(g.left, a.x - m), minOf(g.top, a.y - m), maxOf(g.right, a.x + m), maxOf(g.bottom, a.y + m)).inflate(m)
    }

    fun start(p: DragPayload, at: Offset) { payload = p; pos = at; hover = null; padsOpen = false; padAnchor = null; evaluate() }
    fun move(at: Offset) { pos = at; evaluate() }
    private fun evaluate() {
        val p = payload ?: return
        val at = pos; val ar = arrangeRect
        val laneHit = if (ar != null && ar.contains(at)) lanes.entries.firstOrNull { at.y >= it.value.top && at.y < it.value.bottom } else null
        val onDrums = laneHit != null && isDrums(laneHit.key)
        if (p.isSound) {
            val keep = padsOpen && padZone()?.contains(at) == true
            if (onDrums && !padsOpen) padAnchor = at
            padsOpen = onDrums || keep
            if (!padsOpen) padAnchor = null
            if (padsOpen) {
                padRects.entries.firstOrNull { it.value.contains(at) }?.let { hover = Hover.Pad(it.key); return }
                if (padGrid?.contains(at) == true) { hover = Hover.PadGrid; return }
                if (!onDrums) { hover = Hover.PadGrid; return }   // on the way to the grid: nothing else is a target
            }
        } else { padsOpen = false; padAnchor = null }
        if (laneHit != null) {
            val (t, r) = laneHit
            val snap = com.zedokmusic.settings.AppSettings.snapBeats
            val beat = (((at.x - r.left) / density / ppb) / snap).let { floor(it) * snap }.coerceAtLeast(0f)
            if (isDrums(t)) lastDrums = t
            hover = Hover.Lane(t, beat); return
        }
        if (ar != null && ar.contains(at) && newZone?.let { at.y >= it.top && at.y < it.bottom } == true) { hover = Hover.NewTrack; return }
        hover = null
    }
    fun drop() {
        val p = payload; val h = hover
        payload = null; hover = null; padsOpen = false; padAnchor = null
        if (p != null && h != null && accepts(p, h)) { onDrop(p, h); finished(true) } else finished(false)
    }
    fun cancel() { payload = null; hover = null; padsOpen = false; padAnchor = null; finished(false) }
}

val LocalDnd = compositionLocalOf { DndState() }

/** Makes an element a drag source: [immediate] drags from the first movement (a handle), otherwise after a long press (a whole row). */
fun Modifier.dragSource(dnd: DndState, immediate: Boolean, payload: () -> DragPayload): Modifier = composed {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var cur by remember { mutableStateOf(Offset.Zero) }
    val pl = rememberUpdatedState(payload)
    val begin = { o: Offset -> cur = coords?.localToRoot(o) ?: o; dnd.start(pl.value(), cur) }
    // The finger's window position comes from the pointer itself (adding up deltas would count the first move twice: the touch slop is reported separately).
    val drag = { ch: PointerInputChange, d: Offset -> ch.consume(); cur = coords?.localToRoot(ch.position) ?: (cur + d); dnd.move(cur) }
    this.onGloballyPositioned { coords = it }.pointerInput(immediate) {
        if (immediate) detectDragGestures(onDragStart = begin, onDrag = drag, onDragEnd = { dnd.drop() }, onDragCancel = { dnd.cancel() })
        else detectDragGesturesAfterLongPress(onDragStart = begin, onDrag = drag, onDragEnd = { dnd.drop() }, onDragCancel = { dnd.cancel() })
    }
}

/** The dragged item's chip under the finger, and the 16-pad grid when a sound is over a Zedok Drums track. Placed once, over the whole screen. */
@Composable
fun DragLayer(dnd: DndState, color: Color, padLabel: (Int) -> String?) {
    val p = dnd.payload ?: return
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (dnd.padsOpen) {
            val h = dnd.hover
            DisposableEffect(Unit) { onDispose { dnd.padRects.clear(); dnd.padGrid = null } }
            Column(Modifier.offset { val a = dnd.padAnchor ?: dnd.pos; IntOffset((a.x - origin.x - 230.dp.toPx()).roundToInt().coerceAtLeast(8), (a.y - origin.y - 80.dp.toPx()).roundToInt().coerceAtLeast(8)) }
                .onGloballyPositioned { dnd.padGrid = it.boundsInRoot() }
                .clip(RoundedCornerShape(12.dp)).background(Zk.panel.copy(alpha = .97f)).border(1.dp, color, RoundedCornerShape(12.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pads · release on one", color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (c in 0 until 4) {
                        val n = (3 - r) * 4 + c + 1                      // pad 1 at the bottom left, like the pad screen
                        val hot = h is Hover.Pad && h.n == n
                        Box(Modifier.size(50.dp, 42.dp).clip(RoundedCornerShape(8.dp)).background(if (hot) color.copy(alpha = .35f) else Zk.panel2)
                            .then(if (hot) Modifier.border(2.dp, color, RoundedCornerShape(8.dp)) else Modifier)
                            .onGloballyPositioned { dnd.padRects[n] = it.boundsInRoot() }, contentAlignment = Alignment.Center) {
                            Text(padLabel(n) ?: "$n", color = if (padLabel(n) != null) Zk.text else Zk.dim, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(2.dp))
                        }
                    }
                }
            }
        }
        Text((if (p.isFolder) "📁 " else "") + p.title + (if (p.sounds.size > 1) "  ×${p.sounds.size}" else ""), color = Color(0xFF06211D), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.offset { IntOffset((dnd.pos.x - origin.x + 10.dp.toPx()).roundToInt(), (dnd.pos.y - origin.y + 10.dp.toPx()).roundToInt()) }
                .widthIn(max = 220.dp).clip(RoundedCornerShape(9.dp)).background(color).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}
