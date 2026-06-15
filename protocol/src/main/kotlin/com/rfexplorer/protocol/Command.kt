package com.rfexplorer.protocol

/**
 * Commands sent PC -> device over UART.
 *
 * Framing (from the RF Explorer UART API spec):
 *   `#` + <Size> + <payload>
 * where <Size> is a single binary byte equal to the TOTAL message length in bytes,
 * INCLUDING the leading `#` and the size byte itself. Max 64.
 *
 * Comma-separated command fields use bare commas, no spaces (the wiki's `, ` is
 * for readability only). All numeric fields are fixed-width ASCII, zero-padded.
 *
 * Byte layouts here follow the kickoff spec table; anything marked NEEDS-VERIFY
 * must be cross-checked against RFExplorer-for-.NET / -Python before trusting on
 * hardware.
 */
sealed class Command {
    abstract fun toBytes(): ByteArray

    /** `#04C0` — ask the device to emit its Current_Config. */
    data object RequestConfig : Command() {
        override fun toBytes() = frame('C', '0')
    }

    /** `#04CH` — stop the sweep dump (hold). */
    data object RequestHold : Command() {
        override fun toBytes() = frame('C', 'H')
    }

    /** `#04Cn` — read the device serial number. */
    data object RequestSerialNumber : Command() {
        override fun toBytes() = frame('C', 'n')
    }

    /** `#05CM\x00` — activate the 6G mainboard. */
    data object SwitchModuleMain : Command() {
        override fun toBytes() = frame('C', 'M', 0x00)
    }

    /** `#05CM\x01` — activate the WSUB3G expansion module. */
    data object SwitchModuleExpansion : Command() {
        override fun toBytes() = frame('C', 'M', 0x01)
    }

    /** `#04L0` — disable the device LCD (saves draw cycles while streaming). */
    data object DisableLcd : Command() {
        override fun toBytes() = frame('L', '0')
    }

    /** `#04L1` — re-enable the device LCD. */
    data object EnableLcd : Command() {
        override fun toBytes() = frame('L', '1')
    }

    /**
     * `#04c<code>` (lowercase `c`). code '0' = 500 Kbps.
     * Other codes exist but 500 Kbps is the only rate we drive; 2400 is fallback.
     */
    data class ChangeBaudrate(val code: Char = '0') : Command() {
        override fun toBytes() = frame('c', code)
    }

    /**
     * `#05CJ<pts_byte>` — set sweep size. NEEDS-VERIFY.
     * The device->PC sweep count rule is (byte + 1) * 16, so we encode the inverse:
     * pts_byte = points/16 - 1 (16 -> 0, 4096 -> 255). Confirm against reference lib.
     */
    data class SetSweepPoints(val points: Int) : Command() {
        init {
            require(points in 16..4096 && points % 16 == 0) {
                "points must be a multiple of 16 in 16..4096, was $points"
            }
        }

        override fun toBytes() = frame('C', 'J', (points / 16 - 1))
    }

    /**
     * `#20C2-F:<Start>,<End>,<AmpTop>,<AmpBottom>` — set span + amplitude window.
     * Start/End = 7 ASCII digits in KHz; AmpTop/AmpBottom = 4 ASCII chars in dBm.
     * Size = 1 + 1 + 5 + 7 + 1 + 7 + 1 + 4 + 1 + 4 = 32 bytes.
     */
    data class AnalyzerConfig(
        val startFreqKhz: Long,
        val endFreqKhz: Long,
        val ampTopDbm: Int,
        val ampBottomDbm: Int,
    ) : Command() {
        override fun toBytes(): ByteArray {
            val body = buildString {
                append("C2-F:")
                append(freq7(startFreqKhz)); append(',')
                append(freq7(endFreqKhz)); append(',')
                append(amp4(ampTopDbm)); append(',')
                append(amp4(ampBottomDbm))
            }
            return frame(body)
        }
    }
}

private const val START_MARKER: Byte = '#'.code.toByte()
private const val MAX_MESSAGE_BYTES = 64

/** Build a framed message from raw payload bytes (everything after the size byte). */
private fun frame(payload: ByteArray): ByteArray {
    val size = payload.size + 2 // '#' + size byte
    require(size <= MAX_MESSAGE_BYTES) { "message length $size exceeds $MAX_MESSAGE_BYTES" }
    val out = ByteArray(size)
    out[0] = START_MARKER
    out[1] = size.toByte()
    payload.copyInto(out, destinationOffset = 2)
    return out
}

private fun frame(body: String): ByteArray = frame(body.toByteArray(Charsets.US_ASCII))

/** Convenience for short commands expressed as a mix of ASCII chars and raw byte values. */
private fun frame(vararg parts: Any): ByteArray {
    val payload = ByteArray(parts.size) { i ->
        when (val p = parts[i]) {
            is Char -> p.code.toByte()
            is Int -> p.toByte()
            is Byte -> p
            else -> error("unsupported payload part: $p")
        }
    }
    return frame(payload)
}

/** 7-digit zero-padded ASCII, e.g. 2400000. */
private fun freq7(value: Long): String {
    require(value in 0..9_999_999) { "frequency $value out of 7-digit range" }
    return value.toString().padStart(7, '0')
}

/**
 * 4-char zero-padded dBm, sign included in the width: -30 -> "-030", 10 -> "0010".
 * Matches String.format("%04d", v) used by the reference libraries.
 */
private fun amp4(value: Int): String {
    require(value in -999..9999) { "amplitude $value out of 4-char range" }
    return if (value < 0) {
        "-" + (-value).toString().padStart(3, '0')
    } else {
        value.toString().padStart(4, '0')
    }
}
