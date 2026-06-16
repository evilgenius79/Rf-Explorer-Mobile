package com.rfexplorer.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class FrameParserTest {

    private val CR = 0x0D
    private val LF = 0x0A

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    /** Build a `$S` sweep frame from raw amplitude bytes (count byte = points/16 - 1). */
    private fun sweepFrameS(amplitudes: IntArray): ByteArray {
        require(amplitudes.size % 16 == 0)
        val countByte = amplitudes.size / 16 - 1
        val out = ByteArrayOutputStream()
        out.write('$'.code)
        out.write('S'.code)
        out.write(countByte)
        amplitudes.forEach { out.write(it) }
        out.write(CR); out.write(LF)
        return out.toByteArray()
    }

    /** Build a `$z` large-sweep frame with a 16-bit MSB-first count. */
    private fun sweepFrameZ(amplitudes: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write('$'.code)
        out.write('z'.code)
        out.write((amplitudes.size ushr 8) and 0xFF)
        out.write(amplitudes.size and 0xFF)
        amplitudes.forEach { out.write(it) }
        out.write(CR); out.write(LF)
        return out.toByteArray()
    }

    // 16 amplitude bytes that deliberately include the bytes which would break a
    // line-based parser: 0x0D, 0x0A, '$' (0x24), '#' (0x23). 0x11 decodes to -8.5.
    private val trickyAmps = intArrayOf(
        0x11, 0x0D, 0x0A, 0x24, 0x23, 0x00, 0xFF, 0x40,
        0x10, 0x20, 0x30, 0x01, 0x0D, 0x23, 0x24, 0x0A,
    )

    @Test
    fun parsesSetupLine() {
        val msgs = FrameParser().parse(ascii("#C2-M:006,005,01.12\r\n"))
        assertEquals(1, msgs.size)
        val setup = msgs[0] as RfeMessage.Setup
        assertEquals(6, setup.mainModel)
        assertEquals(5, setup.expansionModel)
        assertEquals("01.12", setup.firmwareVersion)
    }

    @Test
    fun parsesConfigLineAndNormalisesUnits() {
        // Start 100000 KHz (= 100 MHz), step 100 Hz.
        val line = "#C2-F:0100000,0000100,-030,-118,0112,0,0,0000000,9999999,0190000,0003,000,0\r\n"
        val parser = FrameParser()
        val msgs = parser.parse(ascii(line))
        assertEquals(1, msgs.size)
        val cfg = msgs[0] as RfeMessage.Config
        assertEquals(100_000L, cfg.startFreqKhz)
        assertEquals(100L, cfg.freqStepHz)
        assertEquals(100_000_000L, cfg.startFreqHz) // KHz -> Hz
        assertEquals(112, cfg.sweepPoints)
        assertEquals(-30, cfg.ampTopDbm)
        assertEquals(-118, cfg.ampBottomDbm)
        assertEquals(cfg, parser.lastConfig)
    }

    @Test
    fun decodesSweepAmplitudesBinarySafe() {
        val parser = FrameParser()
        // Establish a frequency axis first.
        parser.parse(ascii("#C2-F:0100000,0001000,-030,-118,0016\r\n"))

        val msgs = parser.parse(sweepFrameS(trickyAmps))
        assertEquals("payload bytes equal to CR/LF/\$/# must not split the frame", 1, msgs.size)
        val sweep = msgs[0] as RfeMessage.Sweep
        assertEquals(16, sweep.amplitudesDbm.size)
        assertEquals(-8.5f, sweep.amplitudesDbm[0], 0.0001f) // 0x11 -> -8.5
        assertEquals(-6.5f, sweep.amplitudesDbm[1], 0.0001f) // 0x0D -> -6.5
        assertEquals(-5.0f, sweep.amplitudesDbm[2], 0.0001f) // 0x0A -> -5.0
        assertEquals(-18.0f, sweep.amplitudesDbm[3], 0.0001f) // 0x24 -> -18.0
        assertEquals(-127.5f, sweep.amplitudesDbm[6], 0.0001f) // 0xFF -> -127.5
        assertEquals(100_000_000L, sweep.startFreqHz)
        assertEquals(1000L, sweep.stepFreqHz)
    }

    @Test
    fun reassemblesFrameSplitAcrossReads() {
        val frame = sweepFrameS(trickyAmps)
        val parser = FrameParser()
        parser.parse(ascii("#C2-F:0100000,0001000,-030,-118,0016\r\n"))

        // Split in the middle of the binary payload, right on a 0x0D byte.
        val splitAt = 8
        val first = parser.parse(frame.copyOfRange(0, splitAt))
        assertTrue("partial frame must yield nothing yet", first.isEmpty())

        val second = parser.parse(frame.copyOfRange(splitAt, frame.size))
        assertEquals(1, second.size)
        assertEquals(16, (second[0] as RfeMessage.Sweep).amplitudesDbm.size)
    }

    @Test
    fun parsesLargeSweepZVariant() {
        val amps = IntArray(64) { (it * 2) and 0xFF }
        val msgs = FrameParser().parse(sweepFrameZ(amps))
        assertEquals(1, msgs.size)
        assertEquals(64, (msgs[0] as RfeMessage.Sweep).amplitudesDbm.size)
    }

    @Test
    fun handlesMultipleFramesAndLeadingGarbageInOneChunk() {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x00, 0x7E)) // junk before the first marker
        out.write(ascii("#C2-F:0100000,0001000,-030,-118,0016\r\n"))
        out.write(sweepFrameS(trickyAmps))
        out.write(sweepFrameS(trickyAmps))

        val msgs = FrameParser().parse(out.toByteArray())
        assertEquals(3, msgs.size)
        assertNotNull(msgs[0] as RfeMessage.Config)
        assertTrue(msgs[1] is RfeMessage.Sweep)
        assertTrue(msgs[2] is RfeMessage.Sweep)
    }
}
