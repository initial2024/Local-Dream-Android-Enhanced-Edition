package io.github.xororz.localdream.data

/**
 * OpenCL kernel-workgroup tuning policy for native MNN SD1.5.
 * Device evidence on 640x640/20-step Mali OpenCL showed experimental NORMAL
 * at 382.4s versus FAST 158.5s, with increased setup, UNet and VAE times.
 * Fail closed: old persisted NORMAL settings must never reach the backend.
 * Model weights, user history, prompt and diffusion geometry are untouched.
 */
object MnnGpuTuning {
    const val PREF_KEY = "mnn_gpu_tuning"
    const val FAST = "fast"
    const val NORMAL = "normal"

    fun validated(value: String?): String = FAST
}
