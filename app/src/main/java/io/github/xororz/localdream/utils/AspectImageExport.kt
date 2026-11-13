package io.github.xororz.localdream.utils

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * History export never re-runs inference, overwrites history files or
 * allocates a scaled bitmap above the bounded pixel budget.
 */
object AspectImageExport {
    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        percent: Int,
        format: ImageSaveFormat,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        var scaled: Bitmap? = null
        try {
            val image = withContext(Dispatchers.Default) {
                HighDefinitionOutput.exportPreservingAspect(bitmap, percent)
            }
            if (image !== bitmap) scaled = image
            saveImage(
                context = context,
                bitmap = image,
                onSuccess = onSuccess,
                onError = onError,
                formatOverride = format,
            )
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Export failed: ${e.localizedMessage}")
            }
        } finally {
            scaled?.recycle()
        }
    }
}
