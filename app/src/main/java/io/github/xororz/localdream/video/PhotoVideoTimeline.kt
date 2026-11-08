package io.github.xororz.localdream.video

/**
 * Deterministic, model-free slideshow timing. This is NOT generative video AI.
 * Transitions occupy the last part of each photo interval.
 */
internal object PhotoVideoTimeline {
    data class Frame(val index: Int, val nextIndex: Int?, val opacity: Float)

    fun totalFrames(photoCount: Int, fps: Int, secondsPerPhoto: Int): Int {
        require(photoCount in 2..30 && fps in 1..60 && secondsPerPhoto in 1..5)
        return photoCount * fps * secondsPerPhoto
    }

    fun frameAt(
        frame: Int,
        photoCount: Int,
        fps: Int,
        secondsPerPhoto: Int,
    ): Frame {
        val total = totalFrames(photoCount, fps, secondsPerPhoto)
        require(frame in 0 until total)
        val interval = fps * secondsPerPhoto
        val index = frame / interval
        val offset = frame % interval
        val fadeFrames = (fps * 0.35f).toInt().coerceIn(1, interval / 2)
        val fadeStart = interval - fadeFrames
        val next = if (index + 1 < photoCount && offset >= fadeStart) index + 1 else null
        val fraction = if (next != null) {
            ((offset - fadeStart + 1).toFloat() / fadeFrames).coerceIn(0f, 1f)
        } else {
            0f
        }
        return Frame(index, next, fraction)
    }
}
