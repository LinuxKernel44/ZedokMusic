package com.zedokmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.*

/** What a track's "out" button says: where the track is mixed, and how many sends it has. */
fun routeLabel(vm: DawViewModel, t: TrackM): String {
    val dest = if (t.output == 0) "Master" else vm.busById(t.output)?.name ?: "Master"
    return if (t.sends.isEmpty()) "→ $dest" else "→ $dest +${t.sends.size}"
}

@Composable
private fun OutputChoice(label: String, color: Color, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (selected) Zk.panel2 else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(if (selected) color else Zk.line))
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Zk.text else Zk.dim, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Output and sends of one track. */
@Composable
fun TrackRoutingDialog(vm: DawViewModel, t: TrackM, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("${t.name} — routing") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("OUTPUT", color = Zk.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutputChoice("Master", Zk.text, t.output == 0) { vm.setTrackOutput(t, 0) }
            for (b in vm.buses) OutputChoice(b.name, b.color, t.output == b.id) { vm.setTrackOutput(t, b.id) }
            Spacer(Modifier.height(12.dp))
            Text("SENDS (copies of this track into a bus, up to 4)", color = Zk.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (vm.buses.isEmpty()) Text("No bus yet: add one with “+ Bus” in the mixer.", color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
            for (b in vm.buses) {
                val s = t.sends.firstOrNull { it.bus == b.id }
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(b.name, color = b.color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (s == null) LabelButton("Send", false, w = 56.dp, h = 28.dp) { vm.setSend(t, b.id, 0.5f) }
                        else {
                            LabelButton(if (s.pre) "Pre" else "Post", s.pre, w = 46.dp, h = 28.dp) { vm.setSend(t, b.id, s.gain, !s.pre) }
                            Spacer(Modifier.width(6.dp))
                            LabelButton("✕", false, w = 32.dp, h = 28.dp) { vm.removeSend(t, b.id) }
                        }
                    }
                    if (s != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(s.gain, { vm.setSend(t, b.id, it) }, valueRange = 0f..2f, modifier = Modifier.weight(1f), colors = SliderDefaults.colors(thumbColor = b.color, activeTrackColor = b.color, inactiveTrackColor = Zk.line))
                        Text(gainToDb(s.gain) + " dB", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.width(64.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Done") } })
}

/** Name, output and removal of one bus. */
@Composable
fun BusDialog(vm: DawViewModel, b: BusM, onDismiss: () -> Unit) {
    var name by remember(b) { mutableStateOf(b.name) }
    AlertDialog(onDismissRequest = { vm.renameBus(b, name); onDismiss() }, containerColor = Zk.panel, title = { Text("Bus") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            Spacer(Modifier.height(10.dp))
            Text("OUTPUT", color = Zk.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutputChoice("Master", Zk.text, b.output == 0) { vm.setBusOutput(b, 0) }
            for (x in vm.busTargets(b).filter { it.id != b.id }) OutputChoice(x.name, x.color, b.output == x.id) { vm.setBusOutput(b, x.id) }
            Text("Tracks routed here: " + vm.tracks.count { it.output == b.id } + ", sends: " + vm.tracks.sumOf { t -> t.sends.count { it.bus == b.id } }, color = Zk.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }, confirmButton = { TextButton({ vm.renameBus(b, name); onDismiss() }) { Text("Done") } },
        dismissButton = { TextButton({ vm.removeBus(b); onDismiss() }) { Text("Delete bus", color = Zk.danger) } })
}
