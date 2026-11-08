package io.github.xororz.localdream.data

/**
 * Experimental native SD1.5 MNN canvas policy, independently of output export.
 *
 * MNN UNet/VAE tensor shapes are dynamic, but a successful JNI compile
 * cannot prove a Mali GPU has memory for a particular resolution.
 * A bounded, opt-in high-resolution choice prevents accidental giant
 * allocations while still exposing 1024x768 and 1024x1024 native pixels.
 */
object MnnNativeResolution {
    const val MIN_SIDE = 128
    const val MAX_SIDE = 1024
    const val STEP = 64
    const val MAX_PIXELS = 1024L * 1024L

    val presets: List<Resolution> = listOf(
        Resolution(512, 512),
        Resolution(640, 640),
        Resolution(768, 768),
        Resolution(1024, 768),
        Resolution(768, 1024),
        Resolution(1024, 1024),
    )

    fun isAllowed(width: Int, height: Int): Boolean =
        width in MIN_SIDE..MAX_SIDE &&
            height in MIN_SIDE..MAX_SIDE &&
            width % STEP == 0 &&
            height % STEP == 0 &&
            width.toLong() * height.toLong() <= MAX_PIXELS

    fun needsExperimentalWarning(width: Int, height: Int): Boolean =
        width > 512 || height > 512
}
