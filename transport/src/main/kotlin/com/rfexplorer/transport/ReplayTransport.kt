package com.rfexplorer.transport

import com.rfexplorer.protocol.Command
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.io.InputStream

/**
 * Plays back a recorded raw-byte stream so the UI can be built and demoed with no
 * hardware attached (kickoff phase plan, item 5 — "build this early; it doubles as a
 * test harness"). Writes are accepted and ignored.
 *
 * [chunkSize] and [interChunkDelayMs] emulate USB read boundaries and timing so the
 * frame parser is exercised against realistic, split frames.
 */
class ReplayTransport(
    private val source: () -> InputStream,
    private val chunkSize: Int = 256,
    private val interChunkDelayMs: Long = 0,
    private val loop: Boolean = false,
) : SerialTransport {

    override val state: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

    override val deviceInfo: StateFlow<String?> = MutableStateFlow("replay (no hardware)")

    override val reads: Flow<ByteArray> = flow {
        do {
            source().use { input ->
                val buf = ByteArray(chunkSize)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) emit(buf.copyOf(n))
                    if (interChunkDelayMs > 0) delay(interChunkDelayMs)
                }
            }
        } while (loop)
    }

    override suspend fun open() = Unit
    override suspend fun write(command: Command) = Unit
    override suspend fun write(bytes: ByteArray) = Unit
    override suspend fun close() = Unit
}
