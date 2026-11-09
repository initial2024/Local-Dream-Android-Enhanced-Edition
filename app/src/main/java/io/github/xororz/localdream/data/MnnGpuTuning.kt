package io.github.xororz.localdream.data

/**
 * OpenCL kernel-workgroup tuning policy for native MNN SD1.5.
 * FAST is the existing verified initialization path. NORMAL is an opt-in
 * performance experiment with a separate MNN cache per shape/model.
 */
object MnnGpuTuning {
    const val PREF_KEY = "mnn_gpu_tuning"
    const val FAST = "fast"
    const val NORMAL = "normal"

    fun validated(value: String?): String =
        if (value == NORMAL) NORMAL else FAST
}
