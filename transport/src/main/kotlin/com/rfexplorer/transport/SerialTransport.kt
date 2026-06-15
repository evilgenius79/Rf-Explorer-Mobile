package com.rfexplorer.transport

import com.rfexplorer.protocol.Command
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction over the physical link to the RF Explorer.
 *
 * Implementations expose a [Flow] of raw read chunks; callers pipe that straight into
 * [com.rfexplorer.protocol.parseRfeFrames]. Keeping this an interface lets the app run
 * against a replay/file source with no hardware attached (see kickoff phase plan).
 */
interface SerialTransport {

    /** Raw bytes as they arrive from the device. Not framed — feed into the parser. */
    val reads: Flow<ByteArray>

    /** Current connection lifecycle state. */
    val state: StateFlow<ConnectionState>

    /** Open the port at the configured line settings. Suspends until connected. */
    suspend fun open()

    /** Send a framed command. */
    suspend fun write(command: Command)

    /** Send raw bytes (escape hatch for commands not yet modelled). */
    suspend fun write(bytes: ByteArray)

    /** Close the port and release the USB interface. Idempotent. */
    suspend fun close()
}

/** Lifecycle of a [SerialTransport]. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data class Error(val message: String) : ConnectionState
}

/** CP210x line settings the RF Explorer 6G Combo requires (kickoff "Hardware facts"). */
object SerialConfig {
    const val BAUD_RATE = 500_000
    const val FALLBACK_BAUD_RATE = 2_400
    const val DATA_BITS = 8
    // No parity, 1 stop bit, no flow control.

    /** Silicon Labs USB vendor id. */
    const val SILABS_VENDOR_ID = 0x10C4

    /** Typical CP2102 product id. NEEDS-VERIFY against the actual RF Explorer EEPROM. */
    const val CP2102_PRODUCT_ID = 0xEA60
}
