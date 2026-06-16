package com.rfexplorer.mobile

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfexplorer.protocol.CalcMode
import com.rfexplorer.transport.ConnectionState
import java.io.File
import kotlin.math.abs

@Composable
fun SpectrumScreen(
    modifier: Modifier = Modifier,
    viewModel: SpectrumViewModel = viewModel(),
) {
    val state by viewModel.ui.collectAsState()
    var showWaterfall by remember { mutableStateOf(false) }

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
        ViewControls(
            showWaterfall = showWaterfall,
            onToggleView = { showWaterfall = it },
            frozen = state.frozen,
            autoscale = state.autoscale,
            onFreeze = viewModel::toggleFreeze,
            onAutoscale = viewModel::toggleAutoscale,
            onClear = viewModel::clearTraces,
        )

        val window = ampWindowFor(state.trace, state.config?.ampTopDbm, state.config?.ampBottomDbm, state.autoscale)
        Card(Modifier.fillMaxWidth()) {
            if (showWaterfall) {
                WaterfallCanvas(
                    history = state.waterfall,
                    window = window,
                    modifier = Modifier.fillMaxWidth().height(260.dp).padding(8.dp),
                )
            } else {
                SpectrumCanvas(
                    trace = state.trace,
                    window = window,
                    markerA = state.markerA,
                    markerB = state.markerB,
                    onTapBin = viewModel::setMarker,
                    modifier = Modifier.fillMaxWidth().height(260.dp).padding(8.dp),
                )
            }
        }

        MarkerControls(state, viewModel)
        PeaksCard(state)

        ConnectionControls(viewModel, state.connection)
        CalcModeChips(state.calcMode, viewModel::setCalcMode)
        ConfigControls(viewModel, state.controls)
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
            "Peak %.3f MHz @ %.1f dBm".format(t.freqHzAt(t.peakIndex) / 1e6, t.amplitudesDbm[t.peakIndex])
        } else null
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(connection, style = MaterialTheme.typography.titleMedium)
            state.deviceInfo?.let { Text("Device: $it", fontSize = 12.sp) }
            state.setup?.let {
                Text("Main ${it.mainModel} · Exp ${it.expansionModel} · fw ${it.firmwareVersion}", fontSize = 12.sp)
            }
            Text("Sweeps: ${state.sweepCount} · %.1f /s".format(state.sweepsPerSec), fontSize = 12.sp)
            peak?.let { Text(it, fontSize = 12.sp, color = PeakAmber) }
        }
    }
}

@Composable
private fun ViewControls(
    showWaterfall: Boolean,
    onToggleView: (Boolean) -> Unit,
    frozen: Boolean,
    autoscale: Boolean,
    onFreeze: () -> Unit,
    onAutoscale: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = !showWaterfall, onClick = { onToggleView(false) }, label = { Text("Spectrum") })
        FilterChip(selected = showWaterfall, onClick = { onToggleView(true) }, label = { Text("Waterfall") })
        FilterChip(selected = frozen, onClick = onFreeze, label = { Text("Freeze") })
        FilterChip(selected = autoscale, onClick = onAutoscale, label = { Text("Autoscale") })
        OutlinedButton(onClick = onClear) { Text("Clear") }
    }
}

