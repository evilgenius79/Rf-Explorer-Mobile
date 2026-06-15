package com.rfexplorer.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rfexplorer.protocol.CalcMode
import com.rfexplorer.protocol.Command
import com.rfexplorer.protocol.CumulativeCsvWriter
import com.rfexplorer.protocol.FrameParser
import com.rfexplorer.protocol.RfeMessage
import com.rfexplorer.protocol.SweepAccumulator
import com.rfexplorer.protocol.peakIndex
import com.rfexplorer.transport.ConnectionState
import com.rfexplorer.transport.ReplayTransport
import com.rfexplorer.transport.SerialTransport
import com.rfexplorer.transport.UsbSerialTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** Processed trace the spectrum view draws. */
data class Trace(
    val startFreqHz: Long,
    val stepFreqHz: Long,
    val amplitudesDbm: FloatArray,
    val peakIndex: Int,
) {
    val pointCount: Int get() = amplitudesDbm.size
}

data class SpectrumUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val deviceInfo: String? = null,
    val setup: RfeMessage.Setup? = null,
    val config: RfeMessage.Config? = null,
    val trace: Trace? = null,
    val calcMode: CalcMode = CalcMode.NORMAL,
    val sweepCount: Long = 0,
    val hexTail: String = "",
    val isRecording: Boolean = false,
    val recordedSweeps: Int = 0,
    val lastExportPath: String? = null,
)

/**
 * Wires transport bytes -> [FrameParser] -> UI state. Depends only on the
 * [SerialTransport] interface, so the USB path and the hardware-free replay path run
 * through identical code (kickoff phase plan, items 4-6).
 */
class SpectrumViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(SpectrumUiState())
    val ui: StateFlow<SpectrumUiState> = _ui.asStateFlow()

    private val parser = FrameParser()
    private val accumulator = SweepAccumulator()
    private val csv = CumulativeCsvWriter()
    private val recorded = mutableListOf<Pair<Instant, RfeMessage.Sweep>>()

    private var transport: SerialTransport? = null
    private var sessionJob: Job? = null

    fun connectUsb() = connect(UsbSerialTransport(getApplication()))

    fun connectReplay() = connect(
        ReplayTransport(
            source = { getApplication<Application>().assets.open(REPLAY_ASSET) },
            chunkSize = 64,        // small chunks exercise cross-read frame reassembly
            interChunkDelayMs = 60,
        ),
    )

    private fun connect(newTransport: SerialTransport) {
        disconnect()
        transport = newTransport
        parser.reset()
        accumulator.reset()
        recorded.clear()
        _ui.update {
            SpectrumUiState(calcMode = it.calcMode) // keep user's calc mode across reconnects
        }

        sessionJob = viewModelScope.launch {
            launch { newTransport.state.collect { st -> _ui.update { it.copy(connection = st) } } }
            launch { newTransport.deviceInfo.collect { info -> _ui.update { it.copy(deviceInfo = info) } } }
            launch { newTransport.reads.collect { onBytes(it) } }
            newTransport.open()
            // Pull the device's config so the frequency/amplitude axis populates without
            // the user having to tap "Req Config". No-op for the replay transport.
            if (newTransport.state.value is ConnectionState.Connected) requestConfig()
        }
    }

    fun disconnect() {
        val t = transport
        transport = null
        sessionJob?.cancel()
        sessionJob = null
        if (t != null) viewModelScope.launch { t.close() }
    }

    fun setCalcMode(mode: CalcMode) {
        accumulator.mode = mode
        accumulator.reset()
        _ui.update { it.copy(calcMode = mode) }
    }

    fun applyConfig(startMhz: Double, endMhz: Double, ampTopDbm: Int, ampBottomDbm: Int) = send(
        Command.AnalyzerConfig(
            startFreqKhz = (startMhz * 1000).toLong(),
            endFreqKhz = (endMhz * 1000).toLong(),
            ampTopDbm = ampTopDbm,
            ampBottomDbm = ampBottomDbm,
        ),
    )

    fun switchToMainModule() = send(Command.SwitchModuleMain)
    fun switchToExpansionModule() = send(Command.SwitchModuleExpansion)
    fun setSweepPoints(points: Int) = send(Command.SetSweepPoints(points))
    fun requestConfig() = send(Command.RequestConfig)
    fun hold() = send(Command.RequestHold)

    fun toggleRecording() {
        _ui.update {
            if (it.isRecording) it.copy(isRecording = false)
            else { recorded.clear(); it.copy(isRecording = true, recordedSweeps = 0) }
        }
    }

    /** Write recorded sweeps as cumulative CSV to app-private external storage. */
    fun exportCsv(): String? {
        if (recorded.isEmpty()) return null
        val dir = File(getApplication<Application>().getExternalFilesDir(null), "exports")
        dir.mkdirs()
        val file = File(dir, "rfe_${System.currentTimeMillis()}.csv")
        file.writeText(csv.write(recorded.toList()))
        _ui.update { it.copy(lastExportPath = file.absolutePath) }
        return file.absolutePath
    }

    private fun send(command: Command) {
        val t = transport ?: return
        viewModelScope.launch { runCatching { t.write(command) } }
    }

    private fun onBytes(chunk: ByteArray) {
        appendHex(chunk)
        for (message in parser.parse(chunk)) {
            when (message) {
                is RfeMessage.Setup -> _ui.update { it.copy(setup = message) }
                is RfeMessage.Config -> {
                    accumulator.reset()
                    _ui.update { it.copy(config = message) }
                }
                is RfeMessage.Sweep -> onSweep(message)
            }
        }
    }

    private fun onSweep(sweep: RfeMessage.Sweep) {
        val processed = accumulator.process(sweep.amplitudesDbm)
        val display = sweep.copy(amplitudesDbm = processed)

        if (_ui.value.isRecording) {
            recorded.add(Instant.now() to sweep) // record the raw live sweep, not the processed trace
        }

        _ui.update {
            it.copy(
                trace = Trace(sweep.startFreqHz, sweep.stepFreqHz, processed, display.peakIndex()),
                sweepCount = it.sweepCount + 1,
                recordedSweeps = recorded.size,
            )
        }
    }

    private fun appendHex(chunk: ByteArray) {
        val hex = chunk.joinToString(" ") { "%02X".format(it) }
        _ui.update {
            val combined = (it.hexTail + " " + hex).trim()
            it.copy(hexTail = combined.takeLast(HEX_TAIL_CHARS))
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        const val REPLAY_ASSET = "sample_capture.bin"
        const val HEX_TAIL_CHARS = 1500
    }
}
