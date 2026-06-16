package com.rfexplorer.protocol

/**
 * Streaming, binary-safe parser for the device->PC byte stream.
 *
 * WHY A STATE MACHINE AND NOT readLine():
 * Sweep payloads are raw binary; an amplitude byte can legally equal 0x0D, 0x0A,
 * `$` or `#`. Splitting on EOL would corrupt frames. Instead we locate a frame
 * marker, read a length-prefixed payload of exactly the expected size, then consume
 * the trailing EOL. Frames routinely span multiple USB reads, so unconsumed bytes
 * are buffered between [parse] calls.
 *
 * Frame markers:
 *   `#C2-M:` / `#C2-F:`  ASCII line terminated by CRLF (Setup / Config)
 *   `$S`, `$s`          sweep, count byte -> (byte + 1) * 16 points
 *   `$z`                sweep, 16-bit MSB-first point count
 *
 * Not thread-safe: feed one stream from one coroutine/thread.
 */
class FrameParser {

    private var buffer = ByteArray(0)

    /** Most recent Config — supplies frequency axis for subsequent sweeps. */
    var lastConfig: RfeMessage.Config? = null
        private set

    /** Feed a raw read chunk; returns every complete message it unlocked (may be empty). */
    fun parse(chunk: ByteArray): List<RfeMessage> {
        if (chunk.isEmpty() && buffer.isEmpty()) return emptyList()
        buffer += chunk

        val out = mutableListOf<RfeMessage>()
        var pos = 0
        val b = buffer

        while (true) {
            val start = markerIndex(b, pos)
            if (start < 0) {
                // No frame start in the remainder — discard leading garbage.
                pos = b.size
                break
            }
            pos = start

            if (b[pos] == DOLLAR) {
                if (pos + 2 > b.size) break // need marker + variant
                when (b[pos + 1]) {
                    S_UPPER, S_LOWER -> {
                        if (pos + 3 > b.size) break // need count byte
                        val points = ((b[pos + 2].toInt() and 0xFF) + 1) * 16
                        val payloadStart = pos + 3
                        val frameEnd = payloadStart + points + EOL_LEN
                        if (frameEnd > b.size) break // wait for full payload + EOL
                        out += decodeSweep(b, payloadStart, points)
                        pos = frameEnd
                    }

                    Z_LOWER -> {
                        if (pos + 4 > b.size) break // need 16-bit count
                        val hi = b[pos + 2].toInt() and 0xFF
                        val lo = b[pos + 3].toInt() and 0xFF
                        val points = (hi shl 8) or lo
                        val payloadStart = pos + 4
                        val frameEnd = payloadStart + points + EOL_LEN
                        if (frameEnd > b.size) break
                        out += decodeSweep(b, payloadStart, points)
                        pos = frameEnd
                    }

                    else -> pos += 1 // lone '$' or unknown variant — resync past it
                }
            } else { // '#': ASCII line terminated by CRLF
                val eol = eolIndex(b, pos)
                if (eol < 0) break // wait for the rest of the line
                val line = String(b, pos, eol - pos, Charsets.ISO_8859_1)
                handleLine(line)?.let { out += it }
                pos = eol + EOL_LEN
            }
        }

        // Drop everything we consumed; keep any partial frame for the next read.
        buffer = if (pos == 0) b else b.copyOfRange(pos, b.size)
        return out
    }

    /** Clear buffered bytes and remembered config (e.g. on reconnect). */
    fun reset() {
        buffer = ByteArray(0)
        lastConfig = null
    }

    private fun decodeSweep(b: ByteArray, offset: Int, points: Int): RfeMessage.Sweep {
        // AdBm decode: unsigned byte / 2, negated. 0x11 (17) -> -8.5 dBm.
        val amps = FloatArray(points) { i -> -((b[offset + i].toInt() and 0xFF) / 2.0f) }
        val cfg = lastConfig
        return RfeMessage.Sweep(
            startFreqHz = cfg?.startFreqHz ?: 0L,
            stepFreqHz = cfg?.freqStepHz ?: 0L,
            amplitudesDbm = amps,
        )
    }

    private fun handleLine(line: String): RfeMessage? = when {
        line.startsWith("#C2-M:") -> parseSetup(line.substring(6))
        line.startsWith("#C2-F:") -> parseConfig(line.substring(6))?.also { lastConfig = it }
        else -> null // unrecognised '#' line — consumed but ignored
    }

    private fun parseSetup(rest: String): RfeMessage.Setup? {
        val f = rest.split(',')
        if (f.size < 3) return null
        val main = f[0].trim().toIntOrNull() ?: return null
        val exp = f[1].trim().toIntOrNull() ?: return null
        return RfeMessage.Setup(main, exp, f[2].trim())
    }

    private fun parseConfig(rest: String): RfeMessage.Config? {
        val f = rest.split(',')
        if (f.size < 5) return null
        fun s(i: Int) = f.getOrNull(i)?.trim()
        val startKhz = s(0)?.toLongOrNull() ?: return null
        val stepHz = s(1)?.toLongOrNull() ?: return null
        return RfeMessage.Config(
            startFreqKhz = startKhz,
            freqStepHz = stepHz,
            ampTopDbm = s(2)?.toIntOrNull() ?: 0,
            ampBottomDbm = s(3)?.toIntOrNull() ?: 0,
            sweepPoints = s(4)?.toIntOrNull() ?: 0,
            expModuleActive = s(5)?.toIntOrNull(),
            currentMode = s(6)?.toIntOrNull(),
            minFreqKhz = s(7)?.toLongOrNull(),
            maxFreqKhz = s(8)?.toLongOrNull(),
            maxSpanKhz = s(9)?.toLongOrNull(),
            rbwKhz = s(10)?.toIntOrNull(),
            ampOffsetDb = s(11)?.toIntOrNull(),
            calculatorMode = s(12)?.toIntOrNull(),
            rawFields = f,
        )
    }

    private companion object {
        const val HASH: Byte = '#'.code.toByte()
        const val DOLLAR: Byte = '$'.code.toByte()
        const val CR: Byte = 0x0D
        const val LF: Byte = 0x0A
        const val S_UPPER: Byte = 'S'.code.toByte()
        const val S_LOWER: Byte = 's'.code.toByte()
        const val Z_LOWER: Byte = 'z'.code.toByte()
        const val EOL_LEN = 2

        fun markerIndex(b: ByteArray, from: Int): Int {
            var i = from
            while (i < b.size) {
                if (b[i] == HASH || b[i] == DOLLAR) return i
                i++
            }
            return -1
        }

        fun eolIndex(b: ByteArray, from: Int): Int {
            var i = from
            while (i < b.size - 1) {
                if (b[i] == CR && b[i + 1] == LF) return i
                i++
            }
            return -1
        }
    }
}
