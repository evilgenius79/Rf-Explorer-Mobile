package com.rfexplorer.protocol

/**
 * Client-side trace processing modes, mirroring the device's CalculatorMode values so
 * the UI can offer the same options. We apply these on the host across successive
 * sweeps; the device can also do them internally.
 *
 * Codes follow RFExplorer-for-.NET's RFE_CalculatorModes. NEEDS-VERIFY against the
 * reference lib before relying on the numeric mapping for device round-trips.
 */
enum class CalcMode(val code: Int) {
    /** Show each sweep as-is. */
    NORMAL(0),

    /** Running element-wise maximum since the last reset (decaying not applied). */
    MAX(1),

    /** Rolling average over the last [SweepAccumulator.avgWindow] sweeps. */
    AVG(2),

    /** Same as NORMAL for our purposes (device overwrites its buffer). */
    OVERWRITE(3),

    /** Element-wise maximum held indefinitely. */
    MAX_HOLD(4),

    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int?): CalcMode = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
