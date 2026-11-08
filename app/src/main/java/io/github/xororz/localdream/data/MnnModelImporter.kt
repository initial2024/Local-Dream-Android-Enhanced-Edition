package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Imports one preconverted, original-format SD1.5 MNN ZIP into a NEW model
 * directory. It NEVER converts safetensors/CKPT and NEVER replaces/deletes
 * an existing model. Extraction stays under a hidden sibling staging dir,
 * then a same-filesystem rename exposes the validated finished marker.
 */
object MnnModelImporter {
    suspend fun import(
        context: Context,
        uri: Uri,
        onProgress: suspend (bytes: Long) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        if (!ModelStorage.beginMnnImport()) {
            throw IOException("Finish any generation, download or storage move before importing")
        }
        var staging: File? = null
        try {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            } ?: uri.lastPathSegment ?: throw IOException("Model ZIP has no filename")
            if (!name.endsWith(".zip", ignoreCase = true)) {
                throw IOException("Select a Local Dream SD1.5 MNN ZIP file, not safetensors/CKPT")
            }
            val id = MnnModelPackage.cleanId(name)
            if (id.isBlank() || ModelRepository.isReservedModelId(id, ignoreCase = true)) {
                throw IOException("Invalid or reserved model name; rename the ZIP file and retry")
            }
            val root = Model.getModelsDir(context)
            if (!root.isDirectory && !root.mkdirs()) {
                throw IOException("Cannot access the selected model storage directory")
            }
            val dest = File(root, id)
            if (dest.exists()) {
                throw IOException("Model '$id' exists; rename the ZIP. Existing files were untouched")
            }
            if (root.usableSpace < MnnModelPackage.MIN_FREE_BYTES) {
                throw IOException("Not enough storage for model import")
            }
            staging = File(root, ".mnn-import-${UUID.randomUUID()}")
            if (!staging.mkdir()) throw IOException("Cannot create temporary import directory")

            val seen = mutableSetOf<String>()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var entryCount = 0
            var lastProgress = 0L

            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot read the selected ZIP")
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MnnModelPackage.MAX_FILES) {
                        throw IOException("Too many archive entries")
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val fileName = MnnModelPackage.safePackageFile(entry.name)
                        ?: throw IOException("Unsupported or unsafe ZIP entry: ${entry.name.take(100)}")
                    if (!seen.add(fileName.lowercase())) {
                        throw IOException("Duplicate file in archive: $fileName")
                    }
                    val output = File(staging, fileName)
                    var fileBytes = 0L
                    FileOutputStream(output).use { out ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = zip.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            total += n
                            fileBytes += n
                            if (total > MnnModelPackage.MAX_TOTAL_BYTES ||
                                (fileName.endsWith(".json") &&
                                    fileBytes > MnnModelPackage.MAX_JSON_BYTES)
                            ) {
                                throw IOException("Archive exceeds safe extraction limit")
                            }
                            out.write(buffer, 0, n)
                            if (total - lastProgress >= 32L * 1024 * 1024) {
                                onProgress(total)
                                lastProgress = total
                            }
                        }
                    }
                    if (fileBytes == 0L) throw IOException("Empty model component: $fileName")
                    zip.closeEntry()
                }
            }
            if (!MnnModelPackage.containsRequired(seen)) {
                val missing = MnnModelPackage.requiredFiles - seen
                throw IOException("Incomplete original-format MNN ZIP; missing: ${missing.joinToString()}")
            }
            if (dest.exists()) throw IOException("Model name became occupied during import")
            File(staging, "finished").writeText("mnn-import-v1\n")
            if (!staging.renameTo(dest)) {
                throw IOException("Cannot atomically commit imported MNN model")
            }
            staging = null
            onProgress(total)
            id
        } finally {
            staging?.deleteRecursively()
            ModelStorage.endMnnImport()
        }
    }
}
