package com.rfexplorer.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfexplorer.protocol.CalcMode
import com.rfexplorer.transport.ConnectionState

@Composable
fun SpectrumScreen(
    modifier: Modifier = Modifier,
    viewModel: SpectrumViewModel = viewModel(),
) {
    val state by viewModel.ui.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusBar(state)
        SpectrumCanvas(state)
        ConnectionControls(viewModel, state.connection)
        CalcModeChips(state.calcMode, viewModel::setCalcMode)
        ConfigControls(viewModel)
        ModuleAndPointsControls(viewModel)
        RecordingControls(viewModel, state)
        HexDebug(state.hexTail)
    }
}

@Composable
private fun StatusBar(state: SpectrumUiState) {
    val connection = when (val c = state.connection) {
        ConnectionState.Connected -> "Connected"
        ConnectionState.Connecting -> "Connecting…"
        ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Error -> "Error: ${c.message}"
    }
    val peak = state.trace?.let { t ->
        if (t.peakIndex in t.amplitudesDbm.indices) {
            val hz = t.startFreqHz + t.peakIndex.toLong() * t.stepFreqHz
            "Peak %.3f MHz @ %.1f dBm".format(hz / 1e6, t.amplitudesDbm[t.peakIndex])
        } else null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(connection, style = MaterialTheme.typography.titleMedium)
            state.deviceInfo?.let { Text("Device: $it", fontSize = 12.sp) }
            state.setup?.let {
                Text("Main ${it.mainModel} · Exp ${it.expansionModel} · fw ${it.firmwareVersion}", fontSize = 12.sp)
            }
            Text("Sweeps: ${state.sweepCount}", fontSize = 12.sp)
            peak?.let { Text(it, fontSize = 12.sp, color = PeakAmber) }
        }
    }
}

@Composable
private fun SpectrumCanvas(state: SpectrumUiState) {
    val trace = state.trace
    val ampTop = (state.config?.ampTopDbm ?: 0).toFloat()
    val ampBottom = (state.config?.ampBottomDbm ?: -120).toFloat()
    val span = (ampTop - ampBottom).takeIf { it > 0 } ?: 120f

    Card(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(260.dp)
                .padding(8.dp),
        ) {
            // Grid.
            val rows = 6
            val cols = 8
            for (r in 0..rows) {
                val y = size.height * r / rows
                drawLine(GridGray, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            for (c in 0..cols) {
                val x = size.width * c / cols
                drawLine(GridGray, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }

            val amps = trace?.amplitudesDbm ?: return@Canvas
            if (amps.size < 2) return@Canvas

            fun yFor(dbm: Float) = (size.height * (ampTop - dbm) / span).coerceIn(0f, size.height)
            fun xFor(i: Int) = size.width * i / (amps.size - 1)

            val path = Path().apply {
                moveTo(xFor(0), yFor(amps[0]))
                for (i in 1 until amps.size) lineTo(xFor(i), yFor(amps[i]))
            }
            drawPath(path, color = TraceGreen, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))

            // Peak marker.
            val p = trace.peakIndex
            if (p in amps.indices) {
                drawCircle(PeakAmber, radius = 5f, center = Offset(xFor(p), yFor(amps[p])))
            }
        }
    }
}

@Composable
private fun ConnectionControls(viewModel: SpectrumViewModel, connection: ConnectionState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::connectUsb) { Text("USB") }
        Button(onClick = viewModel::connectReplay) { Text("Replay") }
        OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
    }
    if (connection == ConnectionState.Connected) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::requestConfig) { Text("Req Config") }
            OutlinedButton(onClick = viewModel::hold) { Text("Hold") }
        }
    }
}

@Composable
private fun CalcModeChips(current: CalcMode, onSelect: (CalcMode) -> Unit) {
    val modes = listOf(CalcMode.NORMAL, CalcMode.MAX_HOLD, CalcMode.AVG)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { mode ->
            FilterChip(
                selected = current == mode,
                onClick = { onSelect(mode) },
                label = { Text(mode.name) },
            )
        }
    }
}

@Composable
private fun ConfigControls(viewModel: SpectrumViewModel) {
    var startMhz by remember { mutableStateOf("100") }
    var endMhz by remember { mutableStateOf("200") }
    var ampTop by remember { mutableStateOf("-10") }
    var ampBottom by remember { mutableStateOf("-110") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Start MHz", startMhz, Modifier.weight(1f)) { startMhz = it }
            NumberField("End MHz", endMhz, Modifier.weight(1f)) { endMhz = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Amp top dBm", ampTop, Modifier.weight(1f)) { ampTop = it }
            NumberField("Amp bottom dBm", ampBottom, Modifier.weight(1f)) { ampBottom = it }
        }
        Button(
            onClick = {
                viewModel.applyConfig(
                    startMhz = startMhz.toDoubleOrNull() ?: return@Button,
                    endMhz = endMhz.toDoubleOrNull() ?: return@Button,
                    ampTopDbm = ampTop.toIntOrNull() ?: return@Button,
                    ampBottomDbm = ampBottom.toIntOrNull() ?: return@Button,
                )
            },
        ) { Text("Apply span / amplitude") }
    }
}

@Composable
private fun ModuleAndPointsControls(viewModel: SpectrumViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::switchToMainModule) { Text("Main 6G") }
            OutlinedButton(onClick = viewModel::switchToExpansionModule) { Text("WSUB3G") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Points:")
            listOf(112, 240, 512).forEach { pts ->
                OutlinedButton(onClick = { viewModel.setSweepPoints(pts) }) { Text("$pts") }
            }
        }
    }
}

@Composable
private fun RecordingControls(viewModel: SpectrumViewModel, state: SpectrumUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::toggleRecording) {
                Text(if (state.isRecording) "Stop rec (${state.recordedSweeps})" else "Record CSV")
            }
            OutlinedButton(onClick = { viewModel.exportCsv() }) { Text("Export CSV") }
        }
        state.lastExportPath?.let { Text("Saved: $it", fontSize = 11.sp) }
    }
}

@Composable
private fun HexDebug(hexTail: String) {
    if (hexTail.isBlank()) return
    Column {
        HorizontalDivider()
        Text("Raw bytes (tail)", style = MaterialTheme.typography.labelMedium)
        Text(hexTail, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}
