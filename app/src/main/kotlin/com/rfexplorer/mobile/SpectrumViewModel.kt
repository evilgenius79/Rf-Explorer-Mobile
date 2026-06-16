package com.rfexplorer.mobile

import android.app.Application
import android.content.Context
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

/** Editable tuning fields, persisted across launches. */
data class ControlFields(
    val startMhz: String = "100",
    val endMhz: String = "200",
    val centerMhz: String = "150",
    val spanMhz: String = "100",
    val ampTop: String = "-10",
    val ampBottom: String = "-110",
    val centerSpan: Boolean = false,
)

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
    val markerA: Int? = null,
    val markerB: Int? = null,
    val activeMarker: Int = 0, // 0 = A, 1 = B
    val peaks: List<PeakInfo> = emptyList(),
    val controls: ControlFields = ControlFields(),
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
    private val prefs = app.getSharedPreferences("rfe_settings", Context.MODE_PRIVATE)

    private var transport: SerialTransport? = null
    private var sessionJob: Job? = null
    private var lastSweepNanos = 0L
    private var fpsEma = 0f

    init {
        val mode = runCatching { CalcMode.valueOf(prefs.getString("calcMode", "NORMAL")!!) }.getOrDefault(CalcMode.NORMAL)
        accumulator.mode = mode
        _ui.update {
            it.copy(
                calcMode = mode,
                autoscale = prefs.getBoolean("autoscale", false),
                controls = loadControls(),
            )
        }
    }

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
        // Keep persisted/user choices across reconnects.
        _ui.update {
            SpectrumUiState(calcMode = it.calcMode, autoscale = it.autoscale, controls = it.controls)
        }

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
        prefs.edit().putString("calcMode", mode.name).apply()
        _ui.update { it.copy(calcMode = mode) }
    }

    fun toggleFreeze() = _ui.update { it.copy(frozen = !it.frozen) }

    fun toggleAutoscale() = _ui.update {
        val next = !it.autoscale
        prefs.edit().putBoolean("autoscale", next).apply()
        it.copy(autoscale = next)
    }

    // ---- Markers --------------------------------------------------------------

    fun setActiveMarker(which: Int) = _ui.update { it.copy(activeMarker = which) }

    fun setMarker(index: Int?) = _ui.update {
        val clamped = index?.coerceIn(0, (it.trace?.pointCount ?: 1) - 1)
        if (it.activeMarker == 0) it.copy(markerA = clamped) else it.copy(markerB = clamped)
    }

    fun snapActiveMarkerToPeak() {
        val p = _ui.value.trace?.peakIndex ?: return
        setMarker(p)
    }

    fun clearMarkers() = _ui.update { it.copy(markerA = null, markerB = null) }

    // ---- Trace controls -------------------------------------------------------

    fun clearTraces() {
        accumulator.reset()
        waterfall.clear()
        _ui.update {
            it.copy(trace = null, waterfall = emptyList(), markerA = null, markerB = null, peaks = emptyList())
        }
    }

    // ---- Tuning ---------------------------------------------------------------

    fun updateControls(controls: ControlFields) = _ui.update { it.copy(controls = controls) }

    fun applyFromControls() {
        val c = _ui.value.controls
        val span = if (c.centerSpan) {
            val ce = c.centerMhz.toDoubleOrNull() ?: return
            val sp = c.spanMhz.toDoubleOrNull() ?: return
            (ce - sp / 2) to (ce + sp / 2)
        } else {
            val a = c.startMhz.toDoubleOrNull() ?: return
            val b = c.endMhz.toDoubleOrNull() ?: return
            a to b
        }
        applyConfig(span.first, span.second, c.ampTop.toIntOrNull() ?: return, c.ampBottom.toIntOrNull() ?: return)
        persistControls(c)
    }

    fun applyPreset(loMhz: Double, hiMhz: Double) {
        val c = _ui.value.controls.copy(
            startMhz = fmt(loMhz), endMhz = fmt(hiMhz),
            centerMhz = fmt((loMhz + hiMhz) / 2), spanMhz = fmt(hiMhz - loMhz),
        )
        _ui.update { it.copy(controls = c) }
        applyConfig(loMhz, hiMhz, c.ampTop.toIntOrNull() ?: -10, c.ampBottom.toIntOrNull() ?: -110)
        persistControls(c)
    }

    /**
     * Send the span/amplitude config, first switching to the RF module that can reach
     * the requested band. On the 6G Combo the WSUB3G expansion covers the low range and
     * the 6G mainboard the high range; asking the active module for an out-of-range span
     * makes the device go silent (the trace just freezes), so we switch first.
     */
    private fun applyConfig(startMhz: Double, endMhz: Double, ampTopDbm: Int, ampBottomDbm: Int) {
        val t = transport ?: return
        val moduleCmd = moduleCommandFor(startMhz)
        val cfg = Command.AnalyzerConfig(
            startFreqKhz = (startMhz * 1000).toLong(),
            endFreqKhz = (endMhz * 1000).toLong(),
            ampTopDbm = ampTopDbm,
            ampBottomDbm = ampBottomDbm,
        )
        viewModelScope.launch {
            runCatching {
                if (moduleCmd != null) {
                    t.write(moduleCmd)
                    kotlinx.coroutines.delay(MODULE_SWITCH_SETTLE_MS)
                }
                t.write(cfg)
            }
        }
    }

    /** Pick the module whose range contains [startMhz], or null if ambiguous (keep current). */
    private fun moduleCommandFor(startMhz: Double): Command? = when {
        startMhz <= MODULE_EXPANSION_MAX_MHZ -> Command.SwitchModuleExpansion
        startMhz >= MODULE_MAIN_MIN_MHZ -> Command.SwitchModuleMain
        else -> null
    }

    fun switchToMainModule() = send(Command.SwitchModuleMain)
    fun switchToExpansionModule() = send(Command.SwitchModuleExpansion)
    fun setSweepPoints(points: Int) = send(Command.SetSweepPoints(points))
    fun requestConfig() = send(Command.RequestConfig)
    fun hold() = send(Command.RequestHold)

    // ---- Recording / export ---------------------------------------------------

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

    // ---- Internals ------------------------------------------------------------

    private fun send(command: Command) {
        val t = transport ?: return
        viewModelScope.launch { runCatching { t.write(command) } }
    }

    private fun onBytes(chunk: ByteArray) {
        appendHex(chunk)
        for (message in parser.parse(chunk)) {
            when (message) {
                is RfeMessage.Setup -> _ui.update { it.copy(setup = message) }
                is RfeMessage.Config -> onConfig(message)
                is RfeMessage.Sweep -> onSweep(message)
            }
        }
    }

    private fun onConfig(config: RfeMessage.Config) {
        accumulator.reset()
        waterfall.clear()
        // Reflect the device's actual span/amplitude in the editable fields.
        val startMhz = config.startFreqHz / 1e6
        val endMhz = (config.startFreqHz + config.freqStepHz * (config.sweepPoints - 1).coerceAtLeast(0)) / 1e6
        _ui.update {
            it.copy(
                config = config,
                markerA = null,
                markerB = null,
                controls = it.controls.copy(
                    startMhz = fmt(startMhz),
                    endMhz = fmt(endMhz),
                    centerMhz = fmt((startMhz + endMhz) / 2),
                    spanMhz = fmt(endMhz - startMhz),
                    ampTop = config.ampTopDbm.toString(),
                    ampBottom = config.ampBottomDbm.toString(),
                ),
            )
        }
    }

    private fun onSweep(sweep: RfeMessage.Sweep) {
        if (_ui.value.frozen) return

        val processed = accumulator.process(sweep.amplitudesDbm)
        updateFps()

        if (_ui.value.isRecording) {
            if (recorded.size < MAX_RECORDED_SWEEPS) {
                recorded.add(Instant.now() to sweep) // record raw live sweep, not the processed trace
            } else {
                _ui.update { it.copy(isRecording = false) } // cap memory; stop recording
            }
        }

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
        // Collapse plateaus (e.g. a clipped flat-top) to a single peak so one strong
        // signal isn't reported as several adjacent peaks.
        val candidates = localMaxima(amps).ifEmpty { listOf(argMax(amps)) }
        val order = candidates.sortedByDescending { amps[it] }
        val chosen = mutableListOf<Int>()
        for (i in order) {
            if (chosen.size >= MAX_PEAKS) break
            if (chosen.none { abs(it - i) < PEAK_MIN_SEPARATION }) chosen.add(i)
        }
        return chosen.map { PeakInfo(startHz + it.toLong() * stepHz, amps[it]) }
    }

    /** Indices of local maxima; a flat plateau yields a single index at its centre. */
    private fun localMaxima(amps: FloatArray): List<Int> {
        val n = amps.size
        val result = mutableListOf<Int>()
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && amps[j + 1] == amps[i]) j++ // plateau [i..j]
            val leftLower = i == 0 || amps[i - 1] < amps[i]
            val rightLower = j == n - 1 || amps[j + 1] < amps[i]
            if (leftLower && rightLower) result.add((i + j) / 2)
            i = j + 1
        }
        return result
    }

    private fun appendHex(chunk: ByteArray) {
        // Only the tail is ever shown, so format at most the last slice of each chunk.
        val slice = if (chunk.size > HEX_CHUNK_BYTES) chunk.copyOfRange(chunk.size - HEX_CHUNK_BYTES, chunk.size) else chunk
        val hex = slice.joinToString(" ") { "%02X".format(it) }
        _ui.update {
            val combined = (it.hexTail + " " + hex).trim()
            it.copy(hexTail = combined.takeLast(HEX_TAIL_CHARS))
        }
    }

    private fun loadControls(): ControlFields = ControlFields(
        startMhz = prefs.getString("startMhz", "100")!!,
        endMhz = prefs.getString("endMhz", "200")!!,
        centerMhz = prefs.getString("centerMhz", "150")!!,
        spanMhz = prefs.getString("spanMhz", "100")!!,
        ampTop = prefs.getString("ampTop", "-10")!!,
        ampBottom = prefs.getString("ampBottom", "-110")!!,
        centerSpan = prefs.getBoolean("centerSpan", false),
    )

    private fun persistControls(c: ControlFields) {
        prefs.edit()
            .putString("startMhz", c.startMhz)
            .putString("endMhz", c.endMhz)
            .putString("centerMhz", c.centerMhz)
            .putString("spanMhz", c.spanMhz)
            .putString("ampTop", c.ampTop)
            .putString("ampBottom", c.ampBottom)
            .putBoolean("centerSpan", c.centerSpan)
            .apply()
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else "%.3f".format(v).trimEnd('0').trimEnd('.')

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }

    private companion object {
        const val REPLAY_ASSET = "sample_capture.bin"
        const val HEX_TAIL_CHARS = 1500
        const val HEX_CHUNK_BYTES = 256
        const val MAX_RECORDED_SWEEPS = 20_000
        const val WATERFALL_ROWS = 100
        const val MAX_PEAKS = 5
        const val PEAK_MIN_SEPARATION = 6

        // 6G Combo module ranges (MHz): WSUB3G expansion is low, 6G mainboard is high.
        const val MODULE_EXPANSION_MAX_MHZ = 2700.0
        const val MODULE_MAIN_MIN_MHZ = 4850.0
        const val MODULE_SWITCH_SETTLE_MS = 250L
    }
}
