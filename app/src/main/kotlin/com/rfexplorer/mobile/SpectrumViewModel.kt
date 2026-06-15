package com.rfexplorer.mobile

import androidx.lifecycle.ViewModel
import com.rfexplorer.protocol.RfeMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SKELETON — phase 5. Wires transport bytes -> parser -> UI state.
 *
 * Intended shape:
 *   transport.reads
 *     .parseRfeFrames()          // protocol module
 *     .filterIsInstance<Sweep>()
 *     .collect { _sweep.value = it }
 *
 * Kept hardware-agnostic by depending on the SerialTransport interface, so a
 * ReplayTransport drives the same path with no device attached.
 */
class SpectrumViewModel : ViewModel() {

    private val _latestSweep = MutableStateFlow<RfeMessage.Sweep?>(null)
    val latestSweep: StateFlow<RfeMessage.Sweep?> = _latestSweep.asStateFlow()

    private val _config = MutableStateFlow<RfeMessage.Config?>(null)
    val config: StateFlow<RfeMessage.Config?> = _config.asStateFlow()
}
