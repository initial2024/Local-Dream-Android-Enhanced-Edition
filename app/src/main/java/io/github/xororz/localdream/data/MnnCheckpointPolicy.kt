package io.github.xororz.localdream.data

/**
 * Explicit capacity and format gate for the experimental LOCAL SD1.5
 * safetensors -> MNN conversion. This is not SDXL, DiT or QNN conversion.
 */
object MnnCheckpointPolicy {
    const val MIN_BYTES = 100L * 1024 * 1024
    const val MAX_BYTES = 6L * 1024 * 1024 * 1024
    const val MIN_FREE_AFTER_COPY = 512L * 1024 * 1024

    fun acceptableSourceSize(bytes: Long): Boolean =
        bytes in MIN_BYTES..MAX_BYTES

    fun safeModelId(rawName: String): String? {
        val sanitized = MnnModelPackage.cleanId(rawName)
        return sanitized.takeIf {
            it.isNotEmpty() &&
                it.length <= 48 &&
                !ModelRepository.isReservedModelId(it, ignoreCase = true)
        }
    }

    /** Allow source + generated weights + a margin without overflow. */
    fun requiredFreeBytes(checkpointBytes: Long): Long? {
        if (!acceptableSourceSize(checkpointBytes)) return null
        // 2x for the input copy and output weights, plus extra reserve.
        return checkpointBytes * 3 + MIN_FREE_AFTER_COPY
    }
}
