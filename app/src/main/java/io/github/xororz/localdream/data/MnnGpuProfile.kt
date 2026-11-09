package io.github.xororz.localdream.data

/**
 * Native MNN/OpenCL profile selected for the next generation request.
 *
 * Auto protects the native 640+ canvas by processing CFG halves sequentially;
 * the original buffered/batched implementation remains available unchanged.
 * This is a memory-vs-throughput control, not an unmeasured speed claim.
 */
object MnnGpuProfile {
    const val PREF_KEY = "mnn_gpu_profile"
    const val AUTO = "auto"
    const val BUFFER = "buffer"
    const val LOW_MEMORY = "lowmem"
    const val IMAGE = "image"

    val options = listOf(AUTO, BUFFER, LOW_MEMORY, IMAGE)

    fun validated(raw: String?): String = raw?.takeIf { it in options } ?: AUTO

    fun effective(raw: String?, width: Int, height: Int): String {
        val selected = validated(raw)
        return if (selected == AUTO) {
            if (width > 512 || height > 512) LOW_MEMORY else BUFFER
        } else selected
    }
}
