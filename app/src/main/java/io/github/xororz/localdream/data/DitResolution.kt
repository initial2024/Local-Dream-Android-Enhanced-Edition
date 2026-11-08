package io.github.xororz.localdream.data

import kotlin.math.roundToInt

/**
 * Resolutions offered for DiT models. The models themselves only need multiples
 * of 16; the 64-pixel step keeps the slider usable while covering the common
 * aspect ratios.
 */
object DitResolution {
    const val MIN_SIZE = 512
    const val MAX_SIZE = 2048
    const val SIZE_STEP = 64
    const val SLIDER_STEPS = (MAX_SIZE - MIN_SIZE) / SIZE_STEP - 1

    // HD 4:3 preset uses 16-aligned width between normal slider ticks.
    // Preserve it on saved-parameter restoration.
    fun snap(value: Float): Int = if (value.roundToInt() == 1440) {
        1440
    } else {
        ((value / SIZE_STEP).roundToInt() * SIZE_STEP).coerceIn(MIN_SIZE, MAX_SIZE)
    }

    fun snap(value: Int): Int = snap(value.toFloat())

    fun isSupported(value: Int): Boolean = value in MIN_SIZE..MAX_SIZE &&
        ((value - MIN_SIZE) % SIZE_STEP == 0 || value == 1440)
}
