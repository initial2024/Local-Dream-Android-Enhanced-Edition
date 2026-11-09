package io.github.xororz.localdream.data

/**
 * A one-tap replay is permitted only when the existing SD1.5 MNN model and
 * the source snapshot match. Historical img2img/masks are not stored, so
 * those jobs can only restore editable parameters, never silently regenerate.
 */
object HistoryReplayPolicy {
    fun canAutoGenerate(
        sourceModelId: String?,
        activeModelId: String,
        mode: GenerationMode,
        seed: Long?,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        modelDownloaded: Boolean,
        mnnOnly: Boolean,
        runOnCpu: Boolean,
        remote: Boolean,
        backendReady: Boolean,
        busy: Boolean,
        hasInputImageOrReferences: Boolean,
    ): Boolean =
        !sourceModelId.isNullOrBlank() &&
        sourceModelId == activeModelId &&
        mode == GenerationMode.TXT2IMG &&
        seed != null && seed >= 0L &&
        steps in 1..50 && cfg.isFinite() && cfg in 0f..30f &&
        MnnNativeResolution.isAllowed(width, height) &&
        mnnOnly && runOnCpu && modelDownloaded &&
        !remote && backendReady && !busy && !hasInputImageOrReferences
}
