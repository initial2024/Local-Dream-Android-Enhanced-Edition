package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.navigation.NavController
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.data.ObjectRemovalPolicy
import io.github.xororz.localdream.data.UpscalerRepository
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.service.MnnBackendHandoff
import io.github.xororz.localdream.service.ObjectRemovalEngine
import io.github.xororz.localdream.utils.ImageSaveFormat
import io.github.xororz.localdream.utils.ObjectRemovalImportedImage
import io.github.xororz.localdream.utils.buildObjectRemovalWorkMask
import io.github.xororz.localdream.utils.cleanupObjectRemovalInternalFiles
import io.github.xororz.localdream.utils.compositeObjectRemovalPatch
import io.github.xororz.localdream.utils.decodeObjectRemovalRegion
import io.github.xororz.localdream.utils.loadObjectRemovalPreview
import io.github.xororz.localdream.utils.objectRemovalMaskBounds
import io.github.xororz.localdream.utils.performUpscale
import io.github.xororz.localdream.utils.replaceImportedImageSafely
import io.github.xororz.localdream.utils.saveImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class LocalEditMode {
    REMOVE_OBJECT,
    CHANGE_OUTFIT,
}

@Composable
fun ObjectRemovalScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences("object_removal_prefs", Context.MODE_PRIVATE)
    }
    val modelRepository = remember { ModelRepository.getInstance(context) }
    val upscalerRepository = remember { UpscalerRepository.getInstance(context) }
    val generationState by BackgroundGenerationService.generationState.collectAsState()

    var imported by remember { mutableStateOf<ObjectRemovalImportedImage?>(null) }
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showMaskEditor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf(prefs.getString("removal_model", null)) }
    var denoise by remember { mutableFloatStateOf(prefs.getFloat("removal_denoise", 0.82f)) }
    var useOpenCl by remember { mutableStateOf(prefs.getBoolean("removal_opencl", true)) }
    var enhancePatch by remember { mutableStateOf(prefs.getBoolean("removal_sr_refine", false)) }
    var replaceByDefault by remember { mutableStateOf(prefs.getBoolean("replace_source_default", false)) }
    var editMode by remember {
        mutableStateOf(
            runCatching {
                LocalEditMode.valueOf(
                    prefs.getString("local_edit_mode", LocalEditMode.REMOVE_OBJECT.name)
                        ?: LocalEditMode.REMOVE_OBJECT.name,
                )
            }.getOrDefault(LocalEditMode.REMOVE_OBJECT),
        )
    }
    var prompt by remember {
        mutableStateOf(
            prefs.getString(
                "removal_prompt",
                "seamless natural background, continue the surrounding texture and lighting",
            ) ?: "",
        )
    }
    var outfitPrompt by remember {
        mutableStateOf(
            prefs.getString(
                "outfit_prompt",
                "a well-fitted casual jacket, realistic fabric, natural folds, matching lighting",
            ) ?: "",
        )
    }

    LaunchedEffect(Unit) {
        modelRepository.ensureLoaded()
        upscalerRepository.ensureLoaded()
    }
    val localModels = modelRepository.models.filter {
        it.isDownloaded && it.backendType == "sd15cpu"
    }
    val selectedModel = localModels.firstOrNull { it.id == selectedModelId } ?: localModels.firstOrNull()
    LaunchedEffect(selectedModel?.id) {
        if (selectedModel != null && selectedModelId != selectedModel.id) {
            selectedModelId = selectedModel.id
            prefs.edit { putString("removal_model", selectedModel.id) }
        }
    }
    val srPhoto = upscalerRepository.upscalers.firstOrNull {
        it.id == "upscaler_mnn_photo" && it.isDownloaded
    }

    fun clearSession(recycleResult: Boolean = true) {
        maskBitmap?.takeIf { !it.isRecycled }?.recycle()
        maskBitmap = null
        imported?.preview?.takeIf { !it.isRecycled }?.recycle()
        imported = null
        resultPreview?.takeIf { !it.isRecycled }?.recycle()
        resultPreview = null
        if (recycleResult) resultBitmap?.takeIf { !it.isRecycled }?.recycle()
        resultBitmap = null
        cleanupObjectRemovalInternalFiles(context)
    }

    DisposableEffect(Unit) {
        onDispose {
            BackgroundGenerationService.stop(context)
            cleanupObjectRemovalInternalFiles(context)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        scope.launch {
            try {
                busy = true
                errorMessage = null
                clearSession()
                imported = loadObjectRemovalPreview(context, uri)
                showMaskEditor = true
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                busy = false
            }
        }
    }

    if (showMaskEditor && imported != null) {
        InpaintScreen(
            originalBitmap = imported!!.preview,
            existingMaskBitmap = maskBitmap,
            existingPathHistory = null,
            onInpaintComplete = { _, _, mask, _ ->
                maskBitmap?.takeIf { it !== mask && !it.isRecycled }?.recycle()
                maskBitmap = mask
                showMaskEditor = false
            },
            onCancel = { showMaskEditor = false },
        )
        return
    }

    fun previewOf(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= ObjectRemovalPolicy.MAX_PREVIEW_SIDE) {
            return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val scale = ObjectRemovalPolicy.MAX_PREVIEW_SIDE.toFloat() / maxSide
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt(),
            (bitmap.height * scale).roundToInt(),
            true,
        )
    }

    fun runRemoval() {
        val source = imported ?: return
        val mask = maskBitmap ?: return
        val model = selectedModel ?: return
        if (busy) return
        scope.launch {
            var workSource: Bitmap? = null
            var workMask: Bitmap? = null
            var generated: Bitmap? = null
            var refined: Bitmap? = null
            try {
                busy = true
                errorMessage = null
                val previewMaskBox = withContext(Dispatchers.Default) {
                    objectRemovalMaskBounds(mask)
                } ?: throw IllegalStateException("Paint the area you want to remove first")
                val sourceBox = ObjectRemovalPolicy.paddedSourceBox(
                    previewMaskBox,
                    mask.width,
                    mask.height,
                    source.sourceWidth,
                    source.sourceHeight,
                )
                val (workWidth, workHeight) = ObjectRemovalPolicy.workSize(sourceBox)
                workSource = decodeObjectRemovalRegion(
                    context, source.uri, sourceBox, workWidth, workHeight,
                )
                workMask = withContext(Dispatchers.Default) {
                    buildObjectRemovalWorkMask(
                        mask, sourceBox, source.sourceWidth, source.sourceHeight,
                        workWidth, workHeight,
                    )
                }
                val operationPrompt = when (editMode) {
                    LocalEditMode.REMOVE_OBJECT -> prompt
                    LocalEditMode.CHANGE_OUTFIT ->
                        "replace only the selected clothing with " + outfitPrompt +
                            ", preserve the same person, face, body proportions, pose, background, " +
                            "camera angle and lighting; realistic garment boundaries"
                }
                generated = ObjectRemovalEngine.run(
                    context = context,
                    model = model,
                    source = workSource!!,
                    mask = workMask!!,
                    prompt = operationPrompt,
                    denoiseStrength = denoise,
                    useOpenCl = useOpenCl,
                )
                val patchForComposite = if (enhancePatch && srPhoto != null) {
                    refined = MnnBackendHandoff.withStandalone(context) {
                        performUpscale(
                            context = context,
                            bitmap = generated!!,
                            upscalerId = srPhoto.id,
                            targetScale = 2,
                        )
                    }
                    refined!!
                } else generated!!
                val final = compositeObjectRemovalPatch(
                    context = context,
                    imported = source,
                    sourceBox = sourceBox,
                    previewMask = mask,
                    generatedPatch = patchForComposite,
                )
                resultBitmap?.takeIf { !it.isRecycled }?.recycle()
                resultPreview?.takeIf { !it.isRecycled }?.recycle()
                resultBitmap = final
                resultPreview = withContext(Dispatchers.Default) { previewOf(final) }
            } catch (e: Exception) {
                errorMessage = e.message ?: "AI removal failed"
            } finally {
                workSource?.takeIf { !it.isRecycled }?.recycle()
                workMask?.takeIf { !it.isRecycled }?.recycle()
                generated?.takeIf { it !== resultBitmap && it !== refined && !it.isRecycled }?.recycle()
                refined?.takeIf { it !== resultBitmap && !it.isRecycled }?.recycle()
                busy = false
            }
        }
    }

    fun exportResult(forceReplace: Boolean? = null) {
        val bitmap = resultBitmap ?: return
        val source = imported ?: return
        val replace = forceReplace ?: (replaceByDefault && source.canReplace)
        scope.launch {
            try {
                busy = true
                if (replace) {
                    check(replaceImportedImageSafely(context, source, bitmap)) {
                        "This source provider cannot be replaced in its original format"
                    }
                    Toast.makeText(context, "已安全替换导入原图", Toast.LENGTH_SHORT).show()
                    cleanupObjectRemovalInternalFiles(context)
                } else {
                    saveImage(
                        context = context,
                        bitmap = bitmap,
                        formatOverride = ImageSaveFormat.PNG,
                        onSuccess = {
                            Toast.makeText(context, "已保存新的无损 PNG", Toast.LENGTH_SHORT).show()
                            cleanupObjectRemovalInternalFiles(context)
                        },
                        onError = { message -> errorMessage = message },
                    )
                }
            } catch (e: Exception) {
                errorMessage = e.message
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 消除") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "本地离线处理 · 只重绘圈选区域 · 图片不会上传到云端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        editMode = LocalEditMode.REMOVE_OBJECT
                        prefs.edit { putString("local_edit_mode", editMode.name) }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (editMode == LocalEditMode.REMOVE_OBJECT) "✓ AI 消除" else "AI 消除")
                }
                OutlinedButton(
                    onClick = {
                        editMode = LocalEditMode.CHANGE_OUTFIT
                        prefs.edit { putString("local_edit_mode", editMode.name) }
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (editMode == LocalEditMode.CHANGE_OUTFIT) "✓ 换衣服" else "换衣服")
                }
            }
            Button(
                onClick = { picker.launch(arrayOf("image/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Text(if (imported == null) " 导入手机图片" else " 更换图片")
            }

            val shown = resultPreview ?: imported?.preview
            if (shown != null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    if (resultPreview == null && maskBitmap != null) {
                        Image(
                            bitmap = maskBitmap!!.asImageBitmap(),
                            contentDescription = "Mask",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alpha = 0.38f,
                        )
                    }
                    if (busy) CircularProgressIndicator()
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (imported != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showMaskEditor = true },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Brush, contentDescription = null)
                        Text(
                            if (maskBitmap == null) {
                                if (editMode == LocalEditMode.CHANGE_OUTFIT) " 圈出要替换的衣物" else " 圈出要消除的部分"
                            } else {
                                " 修改圈选"
                            },
                        )
                    }
                    Button(
                        onClick = { runRemoval() },
                        enabled = !busy && maskBitmap != null && selectedModel != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null)
                        Text(
                            if (editMode == LocalEditMode.CHANGE_OUTFIT) " 开始换衣" else " 开始 AI 消除",
                        )
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { modelMenuExpanded = true },
                        enabled = !busy && localModels.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedModel?.let { "修复模型：" + it.name } ?: "需要先下载本地 SD1.5 MNN 模型")
                    }
                    DropdownMenu(
                        expanded = modelMenuExpanded,
                        onDismissRequest = { modelMenuExpanded = false },
                    ) {
                        localModels.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    selectedModelId = item.id
                                    prefs.edit { putString("removal_model", item.id) }
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                if (editMode == LocalEditMode.REMOVE_OBJECT) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = {
                            prompt = it
                            prefs.edit { putString("removal_prompt", it) }
                        },
                        label = { Text("背景修复提示（可留默认）") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                    )
                } else {
                    OutlinedTextField(
                        value = outfitPrompt,
                        onValueChange = {
                            outfitPrompt = it
                            prefs.edit { putString("outfit_prompt", it) }
                        },
                        label = { Text("想换成什么衣服") },
                        placeholder = { Text("例如：黑色西装、白色连衣裙、运动夹克") },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 2,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "black tailored suit",
                            "white summer dress",
                            "casual denim jacket",
                        ).forEach { preset ->
                            OutlinedButton(
                                onClick = {
                                    outfitPrompt = preset
                                    prefs.edit { putString("outfit_prompt", preset) }
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    when (preset) {
                                        "black tailored suit" -> "西装"
                                        "white summer dress" -> "连衣裙"
                                        else -> "牛仔外套"
                                    },
                                )
                            }
                        }
                    }
                    Text(
                        "换衣只修改你圈选的衣物区域；脸、姿势、身体比例和背景尽量保持不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("修复强度 " + (denoise * 100).roundToInt() + "%")
                Slider(
                    value = denoise,
                    onValueChange = {
                        denoise = it
                        prefs.edit { putFloat("removal_denoise", it) }
                    },
                    valueRange = 0.5f..1f,
                    enabled = !busy,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("OpenCL 加速", modifier = Modifier.weight(1f))
                    Switch(
                        checked = useOpenCl,
                        onCheckedChange = {
                            useOpenCl = it
                            prefs.edit { putBoolean("removal_opencl", it) }
                        },
                        enabled = !busy,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AI 超分增强修复区域（2×）")
                        if (srPhoto == null) {
                            Text(
                                "需先在 AI 超分页面下载照片模型",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Switch(
                        checked = enhancePatch && srPhoto != null,
                        onCheckedChange = {
                            enhancePatch = it
                            prefs.edit { putBoolean("removal_sr_refine", it) }
                        },
                        enabled = !busy && srPhoto != null,
                    )
                }
                if (srPhoto == null && BuildConfig.MNN_ONLY_NATIVE) {
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.Upscale.route) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("打开 AI 超分并下载照片模型") }
                }
            }

            if (resultBitmap != null && imported != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = replaceByDefault,
                        onCheckedChange = {
                            replaceByDefault = it
                            prefs.edit { putBoolean("replace_source_default", it) }
                        },
                        enabled = imported!!.canReplace,
                    )
                    Column {
                        Text("默认导出时替换导入原图")
                        Text(
                            if (imported!!.canReplace) {
                                "关闭则保存新的无损 PNG；内部临时文件导出后自动清理"
                            } else {
                                "当前来源不可写，将自动保存为新图"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Button(
                    onClick = { exportResult() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (replaceByDefault && imported!!.canReplace) {
                            "按默认设置导出：替换原图"
                        } else {
                            "按默认设置导出：保存新 PNG"
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { exportResult(false) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("另存新图") }
                    OutlinedButton(
                        onClick = { exportResult(true) },
                        enabled = !busy && imported!!.canReplace,
                        modifier = Modifier.weight(1f),
                    ) { Text("替换原图") }
                }
            }

            if (generationState is BackgroundGenerationService.GenerationState.Progress) {
                val p = (generationState as BackgroundGenerationService.GenerationState.Progress).progress
                Text("AI 修复处理中 " + (p * 100).roundToInt() + "%")
            }
            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
