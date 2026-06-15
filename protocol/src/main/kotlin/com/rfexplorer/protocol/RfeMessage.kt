package com.rfexplorer.protocol

/** A parsed message decoded from the device->PC byte stream. */
sealed interface RfeMessage {

    /**
     * `#C2-M:<Main_Model>,<Expansion_Model>,<Firmware_Version>`
     *
     * Main_Model 6G = 6. Expansion_Model: 2.4G = 4, WSUB3G = 5, NONE = 255.
     */
    data class Setup(
        val mainModel: Int,
        val expansionModel: Int,
        val firmwareVersion: String,
    ) : RfeMessage

    /**
     * `#C2-F:<Start_Freq>,<Freq_Step>,<Amp_Top>,<Amp_Bottom>,<Sweep_points>,...`
     *
     * Field count is version-dependent (1.08 / 1.11 / 1.12), so trailing fields are
     * nullable and the full split is retained in [rawFields].
     *
     * UNIT TRAP: Start_Freq is KHz, Freq_Step is Hz. [startFreqHz] normalises.
     */
    data class Config(
        val startFreqKhz: Long,
        val freqStepHz: Long,
        val ampTopDbm: Int,
        val ampBottomDbm: Int,
        val sweepPoints: Int,
        val expModuleActive: Int? = null,
        val currentMode: Int? = null,
        val minFreqKhz: Long? = null,
        val maxFreqKhz: Long? = null,
        val maxSpanKhz: Long? = null,
        val rbwKhz: Int? = null,
        val ampOffsetDb: Int? = null,
        val calculatorMode: Int? = null,
        val rawFields: List<String> = emptyList(),
    ) : RfeMessage {
        val startFreqHz: Long get() = startFreqKhz * 1000L
    }

    /**
     * A single spectrum sweep. Frequencies are reconstructed from the most recent
     * [Config] (the `$S/$s/$z` payload carries amplitudes only):
     *   freq[i] = startFreqHz + i * stepFreqHz
     */
    data class Sweep(
        val startFreqHz: Long,
        val stepFreqHz: Long,
        val amplitudesDbm: FloatArray,
    ) : RfeMessage {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sweep) return false
            return startFreqHz == other.startFreqHz &&
                stepFreqHz == other.stepFreqHz &&
                amplitudesDbm.contentEquals(other.amplitudesDbm)
        }

        override fun hashCode(): Int {
            var result = startFreqHz.hashCode()
            result = 31 * result + stepFreqHz.hashCode()
            result = 31 * result + amplitudesDbm.contentHashCode()
            return result
        }
    }
}
