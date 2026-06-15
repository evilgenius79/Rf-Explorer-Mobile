package com.rfexplorer.transport

import android.content.Context
import com.rfexplorer.protocol.Command
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * USB-host transport for the RF Explorer over a CP210x bridge.
 *
 * SKELETON — phase 3 of the kickoff plan. The byte layout and CP210x line settings are
 * settled ([SerialConfig]); what remains can only be confirmed against the physical
 * unit. The first on-device checkpoint is: enumerate the device, confirm VID/PID, open
 * the port at 500000 8N1, and see bytes flow.
 *
 * Implementation outline (using usb-serial-for-android, mik3y):
 *  1. Enumerate: `UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)`.
 *     If the RF Explorer's customised PID isn't recognised, build a custom ProbeTable
 *     mapping [SerialConfig.SILABS_VENDOR_ID] + actual PID to Cp21xxSerialDriver.
 *  2. Permission: request via PendingIntent; handle ACTION_USB_PERMISSION and
 *     ACTION_USB_DEVICE_ATTACHED / DETACHED broadcasts.
 *  3. Open: `port.open(connection)` then
 *     `port.setParameters(BAUD_RATE, DATA_BITS, STOPBITS_1, PARITY_NONE)`.
 *  4. Read loop on a dedicated IO dispatcher; emit chunks to [reads]; buffer in the
 *     parser (frames span reads).
 *  5. Ensure the device is sweeping at 500 Kbps (send Request_Config / AnalyzerConfig),
 *     or the line stays silent.
 */
class UsbSerialTransport(
    @Suppress("unused") private val context: Context,
) : SerialTransport {

    private val _reads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val reads: Flow<ByteArray> = _reads

    override suspend fun open() {
        TODO("phase 3: enumerate, request USB permission, open CP210x at 500000 8N1")
    }

    override suspend fun write(command: Command) = write(command.toBytes())

    override suspend fun write(bytes: ByteArray) {
        TODO("phase 3: port.write(bytes, WRITE_TIMEOUT_MS)")
    }

    override suspend fun close() {
        TODO("phase 3: stop read loop and close the port")
    }
}
