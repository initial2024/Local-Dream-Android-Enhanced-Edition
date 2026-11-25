package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.data.SurfaceSpotRepair
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.service.SceneBackgroundRepair
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.utils.ImageSaveFormat
import io.github.xororz.localdream.utils.saveImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private const val MAX_SOURCE_PIXELS = 16_000_000L

private data class SpotSource(
    val uri: Uri,
    val preview: Bitmap,
    val width: Int,
    val height: Int,
)

private fun previewSize(width: Int, height: Int): Pair<Int, Int> {
    val factor = minOf(1f, SurfaceSpotRepair.MAX_SIDE.toFloat() / maxOf(width, height))
    return (width * factor).roundToInt().coerceAtLeast(1) to
        (height * factor).roundToInt().coerceAtLeast(1)
}

private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
}

/**
 * Original-sized output; only the bounded selected spot changes. The decoder
 * uses the same orientation-aware path for preview and final image.
 */
private fun applySpot(
    full: Bitmap,
    selection: SurfaceSpotRepair.Selection,
): Bitmap {
    val sw = selection.width
    val sh = selection.height
    var minX = sw
    var minY = sh
    var maxX = -1
    var maxY = -1
    for (i in selection.mask.indices) if (selection.mask[i]) {
        minX = minOf(minX, i % sw)
        maxX = maxOf(maxX, i % sw)
        minY = minOf(minY, i / sw)
        maxY = maxOf(maxY, i / sw)
    }
    require(maxX >= minX && maxY >= minY)
    val left = (floor(minX.toDouble() * full.width / sw).toInt() - 2).coerceAtLeast(0)
    val top = (floor(minY.toDouble() * full.height / sh).toInt() - 2).coerceAtLeast(0)
    val right = (ceil((maxX + 1).toDouble() * full.width / sw).toInt() + 2).coerceAtMost(full.width)
    val bottom = (ceil((maxY + 1).toDouble() * full.height / sh).toInt() + 2).coerceAtMost(full.height)
    val w = right - left
    val h = bottom - top
    require(w in 3..SurfaceSpotRepair.MAX_SIDE && h in 3..SurfaceSpotRepair.MAX_SIDE) {
        "Selected spot is too large at original resolution. Choose a smaller isolated mark."
    }
    val patch = IntArray(w * h)
    full.getPixels(patch, 0, w, left, top, w, h)
    val mask = BooleanArray(w * h) { i ->
        val x = ((left + i % w).toLong() * sw / full.width).toInt().coerceIn(0, sw - 1)
        val y = ((top + i / w).toLong() * sh / full.height).toInt().coerceIn(0, sh - 1)
        selection.mask[y * sw + x]
    }
    val repaired = SurfaceSpotRepair.repair(patch, w, h, mask)
    full.setPixels(repaired, 0, w, left, top, w, h)
    return full
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfaceSpotRepairScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelRepository = remember { ModelRepository.getInstance(context) }
    LaunchedEffect(Unit) { modelRepository.ensureLoaded() }
    val installedLocalModel = modelRepository.models.firstOrNull {
        it.isDownloaded && it.backendType == "sd15cpu"
    }
    var source by remember { mutableStateOf<SpotSource?>(null) }
    var selection by remember { mutableStateOf<SurfaceSpotRepair.Selection?>(null) }
    var overlay by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<Bitmap?>(null) }
    var resultPreview by remember { mutableStateOf<Bitmap?>(null) }
    var tolerance by remember { mutableFloatStateOf(40f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val loaded = withContext(Dispatchers.IO) {
                        var w = 0
                        var h = 0
                        val preview = ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(context.contentResolver, uri),
                        ) { decoder, info, _ ->
                            w = info.size.width
                            h = info.size.height
                            require(w > 0 && h > 0 && w.toLong() * h <= MAX_SOURCE_PIXELS) {
                                "Image exceeds 16 MP editing limit."
                            }
                            val (pw, ph) = previewSize(w, h)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetSize(pw, ph)
                        }
                        SpotSource(uri, preview, w, h)
                    }
                    source = loaded
                    selection = null
                    overlay = null
                    result = null
                    resultPreview = null
                    message = "点击图片中的孤立小污点，预览自动选区。"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message ?: "Import failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun choose(s: SpotSource, x: Int, y: Int) {
        if (busy) return
        busy = true
        result = null
        resultPreview = null
        scope.launch {
            try {
                val pair = withContext(Dispatchers.Default) {
                    val selected = SurfaceSpotRepair.select(
                        pixels(s.preview), s.preview.width, s.preview.height,
                        x, y, tolerance.roundToInt(),
                    )
                    val mask = selected?.let {
                        Bitmap.createBitmap(
                            IntArray(it.mask.size) { i ->
                                if (it.mask[i]) 0x90ff3030.toInt() else 0
                            }, it.width, it.height, Bitmap.Config.ARGB_8888,
                        )
                    }
                    selected to mask
                }
                selection = pair.first
                overlay = pair.second
                message = if (pair.first != null) "已选中小污点，可修补或重新点击。"
                else "未找到独立小污点；可调整颜色容差后重新点击。"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                selection = null
                overlay = null
                message = e.message ?: "Selection failed"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("点击污点修补") }, navigationIcon = {
            IconButton(onClick = { navController.popBackStackIfResumed() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("点选小污点后可选快速修补，或用已下载的本地 SD1.5 生成式补全背景；原图不覆盖。",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { picker.launch(arrayOf("image/*")) }, enabled = !busy,
                modifier = Modifier.fillMaxWidth()) { Text("导入图片") }
            val s = source
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                if (s != null) {
                    val shown = resultPreview ?: s.preview
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Spot repair preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().pointerInput(s, busy, tolerance, resultPreview) {
                            detectTapGestures { point ->
                                if (!busy && resultPreview == null && size.width > 0 && size.height > 0) {
                                    val scale = minOf(size.width.toFloat() / s.preview.width,
                                        size.height.toFloat() / s.preview.height)
                                    val ox = (size.width - s.preview.width * scale) / 2
                                    val oy = (size.height - s.preview.height * scale) / 2
                                    val px = (point.x - ox) / scale
                                    val py = (point.y - oy) / scale
                                    if (px >= 0 && py >= 0 && px < s.preview.width && py < s.preview.height) {
                                        choose(s, px.toInt(), py.toInt())
                                    }
                                }
                            }
                        },
                    )
                    if (resultPreview == null) overlay?.let {
                        Image(it.asImageBitmap(), contentDescription = "Selected spot",
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                if (busy) CircularProgressIndicator()
            }
            if (s != null) {
                Text("颜色容差: " + tolerance.roundToInt())
                Slider(tolerance, { tolerance = it }, valueRange = 10f..65f, enabled = !busy)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        selection = null
                        overlay = null
                        result = null
                        resultPreview = null
                        message = "已清除选区，点击原图重新选择。"
                    }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("重新选择") }
                    Button(onClick = {
                        val chosen = selection ?: return@Button
                        busy = true
                        scope.launch {
                            try {
                                val repaired = withContext(Dispatchers.IO) {
                                    val full = ImageDecoder.decodeBitmap(
                                        ImageDecoder.createSource(context.contentResolver, s.uri),
                                    ) { decoder, info, _ ->
                                        require(info.size.width == s.width && info.size.height == s.height)
                                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                                        decoder.isMutableRequired = true
                                    }
                                    try {
                                        require(full.width == s.width && full.height == s.height)
                                        applySpot(full, chosen)
                                    } catch (e: Exception) {
                                        full.recycle()
                                        throw e
                                    }
                                }
                                val small = withContext(Dispatchers.Default) {
                                    Bitmap.createScaledBitmap(repaired, s.preview.width, s.preview.height, true)
                                }
                                result = repaired
                                resultPreview = small
                                message = "修补完成，保留原始分辨率，可另存为 PNG。"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = e.message ?: "Repair failed"
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy && selection != null && result == null,
                        modifier = Modifier.weight(1f)) { Text("修补选区") }
                }
                OutlinedButton(
                    onClick = {
                        val chosen = selection ?: return@OutlinedButton
                        val model = installedLocalModel ?: return@OutlinedButton
                        busy = true
                        scope.launch {
                            try {
                                val repaired = SceneBackgroundRepair.run(
                                    context = context,
                                    sourceUri = s.uri,
                                    preview = s.preview,
                                    sourceWidth = s.width,
                                    sourceHeight = s.height,
                                    selection = chosen,
                                    model = model,
                                )
                                val small = withContext(Dispatchers.Default) {
                                    Bitmap.createScaledBitmap(
                                        repaired, s.preview.width, s.preview.height, true,
                                    )
                                }
                                result = repaired
                                resultPreview = small
                                message = "本地生成式修补完成；请检查边缘，再决定是否导出。"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "生成式修补失败：" + (e.message ?: "未知错误")
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy && selection != null && result == null && installedLocalModel != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("生成式背景补全（本地 SD1.5 · 实验）")
                }
                if (installedLocalModel == null) {
                    Text(
                        "生成式模式需要先在模型页面安装 SD1.5 MNN 模型；传统修补仍可使用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = {
                    val bitmap = result ?: return@Button
                    busy = true
                    scope.launch {
                        try {
                            saveImage(context, bitmap,
                                onSuccess = {
                                    message = "已另存无损 PNG，原文件未修改。"
                                    result = null
                                    resultPreview = null
                                    selection = null
                                    overlay = null
                                    source = null
                                },
                                onError = { message = it },
                                formatOverride = ImageSaveFormat.PNG)
                        } finally {
                            busy = false
                        }
                    }
                }, enabled = !busy && result != null, modifier = Modifier.fillMaxWidth()) {
                    Text("另存原尺寸 PNG")
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
