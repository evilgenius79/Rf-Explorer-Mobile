package com.rfexplorer.protocol

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ProtocolExtrasTest {

    private fun sweep(vararg dbm: Float, startHz: Long = 100_000_000, stepHz: Long = 1_000_000) =
        RfeMessage.Sweep(startHz, stepHz, dbm)

    @Test
    fun frequencyReconstructionAndPeak() {
        val s = sweep(-90f, -50f, -80f, startHz = 100_000_000, stepHz = 1_000_000)
        assertEquals(100_000_000L, s.frequencyHzAt(0))
        assertEquals(102_000_000L, s.frequencyHzAt(2))
        assertEquals(102_000_000L, s.endFreqHz)
        assertEquals(1, s.peakIndex())
        assertEquals(101_000_000L, s.peakFrequencyHz())
    }

    @Test
    fun maxHoldKeepsElementWiseMaximum() {
        val acc = SweepAccumulator(CalcMode.MAX_HOLD)
        acc.process(floatArrayOf(-90f, -50f, -80f))
        val out = acc.process(floatArrayOf(-95f, -60f, -10f))
        assertEquals(-90f, out[0], 0f)
        assertEquals(-50f, out[1], 0f)
        assertEquals(-10f, out[2], 0f)
    }

    @Test
    fun averageRollsOverWindow() {
        val acc = SweepAccumulator(CalcMode.AVG, avgWindow = 2)
        acc.process(floatArrayOf(-100f))
        acc.process(floatArrayOf(-80f))
        val out = acc.process(floatArrayOf(-60f)) // avg of last two: -80 and -60
        assertEquals(-70f, out[0], 0.0001f)
    }

    @Test
    fun accumulatorResetsWhenLengthChanges() {
        val acc = SweepAccumulator(CalcMode.MAX)
        acc.process(floatArrayOf(-10f, -10f))
        val out = acc.process(floatArrayOf(-90f, -90f, -90f)) // different size -> reset
        assertEquals(3, out.size)
        assertEquals(-90f, out[0], 0f)
    }

    @Test
    fun normalModeReturnsLiveCopy() {
        val acc = SweepAccumulator(CalcMode.NORMAL)
        val live = floatArrayOf(-1f, -2f)
        val out = acc.process(live)
        assertEquals(-1f, out[0], 0f)
        out[0] = 99f // must not mutate caller's array
        assertEquals(-1f, live[0], 0f)
    }

    @Test
    fun csvHeaderAndRowFormatting() {
        val w = CumulativeCsvWriter()
        val s = sweep(-30.0f, -8.5f, startHz = 100_000_000, stepHz = 1_000_000)
        assertEquals("Date,Time,100.000000,101.000000", w.header(s))

        val ts = Instant.parse("2026-06-15T07:08:09.250Z")
        assertEquals("2026-06-15,07:08:09.250,-30.0,-8.5", w.row(s, ts))
    }

    @Test
    fun calcModeFromCode() {
        assertEquals(CalcMode.MAX_HOLD, CalcMode.fromCode(4))
        assertEquals(CalcMode.UNKNOWN, CalcMode.fromCode(99))
        assertEquals(CalcMode.UNKNOWN, CalcMode.fromCode(null))
    }
}