@Composable
private fun MarkerControls(state: SpectrumUiState, viewModel: SpectrumViewModel) {
    val t = state.trace
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(state.activeMarker == 0, onClick = { viewModel.setActiveMarker(0) }, label = { Text("Mkr A") })
            FilterChip(state.activeMarker == 1, onClick = { viewModel.setActiveMarker(1) }, label = { Text("Mkr B") })
            OutlinedButton(onClick = viewModel::snapActiveMarkerToPeak) { Text("→ Peak") }
            OutlinedButton(onClick = viewModel::clearMarkers) { Text("Clear Mkr") }
        }
        if (t != null) {
            state.markerA?.let {
                if (it in t.amplitudesDbm.indices) {
                    Text("A: %.3f MHz  %.1f dBm".format(t.freqHzAt(it) / 1e6, t.amplitudesDbm[it]), color = MarkerCyan, fontSize = 12.sp)
                }
            }
            state.markerB?.let {
                if (it in t.amplitudesDbm.indices) {
                    Text("B: %.3f MHz  %.1f dBm".format(t.freqHzAt(it) / 1e6, t.amplitudesDbm[it]), color = MarkerMagenta, fontSize = 12.sp)
                }
            }
            val a = state.markerA
            val b = state.markerB
            if (a != null && b != null && a in t.amplitudesDbm.indices && b in t.amplitudesDbm.indices) {
                val df = abs(t.freqHzAt(b) - t.freqHzAt(a)) / 1e6
                val dd = t.amplitudesDbm[b] - t.amplitudesDbm[a]
                Text("Δ %.3f MHz   %.1f dB".format(df, dd), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PeaksCard(state: SpectrumUiState) {
    if (state.peaks.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("Top peaks", style = MaterialTheme.typography.labelMedium)
            state.peaks.forEachIndexed { i, p ->
                Text("${i + 1}.  %.3f MHz   %.1f dBm".format(p.freqHz / 1e6, p.dbm), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ConnectionControls(viewModel: SpectrumViewModel, connection: ConnectionState) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = viewModel::connectUsb) { Text("USB") }
        Button(onClick = viewModel::connectReplay) { Text("Replay") }
        OutlinedButton(onClick = viewModel::disconnect) { Text("Disconnect") }
        if (connection == ConnectionState.Connected) {
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
            FilterChip(selected = current == mode, onClick = { onSelect(mode) }, label = { Text(mode.name) })
        }
    }
}

@Composable
private fun ConfigControls(viewModel: SpectrumViewModel, controls: ControlFields) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !controls.centerSpan,
                onClick = { viewModel.updateControls(controls.copy(centerSpan = false)) },
                label = { Text("Start/Stop") },
            )
            FilterChip(
                selected = controls.centerSpan,
                onClick = { viewModel.updateControls(controls.copy(centerSpan = true)) },
                label = { Text("Center/Span") },
            )
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bands:", Modifier.align(Alignment.CenterVertically))
            BandPresets.forEach { (label, lo, hi) ->
                OutlinedButton(onClick = { viewModel.applyPreset(lo, hi) }) { Text(label) }
            }
        }

        if (controls.centerSpan) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Center MHz", controls.centerMhz, Modifier.weight(1f)) {
                    viewModel.updateControls(controls.copy(centerMhz = it))
                }
                NumberField("Span MHz", controls.spanMhz, Modifier.weight(1f)) {
                    viewModel.updateControls(controls.copy(spanMhz = it))
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Start MHz", controls.startMhz, Modifier.weight(1f)) {
                    viewModel.updateControls(controls.copy(startMhz = it))
                }
                NumberField("End MHz", controls.endMhz, Modifier.weight(1f)) {
                    viewModel.updateControls(controls.copy(endMhz = it))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Amp top dBm", controls.ampTop, Modifier.weight(1f)) {
                viewModel.updateControls(controls.copy(ampTop = it))
            }
            NumberField("Amp bottom dBm", controls.ampBottom, Modifier.weight(1f)) {
                viewModel.updateControls(controls.copy(ampBottom = it))
            }
        }
        Button(onClick = viewModel::applyFromControls) { Text("Apply span / amplitude") }
    }
}

@Composable
private fun ModuleAndPointsControls(viewModel: SpectrumViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::switchToMainModule) { Text("Main 6G") }
            OutlinedButton(onClick = viewModel::switchToExpansionModule) { Text("WSUB3G") }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Points:")
            listOf(112, 240, 512).forEach { pts ->
                OutlinedButton(onClick = { viewModel.setSweepPoints(pts) }) { Text("$pts") }
            }
        }
    }
}

@Composable
private fun RecordingControls(viewModel: SpectrumViewModel, state: SpectrumUiState) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::toggleRecording) {
                Text(if (state.isRecording) "Stop rec (${state.recordedSweeps})" else "Record CSV")
            }
            OutlinedButton(onClick = { viewModel.exportCsv() }) { Text("Export CSV") }
            OutlinedButton(onClick = {
                viewModel.exportCsv()?.let { shareCsv(context, File(it)) }
            }) { Text("Share CSV") }
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

private fun shareCsv(context: android.content.Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share CSV"))
}

/** Quick-tune band presets (MHz). Applied with the current amplitude window. */
private val BandPresets = listOf(
    Triple("433 ISM", 430.0, 440.0),
    Triple("915 ISM", 902.0, 928.0),
    Triple("2.4 GHz", 2400.0, 2500.0),
    Triple("5 GHz", 5150.0, 5850.0),
)
