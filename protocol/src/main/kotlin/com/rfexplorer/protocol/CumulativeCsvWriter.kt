package com.rfexplorer.protocol

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes sweeps in RF Explorer's "cumulative CSV" shape: one header row of bin
 * frequencies (MHz), then one row per sweep — `Date,Time,<dBm>,<dBm>,...`.
 *
 * NEEDS-VERIFY: the exact column convention (separator, frequency precision, whether a
 * frequency header row is emitted) against RF Explorer for Windows output, so the
 * user's existing tooling ingests it unchanged. Structured so the format is easy to
 * tweak in one place once confirmed.
 *
 * Pure/host-side: timestamps are passed in, not read from a clock, so output is
 * deterministic and unit-testable.
 */
class CumulativeCsvWriter(
    private val separator: Char = ',',
) {
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** Header row: `Date,Time,<f0_MHz>,<f1_MHz>,...` for the given sweep geometry. */
    fun header(sweep: RfeMessage.Sweep): String = buildString {
        append("Date").append(separator).append("Time")
        for (i in 0 until sweep.pointCount) {
            append(separator)
            append(hzToMhz(sweep.frequencyHzAt(i)))
        }
    }

    /** One data row for [sweep] stamped at [timestamp]. */
    fun row(sweep: RfeMessage.Sweep, timestamp: Instant): String = buildString {
        append(date.format(timestamp)).append(separator).append(time.format(timestamp))
        for (a in sweep.amplitudesDbm) {
            append(separator)
            append(formatDbm(a))
        }
    }

    /** Full document for a batch of timestamped sweeps (assumes uniform geometry). */
    fun write(samples: List<Pair<Instant, RfeMessage.Sweep>>): String {
        if (samples.isEmpty()) return ""
        return buildString {
            appendLine(header(samples.first().second))
            for ((ts, sweep) in samples) appendLine(row(sweep, ts))
        }
    }

    private fun hzToMhz(hz: Long): String = String.format(Locale.US, "%.6f", hz / 1_000_000.0)

    private fun formatDbm(dbm: Float): String = String.format(Locale.US, "%.1f", dbm)
}
