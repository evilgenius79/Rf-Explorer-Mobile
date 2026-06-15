package com.rfexplorer.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun requestConfig() {
        assertArrayEquals(bytes('#'.code, 0x04, 'C'.code, '0'.code), Command.RequestConfig.toBytes())
    }

    @Test
    fun requestHold() {
        assertArrayEquals(bytes('#'.code, 0x04, 'C'.code, 'H'.code), Command.RequestHold.toBytes())
    }

    @Test
    fun requestSerialNumber() {
        assertArrayEquals(bytes('#'.code, 0x04, 'C'.code, 'n'.code), Command.RequestSerialNumber.toBytes())
    }

    @Test
    fun switchModuleMainAndExpansion() {
        assertArrayEquals(bytes('#'.code, 0x05, 'C'.code, 'M'.code, 0x00), Command.SwitchModuleMain.toBytes())
        assertArrayEquals(bytes('#'.code, 0x05, 'C'.code, 'M'.code, 0x01), Command.SwitchModuleExpansion.toBytes())
    }

    @Test
    fun lcdToggle() {
        assertArrayEquals(bytes('#'.code, 0x04, 'L'.code, '0'.code), Command.DisableLcd.toBytes())
        assertArrayEquals(bytes('#'.code, 0x04, 'L'.code, '1'.code), Command.EnableLcd.toBytes())
    }

    @Test
    fun changeBaudrateUsesLowercaseC() {
        assertArrayEquals(bytes('#'.code, 0x04, 'c'.code, '0'.code), Command.ChangeBaudrate('0').toBytes())
    }

    @Test
    fun setSweepPointsEncodesInverseOfDecodeRule() {
        // (byte + 1) * 16 == points  ->  256 points => byte 15
        assertArrayEquals(bytes('#'.code, 0x05, 'C'.code, 'J'.code, 15), Command.SetSweepPoints(256).toBytes())
        // 16 points => byte 0, 4096 points => byte 255
        assertEquals(0, Command.SetSweepPoints(16).toBytes()[4].toInt() and 0xFF)
        assertEquals(255, Command.SetSweepPoints(4096).toBytes()[4].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun setSweepPointsRejectsNonMultipleOf16() {
        Command.SetSweepPoints(100)
    }

    @Test
    fun analyzerConfigIs32BytesWithCorrectFieldFormatting() {
        val cmd = Command.AnalyzerConfig(
            startFreqKhz = 2_400_000,
            endFreqKhz = 2_500_000,
            ampTopDbm = -30,
            ampBottomDbm = -118,
        )
        val out = cmd.toBytes()

        assertEquals("frame must be 32 bytes", 32, out.size)
        assertEquals('#'.code.toByte(), out[0])
        assertEquals(32.toByte(), out[1])

        val body = String(out, 2, out.size - 2, Charsets.US_ASCII)
        assertEquals("C2-F:2400000,2500000,-030,-118", body)
    }

    @Test
    fun analyzerConfigPadsPositiveAmplitudeToFourDigits() {
        val out = Command.AnalyzerConfig(0, 0, 10, 5).toBytes()
        val body = String(out, 2, out.size - 2, Charsets.US_ASCII)
        assertEquals("C2-F:0000000,0000000,0010,0005", body)
    }
}
