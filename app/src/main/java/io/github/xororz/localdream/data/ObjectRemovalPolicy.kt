package io.github.xororz.localdream.data

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ObjectRemovalPolicy {
    const val MAX_PREVIEW_SIDE = 1536
    const val MAX_WORK_SIDE = 640
    const val MIN_WORK_SIDE = 128
    const val MAX_SOURCE_PIXELS = 32_000_000L
    const val MASK_ALPHA_THRESHOLD = 12

    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val isValid: Boolean get() = width > 0 && height > 0
    }

    enum class ApproxBodyRegion {
        HEAD_SHOULDERS,
        UPPER_BODY,
        LOWER_BODY,
        FULL_BODY,
        OTHER,
    }

    /**
     * Coarse, local-only region estimate derived from the user's painted mask.
     * This intentionally does not try to infer anatomy or identity. It exists
     * only to improve clothing/object-edit prompts and UI hints.
     */
    fun classifyApproxBodyRegion(
        maskBoxPreview: Box,
        previewWidth: Int,
        previewHeight: Int,
    ): ApproxBodyRegion {
        require(maskBoxPreview.isValid)
        require(previewWidth > 0 && previewHeight > 0)
        val centerY = (maskBoxPreview.top + maskBoxPreview.bottom) / 2.0 / previewHeight
        val heightRatio = maskBoxPreview.height.toDouble() / previewHeight
        val widthRatio = maskBoxPreview.width.toDouble() / previewWidth

        if (heightRatio >= 0.55 || (heightRatio >= 0.45 && widthRatio >= 0.55)) {
            return ApproxBodyRegion.FULL_BODY
        }
        if (centerY < 0.24) return ApproxBodyRegion.HEAD_SHOULDERS
        if (centerY < 0.60) return ApproxBodyRegion.UPPER_BODY
        if (centerY <= 0.98) return ApproxBodyRegion.LOWER_BODY
        return ApproxBodyRegion.OTHER
    }

    fun paddedSourceBox(
        maskBoxPreview: Box,
        previewWidth: Int,
        previewHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Box {
        require(maskBoxPreview.isValid)
        require(previewWidth > 0 && previewHeight > 0)
        require(sourceWidth > 0 && sourceHeight > 0)
        require(sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS) {
            "Image is too large for safe full-resolution replacement on this device"
        }
        val sx = sourceWidth.toDouble() / previewWidth
        val sy = sourceHeight.toDouble() / previewHeight
        var left = floor(maskBoxPreview.left * sx).toInt()
        var top = floor(maskBoxPreview.top * sy).toInt()
        var right = ceil(maskBoxPreview.right * sx).toInt()
        var bottom = ceil(maskBoxPreview.bottom * sy).toInt()
        val padX = max(48, ((right - left) * 0.35f).roundToInt())
        val padY = max(48, ((bottom - top) * 0.35f).roundToInt())
        left = (left - padX).coerceAtLeast(0)
        top = (top - padY).coerceAtLeast(0)
        right = (right + padX).coerceAtMost(sourceWidth)
        bottom = (bottom + padY).coerceAtMost(sourceHeight)
        left = (left / 8) * 8
        top = (top / 8) * 8
        right = min(sourceWidth, ((right + 7) / 8) * 8)
        bottom = min(sourceHeight, ((bottom + 7) / 8) * 8)
        return Box(left, top, right, bottom)
    }

    fun workSize(sourceBox: Box): Pair<Int, Int> {
        require(sourceBox.isValid)
        val scale = min(1.0, MAX_WORK_SIDE.toDouble() / max(sourceBox.width, sourceBox.height))
        fun dimension(value: Int): Int {
            val raw = max(MIN_WORK_SIDE, (value * scale).roundToInt())
            return ((raw + 7) / 8 * 8).coerceIn(MIN_WORK_SIDE, MAX_WORK_SIDE)
        }
        return dimension(sourceBox.width) to dimension(sourceBox.height)
    }

    fun mapSourceBoxToPreview(
        sourceBox: Box,
        sourceWidth: Int,
        sourceHeight: Int,
        previewWidth: Int,
        previewHeight: Int,
    ): Box {
        val sx = previewWidth.toDouble() / sourceWidth
        val sy = previewHeight.toDouble() / sourceHeight
        return Box(
            floor(sourceBox.left * sx).toInt().coerceIn(0, previewWidth - 1),
            floor(sourceBox.top * sy).toInt().coerceIn(0, previewHeight - 1),
            ceil(sourceBox.right * sx).toInt().coerceIn(1, previewWidth),
            ceil(sourceBox.bottom * sy).toInt().coerceIn(1, previewHeight),
        )
    }
}
