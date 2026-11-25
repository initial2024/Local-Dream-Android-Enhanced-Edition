package io.github.xororz.localdream.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import io.github.xororz.localdream.data.ObjectRemovalPolicy
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

data class ObjectRemovalImportedImage(
    val uri: Uri,
    val preview: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val mimeType: String?,
    val canReplace: Boolean,
)

suspend fun loadObjectRemovalPreview(
    context: Context,
    uri: Uri,
): ObjectRemovalImportedImage = withContext(Dispatchers.IO) {
    var sourceWidth = 0
    var sourceHeight = 0
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val preview = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        sourceWidth = info.size.width
        sourceHeight = info.size.height
        require(sourceWidth > 0 && sourceHeight > 0) { "Invalid image dimensions" }
        require(sourceWidth.toLong() * sourceHeight <= ObjectRemovalPolicy.MAX_SOURCE_PIXELS) {
            "Image exceeds the current 32 MP safe editing limit"
        }
        val scale = min(
            1f,
            ObjectRemovalPolicy.MAX_PREVIEW_SIDE.toFloat() /
                maxOf(sourceWidth, sourceHeight).toFloat(),
        )
        decoder.setTargetSize(
            (sourceWidth * scale).roundToInt().coerceAtLeast(1),
            (sourceHeight * scale).roundToInt().coerceAtLeast(1),
        )
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
    val canWrite = runCatching {
        context.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)
    ObjectRemovalImportedImage(
        uri = uri,
        preview = preview,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
        mimeType = context.contentResolver.getType(uri),
        canReplace = canWrite,
    )
}

fun objectRemovalMaskBounds(mask: Bitmap): ObjectRemovalPolicy.Box? {
    val row = IntArray(mask.width)
    var left = mask.width
    var top = mask.height
    var right = -1
    var bottom = -1
    for (y in 0 until mask.height) {
        mask.getPixels(row, 0, mask.width, 0, y, mask.width, 1)
        for (x in row.indices) {
            if ((row[x] ushr 24) > ObjectRemovalPolicy.MASK_ALPHA_THRESHOLD) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
    }
    return if (right >= left && bottom >= top) {
        ObjectRemovalPolicy.Box(left, top, right + 1, bottom + 1)
    } else null
}

suspend fun decodeObjectRemovalRegion(
    context: Context,
    uri: Uri,
    sourceBox: ObjectRemovalPolicy.Box,
    targetWidth: Int,
    targetHeight: Int,
): Bitmap = withContext(Dispatchers.IO) {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.setCrop(Rect(sourceBox.left, sourceBox.top, sourceBox.right, sourceBox.bottom))
        decoder.setTargetSize(targetWidth, targetHeight)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.isMutableRequired = false
    }
}

fun buildObjectRemovalWorkMask(
    previewMask: Bitmap,
    sourceBox: ObjectRemovalPolicy.Box,
    sourceWidth: Int,
    sourceHeight: Int,
    workWidth: Int,
    workHeight: Int,
): Bitmap {
    val previewBox = ObjectRemovalPolicy.mapSourceBoxToPreview(
        sourceBox,
        sourceWidth,
        sourceHeight,
        previewMask.width,
        previewMask.height,
    )
    val cropped = Bitmap.createBitmap(
        previewMask,
        previewBox.left,
        previewBox.top,
        previewBox.width,
        previewBox.height,
    )
    val scaled = Bitmap.createScaledBitmap(cropped, workWidth, workHeight, true)
    if (cropped !== scaled) cropped.recycle()
    return scaled
}

