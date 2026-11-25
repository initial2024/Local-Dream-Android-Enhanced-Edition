package io.github.xororz.localdream.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import io.github.xororz.localdream.data.Model
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ObjectRemovalEngine {
    private const val IMAGE_FILE = "object_remove_image.txt"
    private const val MASK_FILE = "object_remove_mask.txt"

    private fun encodePngBase64(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
            "Could not encode local inpaint payload"
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    suspend fun run(
        context: Context,
        model: Model,
        source: Bitmap,
        mask: Bitmap,
        prompt: String,
        denoiseStrength: Float,
        useOpenCl: Boolean,
    ): Bitmap {
        require(model.isDownloaded && model.backendType == "sd15cpu") {
            "A downloaded local SD1.5 MNN model is required"
        }
        require(source.width == mask.width && source.height == mask.height)
        val imageFile = File(context.filesDir, IMAGE_FILE)
        val maskFile = File(context.filesDir, MASK_FILE)
        return try {
            withContext(Dispatchers.IO) {
                imageFile.writeText(encodePngBase64(source))
                maskFile.writeText(encodePngBase64(mask))
            }
            MnnBackendHandoff.withStandaloneGeneration(
                context = context,
                modelId = model.id,
                backendType = model.backendType,
                width = source.width,
                height = source.height,
            ) {
                BackgroundGenerationService.resetState()
                val defaults = model.defaults
                val intent = Intent(context, BackgroundGenerationService::class.java).apply {
                    putExtra("prompt", prompt)
                    putExtra("negative_prompt", defaults.negativePrompt)
                    putExtra("steps", defaults.steps.toInt().coerceIn(8, 30))
                    putExtra("cfg", defaults.cfg.coerceIn(1f, 12f))
                    putExtra("width", source.width)
                    putExtra("height", source.height)
                    putExtra("effective_width", source.width)
                    putExtra("effective_height", source.height)
                    putExtra("denoise_strength", denoiseStrength.coerceIn(0.35f, 1f))
                    putExtra("use_opencl", useOpenCl)
                    putExtra("scheduler", defaults.scheduler)
                    putExtra("aspect_ratio", "custom")
                    putExtra("backend_host", BackgroundGenerationService.LOCAL_BACKEND_HOST)
                    putExtra("has_image", true)
                    putExtra("has_mask", true)
                    putExtra(BackgroundGenerationService.EXTRA_IMAGE_FILE, IMAGE_FILE)
                    putExtra(BackgroundGenerationService.EXTRA_MASK_FILE, MASK_FILE)
                }
                context.startForegroundService(intent)
                val terminal = BackgroundGenerationService.generationState.first { state ->
                    state is BackgroundGenerationService.GenerationState.Complete ||
                        state is BackgroundGenerationService.GenerationState.Error
                }
                when (terminal) {
                    is BackgroundGenerationService.GenerationState.Complete -> {
                        val out = terminal.bitmap
                        BackgroundGenerationService.markBitmapConsumed()
                        withTimeoutOrNull(10_000L) {
                            BackgroundGenerationService.isServiceRunning.first { !it }
                        }
                        BackgroundGenerationService.resetState()
                        out
                    }
                    is BackgroundGenerationService.GenerationState.Error -> {
                        BackgroundGenerationService.resetState()
                        throw IllegalStateException(terminal.message)
                    }
                    else -> error("Unreachable generation state")
                }
            }
        } finally {
            BackgroundGenerationService.stop(context)
            imageFile.delete()
            maskFile.delete()
        }
    }
}
