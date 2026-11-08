package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Offline SD1.5 safetensors-to-MNN conversion via the upstream converter.
 * This is experimental, memory-intensive and deliberately FAIL CLOSED.
 *
 * All source and converter artifacts live in a new hidden temporary folder.
 * Nothing overwrites the user's existing model directory, even on errors.
 * The final model becomes visible only after validating native outputs.
 */
object MnnSafetensorsConverter {
    suspend fun convert(
        context: Context,
        uri: Uri,
        requestedName: String,
        clipSkip: Int,
        // Only the foreground checkpoint installer can pass an existing
        // reservation and move its own SHA256-verified temp download.
        reservationHeld: Boolean = false,
        moveVerifiedCheckpoint: Boolean = false,
        onProgress: suspend (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (clipSkip != 1 && clipSkip != 2) {
            throw IOException("Unsupported CLIP skip mode")
        }
        val modelId = if (reservationHeld && MnnModelSuggestions.options.any {
                it.modelId == requestedName
            }) {
            requestedName
        } else {
            MnnCheckpointPolicy.safeModelId(requestedName)
                ?: throw IOException("Invalid or reserved model name")
        }
        if (!reservationHeld && !ModelStorage.beginMnnImport()) {
            throw IOException("Stop generation, downloads and model moves before conversion")
        }

        var staging: File? = null
        var process: Process? = null
        try {
            val sourceSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: -1L
            val standardRequired = MnnCheckpointPolicy.requiredFreeBytes(sourceSize)
                ?: throw IOException("Only a complete SD1.5 checkpoint from 100 MiB to 6 GiB is accepted")
            val ownedInput = if (moveVerifiedCheckpoint && reservationHeld && uri.scheme == "file") {
                val inputFile = File(uri.path ?: throw IOException("Checkpoint path missing")).canonicalFile
                val downloadDir = ModelStorage.tempDownloadsDir(context).canonicalFile
                if (inputFile.parentFile != downloadDir || !inputFile.isFile ||
                    !inputFile.name.endsWith(".tmp")
                ) {
                    throw IOException("Refusing to move a file outside the app's temporary download folder")
                }
                inputFile
            } else if (moveVerifiedCheckpoint) {
                throw IOException("Only a leased verified application download can be moved")
            } else null
            // The downloaded checkpoint already occupies storage. Moving it
            // into staging avoids a second 2 GiB copy and its extra I/O cost.
            val required = if (ownedInput != null) {
                sourceSize * 2 + MnnCheckpointPolicy.MIN_FREE_AFTER_COPY
            } else standardRequired
            val root = Model.getModelsDir(context)
            if (!root.isDirectory && !root.mkdirs()) {
                throw IOException("Selected model storage directory is inaccessible")
            }
            if (root.usableSpace < required) {
                throw IOException("Not enough free disk space: needs about ${required / (1024 * 1024)} MiB")
            }
            val dest = File(root, modelId)
            if (dest.exists()) {
                throw IOException("Model '$modelId' already exists; it will not be overwritten")
            }
            staging = File(root, ".mnn-convert-${UUID.randomUUID()}")
            if (!staging.mkdir()) {
                throw IOException("Cannot reserve temporary model directory")
            }
            val work = staging

            onProgress("Preparing checkpoint and conversion templates")
            val stagedCheckpoint = File(work, "model.safetensors")
            if (ownedInput != null) {
                if (ownedInput.length() != sourceSize || !ownedInput.renameTo(stagedCheckpoint)) {
                    throw IOException("Verified checkpoint could not be moved to conversion staging")
                }
            } else {
                val source = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Cannot open checkpoint")
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                source.use { input ->
                    stagedCheckpoint.outputStream().use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            total += n
                            if (total > MnnCheckpointPolicy.MAX_BYTES ||
                                work.usableSpace < MnnCheckpointPolicy.MIN_FREE_AFTER_COPY
                            ) {
                                throw IOException("Checkpoint exceeds size or free-space guard")
                            }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (!MnnCheckpointPolicy.acceptableSourceSize(total) ||
                    total != sourceSize
                ) throw IOException("Truncated or incompatible source checkpoint")
            }

            // Exactly the pinned original Local Dream MNN conversion templates.
            // Untrusted model packages are not allowed to supply their own
            // converter graph or execute arbitrary code.
            val base = context.assets
            for (part in listOf(
                "clip_skip_1.mnn", "clip_skip_2.mnn",
                "unet.mnn", "vae_decoder.mnn", "vae_encoder.mnn", "tokenizer.json",
            )) {
                base.open("cvtbase/$part").use { input ->
                    File(work, part).outputStream().use { out -> input.copyTo(out) }
                }
            }
            val graph = File(work, if (clipSkip == 2) "clip_skip_2.mnn" else "clip_skip_1.mnn")
            graph.copyTo(File(work, "clip_v2.mnn"), overwrite = false)

            val executable = File(context.applicationInfo.nativeLibraryDir,
                "libstable_diffusion_core.so")
            if (!executable.isFile) throw IOException("Local MNN converter binary is missing")
            onProgress("Converting offline (can need several GiB RAM and several minutes)")
            val start = SystemClock.elapsedRealtime()
            val command = mutableListOf(
                executable.absolutePath, "--convert", work.absolutePath,
            )
            if (clipSkip == 2) command += "--clip_skip_2"
            process = ProcessBuilder(command).apply {
                redirectErrorStream(true)
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir +
                    ":/system/lib64:/vendor/lib64"
                directory(File(context.applicationInfo.nativeLibraryDir))
            }.start()
            // Drain output to prevent native process pipe blockage; never log
            // checkpoint contents or private prompt text.
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    Log.i("MnnConvert", line.take(250))
                }
            }
            currentCoroutineContext().ensureActive()
            val exitCode = process.waitFor()
            if (exitCode != 0) throw IOException("Native conversion failed (exit code $exitCode)")
            for (name in MnnModelPackage.requiredFiles) {
                val file = File(work, name)
                if (!file.isFile || file.length() <= 0) {
                    throw IOException("Missing MNN conversion output: $name")
                }
            }
            for (name in listOf(
                "clip_v2.mnn.weight", "unet.mnn.weight",
                "vae_decoder.mnn.weight", "vae_encoder.mnn.weight",
            )) {
                val file = File(work, name)
                if (!file.isFile || file.length() <= 0) {
                    throw IOException("Missing converted MNN weights: $name")
                }
            }
            if (!File(work, "finished").isFile) {
                throw IOException("Missing converter completion marker")
            }
            currentCoroutineContext().ensureActive()

            // Space recovery is limited to known source/temp files in our own
            // never-published staging directory, not an existing model.
            File(work, "model.safetensors").delete()
            File(work, "clip_skip_1.mnn").delete()
            File(work, "clip_skip_2.mnn").delete()
            if (dest.exists()) throw IOException("Destination appeared during conversion")
            if (!work.renameTo(dest)) {
                throw IOException("Could not atomically publish converted model")
            }
            staging = null
            Log.i("MnnConvert", "Offline conversion finished in " +
                (SystemClock.elapsedRealtime() - start) + " ms")
            // A UI notification failure must not roll back a successful
            // atomic import or misleadingly report a missing model.
            runCatching { onProgress("Model ready to select: $modelId") }
            modelId
        } finally {
            if (process?.isAlive == true) {
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
            }
            staging?.deleteRecursively()
            if (!reservationHeld) ModelStorage.endMnnImport()
        }
    }
}
