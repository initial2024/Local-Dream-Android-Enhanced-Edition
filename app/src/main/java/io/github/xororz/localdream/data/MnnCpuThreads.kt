package io.github.xororz.localdream.data

/**
 * CPU thread selection for the independent SD1.5 MNN native executable.
 *
 * A tunable setting is preferable to claiming 8 threads is always faster on
 * a heterogeneous MediaTek big.LITTLE CPU: users can benchmark 2/4/6/8.
 * This setting has no effect on OpenCL kernels. Keep invalid persisted values
 * from reaching the native command-line.
 */
object MnnCpuThreads {
    const val PREF_KEY = "mnn_cpu_threads"
    const val DEFAULT = 4
    const val MIN = 1
    const val MAX = 8

    fun validated(requested: Int): Int = requested.coerceIn(MIN, MAX)
}
