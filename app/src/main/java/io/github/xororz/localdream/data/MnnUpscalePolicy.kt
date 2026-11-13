package io.github.xororz.localdream.data

/** Size budget for on-device tiled MNN 4x AI SR, before RGB buffer allocation. */
object MnnUpscalePolicy {
    const val NATIVE_SCALE = 4
    const val MAX_SIDE = 640
    const val MAX_INPUT_PIXELS = MAX_SIDE * MAX_SIDE

    fun resultSize(width: Int, height: Int): Pair<Int, Int> {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE &&
            width.toLong() * height <= MAX_INPUT_PIXELS) {
            "Local AI 4x accepts images up to 640 × 640 pixels. " +
                "Resize/crop the image before AI super-resolution."
        }
        return width * NATIVE_SCALE to height * NATIVE_SCALE
    }
}
