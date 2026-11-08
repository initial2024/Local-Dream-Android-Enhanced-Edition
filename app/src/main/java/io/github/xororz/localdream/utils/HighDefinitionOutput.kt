package io.github.xororz.localdream.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Explicit image export. Re-renders pixels, it does NOT change the model's
 * native inference resolution or invent generative detail.
 */
object HighDefinitionOutput {
    const val CANVAS_WIDTH = 1440
    const val CANVAS_HEIGHT = 1088
    const val EXPORT_WIDTH = 1440
    const val EXPORT_HEIGHT = 1080
    private const val TOP_CROP = (CANVAS_HEIGHT - EXPORT_HEIGHT) / 2
    private const val MAX_EXPORT_PIXELS = 10_000_000L

    fun isHdCanvas(width: Int, height: Int): Boolean =
        width == CANVAS_WIDTH && height == CANVAS_HEIGHT

    /** Fast, lossless crop for the specially aligned DiT generation canvas. */
    fun cropTo1440x1080(bitmap: Bitmap): Bitmap {
        require(isHdCanvas(bitmap.width, bitmap.height)) {
            "Exact HD crop requires a 1440x1088 input"
        }
        return Bitmap.createBitmap(bitmap, 0, TOP_CROP, EXPORT_WIDTH, EXPORT_HEIGHT)
    }

    /**
     * Fit-and-center-crop in a single Canvas pass to avoid intermediate
     * full-resolution buffers. For smaller originals this is an upscale,
     * not an extra diffusion step. Never overwrites the original.
     */
    fun export(bitmap: Bitmap, width: Int = EXPORT_WIDTH, height: Int = EXPORT_HEIGHT): Bitmap {
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_EXPORT_PIXELS) {
            "Output must be positive and at most 10 megapixels"
        }
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            "Source bitmap is not available"
        }
        if (isHdCanvas(bitmap.width, bitmap.height) &&
            width == EXPORT_WIDTH && height == EXPORT_HEIGHT
        ) {
            return cropTo1440x1080(bitmap)
        }
        if (bitmap.width == width && bitmap.height == height) {
            // A new object is not needed; callers must not recycle this source.
            return bitmap
        }

        val scale = max(width.toDouble() / bitmap.width, height.toDouble() / bitmap.height)
        val srcWidth = (width / scale).roundToInt().coerceIn(1, bitmap.width)
        val srcHeight = (height / scale).roundToInt().coerceIn(1, bitmap.height)
        val left = (bitmap.width - srcWidth) / 2
        val top = (bitmap.height - srcHeight) / 2
        val source = Rect(left, top, left + srcWidth, top + srcHeight)
        val destination = Rect(0, 0, width, height)
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(bitmap, source, destination, Paint(
            Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG,
        ))
        return result
    }
}
