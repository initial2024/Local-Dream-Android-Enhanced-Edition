package io.github.xororz.localdream.data

/**
 * Host-side guard for the Android native backends shipped by Local Dream.
 *
 * The MNN CPU/OpenCL backends are the first-class choice on MediaTek.
 * Qualcomm HTP/QNN and Hexagon DiT require a compatible Snapdragon runtime.
 * This is intentionally independent of UI catalog filters and can be tested
 * on an ordinary JVM without an Android device.
 */
object BackendCompatibility {
    /**
     * Returns null when the backend is allowed, or an actionable reason when
     * it must be rejected BEFORE a native child process is started.
     */
    fun rejection(
        backendType: String,
        qualcommSoc: Boolean,
        compatibleHexagon: Boolean,
        mnnOnlyBuild: Boolean = false,
    ): String? = when {
        // The MNN-only executable also ships a separately validated SR entry.
        // It performs local 4x inference without QNN/Hexagon dependencies.
        mnnOnlyBuild && backendType == "upscaler" -> null
        mnnOnlyBuild && backendType != "sd15cpu" ->
            "This MNN-only build currently supports SD1.5 CPU/OpenCL only; " +
                "other native backends are still under development."
        else -> deviceRejection(backendType, qualcommSoc, compatibleHexagon)
    }

    private fun deviceRejection(
        backendType: String,
        qualcommSoc: Boolean,
        compatibleHexagon: Boolean,
    ): String? = when (backendType) {
        "sd15cpu", "sdxlmnn" -> null
        "sd15npu", "sdxl", "anima", "upscaler" -> if (qualcommSoc) {
            null
        } else {
            "This model requires Qualcomm QNN/HTP. Use an MNN CPU/OpenCL model on this phone."
        }
        "zimage", "klein", "qwen21", "krea2turbo" -> if (qualcommSoc && compatibleHexagon) {
            null
        } else {
            "This DiT runtime requires a compatible Snapdragon Hexagon engine. " +
                "A MediaTek GPU backend has not been validated."
        }
        else -> "Unknown inference backend: $backendType"
    }

    fun isMnn(backendType: String): Boolean = backendType in setOf("sd15cpu", "sdxlmnn")
}
