package com.zedokmusic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zedokmusic.model.DawViewModel
import kotlinx.coroutines.delay

private const val NONE = -150f
private fun lufs(v: Float) = if (v <= NONE) "—" else "%.1f".format(v)

/** Loudness of the master while playing (ITU-R BS.1770 / EBU R128): momentary, short-term, integrated, range, true peak, phase correlation. */
@Composable
fun MeterDialog(vm: DawViewModel, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(vm.readLoudness()) }
    LaunchedEffect(Unit) { while (true) { v = vm.readLoudness(); delay(100) } }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Zk.panel, title = { Text("Master loudness") }, text = {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Big("Momentary", lufs(v[0]), "LUFS"); Big("Short-term", lufs(v[1]), "LUFS"); Big("Integrated", lufs(v[2]), "LUFS")
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Big("Range", lufs(v[3]), "LU"); Big("True peak", lufs(v[4]), "dBTP"); Big("Sample peak", lufs(v[5]), "dBFS")
            }
            Spacer(Modifier.height(12.dp))
            Text("Phase correlation  " + "%+.2f".format(v[6]) + if (v[6] < 0f) "  (out of phase)" else "", color = Zk.dim, fontSize = 12.sp)
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                drawRoundRect(Zk.line, Offset.Zero, size, androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                val x = (v[6].coerceIn(-1f, 1f) + 1f) / 2f * size.width
                drawRect(if (v[6] < 0f) Zk.danger else Zk.accent, Offset(size.width / 2f, 0f), Size(x - size.width / 2f, size.height))
                drawLine(Zk.text, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
            }
            Text("Measured on the master while the song plays; it restarts when playback starts from the beginning. Streaming services aim near -14 LUFS; -23 LUFS is the broadcast reference.", color = Zk.dim, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Close") } }, dismissButton = { TextButton({ vm.resetLoudness() }) { Text("Reset") } })
}

@Composable
private fun Big(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 80.dp)) {
        Text(value, color = Zk.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("$label · $unit", color = Zk.dim, fontSize = 10.sp, maxLines = 1)
    }
}
