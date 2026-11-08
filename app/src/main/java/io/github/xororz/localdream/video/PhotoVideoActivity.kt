package io.github.xororz.localdream.video

import android.os.Bundle
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offline slideshow editor. It deliberately does not invoke any image/DiT
 * model, request network access, or claim AI-generated motion.
 */
class PhotoVideoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { PhotoVideoEditor(onClose = { finish() }) }
        }
    }
}

@Composable
private fun PhotoVideoEditor(onClose: () -> Unit) {
    val app = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var portrait by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(2) }
    var highQuality by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var percent by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var exportJob by remember { mutableStateOf<Job?>(null) }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { items ->
        if (!running && items.isNotEmpty()) {
            photos = items.distinct().take(30)
            error = null
            saved = false
        }
    }
    val saveVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("video/mp4"),
    ) { destination ->
        if (destination != null && !running) {
            val snapshot = photos.toList()
            val vertical = portrait
            val photoSeconds = seconds
            val exportQuality = highQuality
            running = true
            saved = false
            percent = 0
            error = null
            exportJob = scope.launch {
                try {
                    PhotoVideoEncoder.export(
                        context = app,
                        photos = snapshot,
                        destination = destination,
                        portrait = vertical,
                        secondsPerPhoto = photoSeconds,
                        highQuality = exportQuality,
                        onProgress = { progress ->
                            withContext(Dispatchers.Main) { percent = progress }
                        },
                    )
                    saved = true
                } catch (failure: CancellationException) {
                    error = "Video export cancelled."
                    runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }
                } catch (failure: Exception) {
                    error = "Video export failed: ${failure.message ?: "Unknown encoder error"}"
                    runCatching { DocumentsContract.deleteDocument(app.contentResolver, destination) }
                } finally {
                    running = false
                    exportJob = null
                }
            }
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Photos → MP4", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !running) { Text("Close") }
            }
            Text(
                "Local photo slideshow, not an AI video diffusion model. No additional model " +
                    "download is needed. H.264, 720p, 24 or 30 fps, crossfade.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = !running,
                onClick = { pickPhotos.launch("image/*") },
            ) {
                Text("Choose 2–30 photos")
            }
            Text("Selected: ${photos.size} pictures", style = MaterialTheme.typography.titleSmall)
            photos.forEachIndexed { index, uri ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}. ${uri.lastPathSegment?.takeLast(30) ?: "Photo"}",
                        modifier = Modifier.weight(1f),
                        maxLines = 1)
                    TextButton(
                        enabled = !running && index > 0,
                        onClick = {
                            photos = photos.toMutableList().also {
                                val item = it.removeAt(index)
                                it.add(index - 1, item)
                            }
                        },
                    ) { Text("↑") }
                    TextButton(
                        enabled = !running && index < photos.lastIndex,
                        onClick = {
                            photos = photos.toMutableList().also {
                                val item = it.removeAt(index)
                                it.add(index + 1, item)
                            }
                        },
                    ) { Text("↓") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Portrait 720×1280", modifier = Modifier.weight(1f))
                Switch(checked = portrait, enabled = !running, onCheckedChange = { portrait = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Higher quality • 30 fps / 6 Mbps", modifier = Modifier.weight(1f))
                Switch(checked = highQuality, enabled = !running,
                    onCheckedChange = { highQuality = it })
            }
            Text("Time per photo: ${seconds}s")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(1, 2, 3, 4, 5)) {
                    OutlinedButton(
                        enabled = !running && photos.size * n <= 90,
                        onClick = { seconds = n },
                    ) { Text("${n}s") }
                }
            }
            val duration = photos.size * seconds
            Text("Approximate duration: ${duration}s (maximum 90s)")
            Button(
                enabled = photos.size in 2..30 && duration <= 90 && !running,
                onClick = { saveVideo.launch("LocalDream_PhotoVideo.mp4") },
            ) { Text("Export MP4") }
            if (running) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Encoding… ${percent}%", modifier = Modifier.weight(1f))
                    TextButton(onClick = { exportJob?.cancel() }) { Text("Cancel") }
                }
            }
            if (saved) Text("Export complete. The MP4 was saved to your selected location.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Video export reads only the photos you select. Original photos and model " +
                    "weights are not changed. Do not leave this screen during encoding.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
