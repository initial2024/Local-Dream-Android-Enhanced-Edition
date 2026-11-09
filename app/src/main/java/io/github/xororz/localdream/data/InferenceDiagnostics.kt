package io.github.xororz.localdream.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last 20 local image generation timings. No prompt, negative prompt,
 * source image, seed, auth data, network URL or model file paths are stored.
 * The available-memory number is SYSTEM RAM, not actual Mali GPU memory.
 */
object InferenceDiagnostics {
    data class Sample(
        val availableRamMiB: Long,
        val lowMemory: Boolean,
        val thermalStatus: Int,
        val batteryTenthsC: Int,
    )

    fun sample(context: Context): Sample {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
        val battery = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        return Sample(info.availMem / (1024 * 1024), info.lowMemory, thermal, battery)
    }

    fun memoryPressure(sample: Sample): Boolean =
        sample.lowMemory || (sample.availableRamMiB in 0..2047)

    private fun reportFile(context: Context) =
        File(context.filesDir, "inference_diagnostics.jsonl")

    @Synchronized
    fun record(
        context: Context,
        modelId: String,
        width: Int,
        height: Int,
        steps: Int,
        opencl: Boolean,
        profile: String,
        tuning: String,
        elapsedMs: Long,
        firstProgressMs: Long?,
        started: Sample,
        finished: Sample,
        success: Boolean,
        nativeTotalMs: Long? = null,
        nativeFirstUnetStepMs: Long? = null,
        clipTimeMs: Long? = null,
        unetSetupTimeMs: Long? = null,
        denoiseLoopTimeMs: Long? = null,
        vaeDecodeTimeMs: Long? = null,
    ) {
        val line = JSONObject().apply {
            put("at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            put("model", modelId)
            put("canvas", "${width}x$height")
            put("steps", steps)
            put("gpu", opencl)
            put("profile", profile)
            put("tuning", tuning)
            put("duration_ms", elapsedMs)
            // First progress is usually after CLIP, NOT the first UNet step.
            put("first_progress_ms", firstProgressMs ?: -1L)
            put("native_generation_ms", nativeTotalMs ?: -1L)
            put("native_first_unet_step_ms", nativeFirstUnetStepMs ?: -1L)
            put("clip_ms", clipTimeMs ?: -1L)
            put("unet_setup_ms", unetSetupTimeMs ?: -1L)
            put("denoise_loop_ms", denoiseLoopTimeMs ?: -1L)
            put("vae_decode_ms", vaeDecodeTimeMs ?: -1L)
            put("ram_before_MiB", started.availableRamMiB)
            put("ram_after_MiB", finished.availableRamMiB)
            put("low_ram_before", started.lowMemory)
            put("low_ram_after", finished.lowMemory)
            put("thermal_before", started.thermalStatus)
            put("thermal_after", finished.thermalStatus)
            put("battery_temp_before_tenths_C", started.batteryTenthsC)
            put("battery_temp_after_tenths_C", finished.batteryTenthsC)
            put("success", success)
            // Success means the inference pipeline returned; it is NOT an
            // assessment of image semantics or an image-quality guarantee.
            put("image_quality_verified", false)
        }.toString()
        val file = reportFile(context)
        val tail = if (file.isFile) file.readLines().takeLast(19) else emptyList()
        file.writeText((tail + line).joinToString("\n", postfix = "\n"))
    }

    fun report(context: Context): String {
        val file = reportFile(context)
        return if (file.isFile && file.length() > 0L) {
            "Local MNN inference diagnostics (last 20, JSONL).\n" +
                "System RAM is not GPU allocation; denoise_loop_ms includes scheduler/CPU work.\n" +
                "first_progress_ms is not first UNet inference; native times are server-side.\n" +
                "Thermal status 0..6, -1=unknown.\n" +
                "success means server completed, NOT verified image quality.\n" +
                "image_quality_verified=false until an on-device reference test.\n" +
                "No prompts, seeds or image contents are included.\n" +
                file.readText()
        } else {
            "No local MNN generation measurements recorded yet."
        }
    }
}