suspend fun compositeObjectRemovalPatch(
    context: Context,
    imported: ObjectRemovalImportedImage,
    sourceBox: ObjectRemovalPolicy.Box,
    previewMask: Bitmap,
    generatedPatch: Bitmap,
): Bitmap = withContext(Dispatchers.Default) {
    val full = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, imported.uri)
        ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    }
    require(full.width == imported.sourceWidth && full.height == imported.sourceHeight) {
        "Source image dimensions changed while editing"
    }

    val patch = Bitmap.createScaledBitmap(
        generatedPatch,
        sourceBox.width,
        sourceBox.height,
        true,
    )
    val previewBox = ObjectRemovalPolicy.mapSourceBoxToPreview(
        sourceBox,
        imported.sourceWidth,
        imported.sourceHeight,
        previewMask.width,
        previewMask.height,
    )
    val maskCrop = Bitmap.createBitmap(
        previewMask,
        previewBox.left,
        previewBox.top,
        previewBox.width,
        previewBox.height,
    )
    val mask = Bitmap.createScaledBitmap(maskCrop, sourceBox.width, sourceBox.height, true)
    if (mask !== maskCrop) maskCrop.recycle()

    val originalRow = IntArray(sourceBox.width)
    val patchRow = IntArray(sourceBox.width)
    val maskRow = IntArray(sourceBox.width)
    for (y in 0 until sourceBox.height) {
        full.getPixels(originalRow, 0, sourceBox.width, sourceBox.left, sourceBox.top + y, sourceBox.width, 1)
        patch.getPixels(patchRow, 0, sourceBox.width, 0, y, sourceBox.width, 1)
        mask.getPixels(maskRow, 0, sourceBox.width, 0, y, sourceBox.width, 1)
        for (x in originalRow.indices) {
            val alpha = (maskRow[x] ushr 24) and 0xff
            if (alpha <= ObjectRemovalPolicy.MASK_ALPHA_THRESHOLD) continue
            val src = originalRow[x]
            val gen = patchRow[x]
            if (alpha >= 250) {
                originalRow[x] = (src and -0x1000000) or (gen and 0x00ffffff)
            } else {
                val inv = 255 - alpha
                val rr = (((src shr 16) and 0xff) * inv + ((gen shr 16) and 0xff) * alpha) / 255
                val gg = (((src shr 8) and 0xff) * inv + ((gen shr 8) and 0xff) * alpha) / 255
                val bb = ((src and 0xff) * inv + (gen and 0xff) * alpha) / 255
                originalRow[x] = (src and -0x1000000) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        full.setPixels(originalRow, 0, sourceBox.width, sourceBox.left, sourceBox.top + y, sourceBox.width, 1)
    }
    if (patch !== generatedPatch) patch.recycle()
    mask.recycle()
    full
}

@Suppress("DEPRECATION")
suspend fun replaceImportedImageSafely(
    context: Context,
    imported: ObjectRemovalImportedImage,
    bitmap: Bitmap,
): Boolean = withContext(Dispatchers.IO) {
    if (!imported.canReplace) return@withContext false
    val mime = imported.mimeType?.lowercase()
    val format = when {
        mime == "image/png" -> Bitmap.CompressFormat.PNG
        mime == "image/jpeg" || mime == "image/jpg" -> Bitmap.CompressFormat.JPEG
        mime == "image/webp" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            Bitmap.CompressFormat.WEBP_LOSSLESS
        mime == "image/webp" -> Bitmap.CompressFormat.WEBP
        else -> return@withContext false
    }
    val workDir = File(context.cacheDir, "object_removal").apply { mkdirs() }
    val unique = System.nanoTime()
    val backup = File(workDir, "source-backup-" + unique + ".bin")
    val staged = File(workDir, "replacement-" + unique + ".img")
    try {
        context.contentResolver.openInputStream(imported.uri)?.use { input ->
            FileOutputStream(backup).use { output -> input.copyTo(output) }
        } ?: return@withContext false
        FileOutputStream(staged).use { out ->
            check(bitmap.compress(format, 100, out))
            out.fd.sync()
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(staged.absolutePath, options)
        check(options.outWidth == bitmap.width && options.outHeight == bitmap.height) {
            "Replacement image verification failed"
        }
        try {
            context.contentResolver.openOutputStream(imported.uri, "wt")?.use { output ->
                staged.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("Source provider is not writable")
            true
        } catch (writeError: Exception) {
            runCatching {
                context.contentResolver.openOutputStream(imported.uri, "wt")?.use { output ->
                    backup.inputStream().use { input -> input.copyTo(output) }
                }
            }
            throw writeError
        }
    } finally {
        backup.delete()
        staged.delete()
    }
}

fun cleanupObjectRemovalInternalFiles(context: Context) {
    listOf("object_remove_image.txt", "object_remove_mask.txt").forEach {
        runCatching { File(context.filesDir, it).delete() }
    }
    runCatching { File(context.cacheDir, "object_removal").deleteRecursively() }
}
