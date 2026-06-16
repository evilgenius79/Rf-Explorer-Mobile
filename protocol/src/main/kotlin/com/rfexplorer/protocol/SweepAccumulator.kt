package com.rfexplorer.protocol

/**
 * Applies a [CalcMode] across successive sweeps to produce the trace the UI draws.
 *
 * Stateful and not thread-safe — drive it from the same place that collects sweeps.
 * Resets automatically when the sweep length changes (e.g. after a span/points change).
 */
class SweepAccumulator(
    var mode: CalcMode = CalcMode.NORMAL,
    var avgWindow: Int = 4,
) {
    private var hold: FloatArray? = null
    private val history = ArrayDeque<FloatArray>()
    private var lastSize = -1

    /** Feed the latest live amplitudes; returns the processed trace for [mode]. */
    fun process(live: FloatArray): FloatArray {
        if (live.size != lastSize) reset()
        lastSize = live.size

        return when (mode) {
            CalcMode.NORMAL, CalcMode.OVERWRITE, CalcMode.UNKNOWN -> live.copyOf()

            CalcMode.MAX, CalcMode.MAX_HOLD -> {
                val acc = hold ?: FloatArray(live.size) { Float.NEGATIVE_INFINITY }
                for (i in live.indices) if (live[i] > acc[i]) acc[i] = live[i]
                hold = acc
                acc.copyOf()
            }

            CalcMode.AVG -> {
                history.addLast(live.copyOf())
                while (history.size > avgWindow.coerceAtLeast(1)) history.removeFirst()
                val out = FloatArray(live.size)
                for (frame in history) for (i in out.indices) out[i] += frame[i]
                val n = history.size.toFloat()
                for (i in out.indices) out[i] /= n
                out
            }
        }
    }

    /** Clear held/averaged state (call on reconnect or config change). */
    fun reset() {
        hold = null
        history.clear()
        lastSize = -1
    }
}
