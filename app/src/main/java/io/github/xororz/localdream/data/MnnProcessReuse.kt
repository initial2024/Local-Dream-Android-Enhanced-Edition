package io.github.xororz.localdream.data

/**
 * Stable identity for a local MNN inference server process.
 *
 * Unlike a QNN NPU graph, the standalone SD1.5 MNN server accepts the
 * requested canvas width/height in each /generate call and dynamically
 * resizes its tensors. Resizing does not need to reload the tokenizer
 * or recreate the Android native process.
 */
data class MnnProcessSignature(
    val modelId: String,
    val backendType: String,
    val cpuThreads: Int,
    val listenOnAll: Boolean,
)

object MnnProcessReuse {
    fun canKeepProcess(
        mnnOnlyBuild: Boolean,
        processAlive: Boolean,
        forceRestart: Boolean,
        current: MnnProcessSignature?,
        requested: MnnProcessSignature,
    ): Boolean =
        mnnOnlyBuild &&
            processAlive &&
            !forceRestart &&
            current != null &&
            current == requested &&
            requested.backendType == "sd15cpu"
}
