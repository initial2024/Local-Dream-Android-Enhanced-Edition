package io.github.xororz.localdream.service

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ObjectRemovalPolicy
import io.github.xororz.localdream.data.SurfaceSpotRepair
import io.github.xororz.localdream.utils.ObjectRemovalImportedImage
import io.github.xororz.localdream.utils.buildObjectRemovalWorkMask
import io.github.xororz.localdream.utils.compositeObjectRemovalPatch
import io.github.xororz.localdream.utils.decodeObjectRemovalRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Experimental scene-background completion. Scoped to the independent
 * ordinary-surface tool, without changing other generation modes or models.
 */
object SceneBackgroundRepair {
    private const val MAX_SOURCE_PIXELS = 16_000_000L
    private const val MAX_SELECTED_PIXELS = 4_000

    suspend fun run(
        context: Context,
        sourceUri: Uri,
        preview: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        selection: SurfaceSpotRepair.Selection,
        model: Model,
    ): Bitmap {
        require(model.isDownloaded && model.backendType == "sd15cpu") {
            "Install a local MNN SD1.5 model before using generative repair"
        }
        require(selection.width == preview.width && selection.height == preview.height) {
            "Selection does not match the imported preview"
        }
        require(sourceWidth > 0 && sourceHeight > 0 &&
            sourceWidth.toLong() * sourceHeight <= MAX_SOURCE_PIXELS) {
            "Imported image exceeds the safe local size limit"
        }
        require(selection.mask.size == selection.width * selection.height)
        val count = selection.count
        require(count in 1..MAX_SELECTED_PIXELS) {
            "Generative repair requires one small, isolated scene object"
        }

        // Convert the clicked region into the opaque-white mask expected by
        // the existing native inpainting path. No image or mask leaves device.
        val mask = withContext(Dispatchers.Default) {
            val src = IntArray(selection.mask.size) { i ->
                if (selection.mask[i]) -1 else 0
            }
            Bitmap.createBitmap(
                src, selection.width, selection.height, Bitmap.Config.ARGB_8888,
            )
        }
        var workImage: Bitmap? = null
        var workMask: Bitmap? = null
        var generated: Bitmap? = null
        try {
            var left = selection.width
            var top = selection.height
            var right = -1
            var bottom = -1
            for (i in selection.mask.indices) {
                if (!selection.mask[i]) continue
                val x = i % selection.width
                val y = i / selection.width
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
            val box = ObjectRemovalPolicy.paddedSourceBox(
                ObjectRemovalPolicy.Box(left, top, right + 1, bottom + 1),
                selection.width, selection.height, sourceWidth, sourceHeight,
            )
            val (w, h) = ObjectRemovalPolicy.workSize(box)
            workImage = decodeObjectRemovalRegion(context, sourceUri, box, w, h)
            workMask = withContext(Dispatchers.Default) {
                buildObjectRemovalWorkMask(
                    mask, box, sourceWidth, sourceHeight, w, h,
                )
            }
            generated = ObjectRemovalEngine.run(
                context = context,
                model = model,
                source = workImage,
                mask = workMask,
                prompt = "an empty natural background seamlessly continuing the surrounding " +
                    "surface, consistent perspective, lighting, texture and color, " +
                    "no object, no text, no artifacts",
                denoiseStrength = 0.65f,
                useOpenCl = true,
            )
            // The exclusive MNN handoff has stopped the native generator
            // before the original full-resolution image is decoded.
            val imported = ObjectRemovalImportedImage(
                uri = sourceUri,
                preview = preview,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                mimeType = null,
                canReplace = false,
            )
            return compositeObjectRemovalPatch(
                context = context,
                imported = imported,
                sourceBox = box,
                previewMask = mask,
                generatedPatch = generated,
            )
        } finally {
            generated?.takeIf { !it.isRecycled }?.recycle()
            workImage?.takeIf { !it.isRecycled }?.recycle()
            workMask?.takeIf { !it.isRecycled }?.recycle()
            if (!mask.isRecycled) mask.recycle()
        }
    }
}
