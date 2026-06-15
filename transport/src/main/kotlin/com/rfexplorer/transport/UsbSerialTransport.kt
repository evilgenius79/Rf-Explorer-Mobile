package com.rfexplorer.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.rfexplorer.protocol.Command
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/**
 * USB-host transport for the RF Explorer over a Silicon Labs CP210x bridge, using
 * usb-serial-for-android (mik3y).
 *
 * Flow: enumerate -> request USB permission -> open port at 500000 8N1 -> read loop on
 * an IO dispatcher emitting raw chunks into [reads]. Frames span reads, so callers must
 * feed [reads] into the protocol FrameParser, which buffers across boundaries.
 *
 * UNVERIFIED ON HARDWARE. The first on-device checkpoint (kickoff phase 3): confirm a
 * device is found with the expected VID/PID, the port opens, and bytes flow while the
 * unit is actively sweeping at 500 Kbps.
 */
class UsbSerialTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SerialTransport {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _reads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    override val reads: Flow<ByteArray> = _reads

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var port: UsbSerialPort? = null
    private var readJob: Job? = null

    override suspend fun open() {
        _state.value = ConnectionState.Connecting
        try {
            val driver = findDriver() ?: run {
                _state.value = ConnectionState.Error("No RF Explorer / CP210x device found")
                return
            }
            val device = driver.device

            if (!usbManager.hasPermission(device) && !requestPermission(device)) {
                _state.value = ConnectionState.Error("USB permission denied")
                return
            }

            val connection = usbManager.openDevice(device)
                ?: throw IOException("openDevice returned null (permission revoked?)")

            val p = driver.ports.first()
            p.open(connection)
            p.setParameters(
                SerialConfig.BAUD_RATE,
                SerialConfig.DATA_BITS,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            // CP210x needs the modem lines asserted before it streams.
            runCatching {
                p.dtr = true
                p.rts = true
            }
            port = p
            _state.value = ConnectionState.Connected
            startReadLoop(p)
        } catch (e: Exception) {
            _state.value = ConnectionState.Error(e.message ?: e.javaClass.simpleName)
            closeQuietly()
        }
    }

    override suspend fun write(command: Command) = write(command.toBytes())

    override suspend fun write(bytes: ByteArray) {
        val p = port ?: throw IllegalStateException("port not open")
        withContext(Dispatchers.IO) { p.write(bytes, WRITE_TIMEOUT_MS) }
    }

    override suspend fun close() {
        readJob?.cancel()
        readJob = null
        withContext(Dispatchers.IO) { closeQuietly() }
        _state.value = ConnectionState.Disconnected
    }

    private fun startReadLoop(p: UsbSerialPort) {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (true) {
                    val n = p.read(buffer, READ_TIMEOUT_MS)
                    if (n > 0) _reads.emit(buffer.copyOf(n))
                }
            } catch (e: IOException) {
                _state.value = ConnectionState.Error(e.message ?: "read failed")
            } finally {
                closeQuietly()
            }
        }
    }

    private fun closeQuietly() {
        runCatching { port?.close() }
        port = null
    }

    /** Default prober first; fall back to a custom CP210x probe for a non-standard PID. */
    private fun findDriver(): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).firstOrNull()?.let { return it }

        val custom = ProbeTable().apply {
            addProduct(
                SerialConfig.SILABS_VENDOR_ID,
                SerialConfig.CP2102_PRODUCT_ID,
                Cp21xxSerialDriver::class.java,
            )
        }
        return UsbSerialProber(custom).findAllDrivers(usbManager).firstOrNull()
    }

    /** Suspends until the user grants/denies the USB permission dialog. */
    private suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION_USB_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags,
            )
            usbManager.requestPermission(device, pi)
        }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.rfexplorer.transport.USB_PERMISSION"
        private const val READ_BUFFER_SIZE = 4096
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 1000

        /** Devices currently attached that look like a serial/CP210x bridge (for UI lists). */
        fun attachedSerialDevices(context: Context): List<UsbDevice> {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .map { it.device }
        }
    }
}
