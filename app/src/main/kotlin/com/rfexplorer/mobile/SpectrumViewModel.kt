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
import kotlin.math.abs

/** Processed trace the spectrum view draws. */
data class Trace(
    val startFreqHz: Long,
    val stepFreqHz: Long,
    val amplitudesDbm: FloatArray,
    val peakIndex: Int,
) {
    val pointCount: Int get() = amplitudesDbm.size

    fun freqHzAt(i: Int): Long = startFreqHz + i.toLong() * stepFreqHz
}

/** A detected peak in the current trace. */
data class PeakInfo(val freqHz: Long, val dbm: Float)

data class SpectrumUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val deviceInfo: String? = null,
    val setup: RfeMessage.Setup? = null,
    val config: RfeMessage.Config? = null,
    val trace: Trace? = null,
    val waterfall: List<FloatArray> = emptyList(),
    val calcMode: CalcMode = CalcMode.NORMAL,
    val sweepCount: Long = 0,
    val sweepsPerSec: Float = 0f,
    val hexTail: String = "",
    val isRecording: Boolean = false,
    val recordedSweeps: Int = 0,
    val lastExportPath: String? = null,
    val frozen: Boolean = false,
    val autoscale: Boolean = false,
    val markerIndex: Int? = null,
    val peaks: List<PeakInfo> = emptyList(),
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
    private val waterfall = ArrayDeque<FloatArray>()

    private var transport: SerialTransport? = null
    private var sessionJob: Job? = null
    private var lastSweepNanos = 0L
    private var fpsEma = 0f

    fun connectUsb() = connect(UsbSerialTransport(getApplication()))

    fun connectReplay() = connect(
        ReplayTransport(
            source = { getApplication<Application>().assets.open(REPLAY_ASSET) },
            chunkSize = 64,        // small chunks exercise cross-read frame reassembly
            interChunkDelayMs = 60,
            loop = true,           // keep the demo running
        ),
    )

    private fun connect(newTransport: SerialTransport) {
        disconnect()
        transport = newTransport
        parser.reset()
        accumulator.reset()
        recorded.clear()
        waterfall.clear()
        lastSweepNanos = 0L
        fpsEma = 0f
        _ui.update { SpectrumUiState(calcMode = it.calcMode, autoscale = it.autoscale) }

        sessionJob = viewModelScope.launch {
            launch { newTransport.state.collect { st -> _ui.update { it.copy(connection = st) } } }
            launch { newTransport.deviceInfo.collect { info -> _ui.update { it.copy(deviceInfo = info) } } }
            launch { newTransport.reads.collect { onBytes(it) } }
            newTransport.open()
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

    fun toggleFreeze() = _ui.update { it.copy(frozen = !it.frozen) }

    fun toggleAutoscale() = _ui.update { it.copy(autoscale = !it.autoscale) }

    fun setMarker(index: Int?) = _ui.update {
        val clamped = index?.coerceIn(0, (it.trace?.pointCount ?: 1) - 1)
        it.copy(markerIndex = clamped)
    }

    fun clearTraces() {
        accumulator.reset()
        waterfall.clear()
        _ui.update { it.copy(trace = null, waterfall = emptyList(), markerIndex = null, peaks = emptyList()) }
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
                    waterfall.clear()
                    _ui.update { it.copy(config = message, markerIndex = null) }
                }
                is RfeMessage.Sweep -> onSweep(message)
            }
        }
    }

    private fun onSweep(sweep: RfeMessage.Sweep) {
        if (_ui.value.frozen) return

        val processed = accumulator.process(sweep.amplitudesDbm)
        updateFps()

        if (_ui.value.isRecording) {
            recorded.add(Instant.now() to sweep) // record raw live sweep, not the processed trace
        }

        // Waterfall history (reset if the bin count changed).
        if (waterfall.isNotEmpty() && waterfall.last().size != processed.size) waterfall.clear()
        waterfall.addLast(processed)
        while (waterfall.size > WATERFALL_ROWS) waterfall.removeFirst()

        val peakIdx = argMax(processed)
        _ui.update {
            it.copy(
                trace = Trace(sweep.startFreqHz, sweep.stepFreqHz, processed, peakIdx),
                waterfall = waterfall.toList(),
                peaks = topPeaks(processed, sweep.startFreqHz, sweep.stepFreqHz),
                sweepCount = it.sweepCount + 1,
                sweepsPerSec = fpsEma,
                recordedSweeps = recorded.size,
            )
        }
    }

    private fun updateFps() {
        val now = System.nanoTime()
        if (lastSweepNanos != 0L) {
            val dt = (now - lastSweepNanos) / 1e9f
            if (dt > 0f) {
                val inst = 1f / dt
                fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.8f + inst * 0.2f
            }
        }
        lastSweepNanos = now
    }

    private fun argMax(amps: FloatArray): Int {
        if (amps.isEmpty()) return -1
        var best = 0
        for (i in 1 until amps.size) if (amps[i] > amps[best]) best = i
        return best
    }

    private fun topPeaks(amps: FloatArray, startHz: Long, stepHz: Long): List<PeakInfo> {
        if (amps.isEmpty()) return emptyList()
        val order = amps.indices.sortedByDescending { amps[it] }
        val chosen = mutableListOf<Int>()
        for (i in order) {
            if (chosen.size >= MAX_PEAKS) break
            if (chosen.none { abs(it - i) < PEAK_MIN_SEPARATION }) chosen.add(i)
        }
        return chosen.map { PeakInfo(startHz + it.toLong() * stepHz, amps[it]) }
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
        const val WATERFALL_ROWS = 100
        const val MAX_PEAKS = 5
        const val PEAK_MIN_SEPARATION = 6
    }
}
