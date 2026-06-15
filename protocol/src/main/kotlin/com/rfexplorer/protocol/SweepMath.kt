package com.rfexplorer.protocol

/** Frequency/amplitude helpers over a parsed [RfeMessage.Sweep]. Pure, host-side. */

/** Number of frequency bins in the sweep. */
val RfeMessage.Sweep.pointCount: Int get() = amplitudesDbm.size

/** Centre/edge frequency reconstruction: freq[i] = startFreqHz + i * stepFreqHz. */
fun RfeMessage.Sweep.frequencyHzAt(index: Int): Long = startFreqHz + index.toLong() * stepFreqHz

/** Frequency of the last bin (Hz). Returns startFreqHz for an empty sweep. */
val RfeMessage.Sweep.endFreqHz: Long
    get() = if (amplitudesDbm.isEmpty()) startFreqHz else frequencyHzAt(amplitudesDbm.size - 1)

/** Index of the strongest bin, or -1 if the sweep is empty. */
fun RfeMessage.Sweep.peakIndex(): Int {
    if (amplitudesDbm.isEmpty()) return -1
    var best = 0
    for (i in 1 until amplitudesDbm.size) {
        if (amplitudesDbm[i] > amplitudesDbm[best]) best = i
    }
    return best
}

/** Frequency (Hz) of the strongest bin, or null if the sweep is empty. */
fun RfeMessage.Sweep.peakFrequencyHz(): Long? {
    val i = peakIndex()
    return if (i < 0) null else frequencyHzAt(i)
}
