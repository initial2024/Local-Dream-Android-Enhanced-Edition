package io.github.xororz.localdream.utils

import android.graphics.Bitmap

/** Source canvas and export target are deliberately distinct. */
object HighDefinitionOutput {
    const val CANVAS_WIDTH = 1440
    const val CANVAS_HEIGHT = 1088
    const val EXPORT_WIDTH = 1440
    const val EXPORT_HEIGHT = 1080
    private const val TOP_CROP = (CANVAS_HEIGHT - EXPORT_HEIGHT) / 2

    fun isHdCanvas(width: Int, height: Int): Boolean =
        width == CANVAS_WIDTH && height == CANVAS_HEIGHT

    /** Called only by the explicit HD export action; original pixels remain unchanged. */
    fun cropTo1440x1080(bitmap: Bitmap): Bitmap {
        require(isHdCanvas(bitmap.width, bitmap.height)) {
            "HD export requires a 1440x1088 image"
        }
        return Bitmap.createBitmap(bitmap, 0, TOP_CROP, EXPORT_WIDTH, EXPORT_HEIGHT)
    }
}
