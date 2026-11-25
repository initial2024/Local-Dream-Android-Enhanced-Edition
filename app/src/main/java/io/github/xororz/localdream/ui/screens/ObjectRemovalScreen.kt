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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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

private enum class LocalEditMode { REMOVE, OUTFIT, NUDE }

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
    var detectedRegion by remember { mutableStateOf<ObjectRemovalPolicy.ApproxBodyRegion?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showMaskEditor by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf(prefs.getString("removal_model", null)) }
    var editMode by remember {
        mutableStateOf(
            runCatching {
                LocalEditMode.valueOf(prefs.getString("local_edit_mode", LocalEditMode.REMOVE.name)!!)
            }.getOrDefault(LocalEditMode.REMOVE),
        )
    }
    fun denoiseKey(mode: LocalEditMode): String = when (mode) {
        LocalEditMode.OUTFIT -> "outfit_denoise"
        LocalEditMode.NUDE -> "nude_denoise"
        LocalEditMode.REMOVE -> "removal_denoise"
    }

    fun defaultDenoise(mode: LocalEditMode): Float = when (mode) {
        LocalEditMode.OUTFIT -> 0.72f
        LocalEditMode.NUDE -> 0.78f
        LocalEditMode.REMOVE -> 0.82f
    }

    fun promptKey(mode: LocalEditMode): String = when (mode) {
        LocalEditMode.OUTFIT -> "outfit_prompt"
        LocalEditMode.NUDE -> "nude_prompt"
        LocalEditMode.REMOVE -> "removal_prompt"
    }

    var denoise by remember {
        mutableFloatStateOf(
            prefs.getFloat(denoiseKey(editMode), defaultDenoise(editMode)),
        )
    }
    var useOpenCl by remember { mutableStateOf(prefs.getBoolean("removal_opencl", true)) }
    var enhancePatch by remember { mutableStateOf(prefs.getBoolean("removal_sr_refine", false)) }
    var replaceByDefault by remember { mutableStateOf(prefs.getBoolean("replace_source_default", false)) }
    fun defaultPrompt(mode: LocalEditMode): String = when (mode) {
        LocalEditMode.REMOVE ->
            "seamless natural background, continue the surrounding texture and lighting"
        LocalEditMode.OUTFIT ->
            "replace the masked clothing with a realistic well-fitted outfit, preserve pose, body shape, lighting and surrounding details"
        LocalEditMode.NUDE ->
            "completely nude, bare skin, natural skin texture, detailed body, soft lighting, seamless anatomy, no clothing, no fabric, nipples, realistic skin"
    }

    fun regionLabel(region: ObjectRemovalPolicy.ApproxBodyRegion): String = when (region) {
        ObjectRemovalPolicy.ApproxBodyRegion.HEAD_SHOULDERS -> "头肩区域"
        ObjectRemovalPolicy.ApproxBodyRegion.UPPER_BODY -> "上身区��"
        ObjectRemovalPolicy.ApproxBodyRegion.LOWER_BODY -> "下身区域"
        ObjectRemovalPolicy.ApproxBodyRegion.FULL_BODY -> "全身区域"
        ObjectRemovalPolicy.ApproxBodyRegion.OTHER -> "其他区域"
    }

    fun outfitRegionHint(region: ObjectRemovalPolicy.ApproxBodyRegion): String = when (region) {
        ObjectRemovalPolicy.ApproxBodyRegion.HEAD_SHOULDERS ->
            "selected head/shoulder garment or accessory area"
        ObjectRemovalPolicy.ApproxBodyRegion.UPPER_BODY ->
            "selected upper-body garment area"
        ObjectRemovalPolicy.ApproxBodyRegion.LOWER_BODY ->
            "selected lower-body garment area"
        ObjectRemovalPolicy.ApproxBodyRegion.FULL_BODY ->
            "selected full-outfit area"
        ObjectRemovalPolicy.ApproxBodyRegion.OTHER ->
            "selected garment area"
    }

    fun nudeRegionHint(region: ObjectRemovalPolicy.ApproxBodyRegion): String = when (region) {
        ObjectRemovalPolicy.ApproxBodyRegion.HEAD_SHOULDERS ->
            "bare neck and shoulders, natural skin, no clothing"
        ObjectRemovalPolicy.ApproxBodyRegion.UPPER_BODY ->
            "bare breasts and torso, natural skin, nipples, no clothing"
        ObjectRemovalPolicy.ApproxBodyRegion.LOWER_BODY ->
            "bare lower body, natural skin, detailed anatomy, no underwear"
        ObjectRemovalPolicy.ApproxBodyRegion.FULL_BODY ->
            "completely nude full body, natural skin texture, detailed anatomy, no clothing"
        ObjectRemovalPolicy.ApproxBodyRegion.OTHER ->
            "bare skin, natural texture, no clothing"
    }

    var prompt by remember {
        mutableStateOf(
            prefs.getString(promptKey(editMode), defaultPrompt(editMode)) ?: defaultPrompt(editMode),
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
        detectedRegion = null
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
                detectedRegion = objectRemovalMaskBounds(mask)?.let { bounds ->
                    ObjectRemovalPolicy.classifyApproxBodyRegion(
                        bounds,
                        mask.width,
                        mask.height,
                    )
                }
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

    fun selectMode(mode: LocalEditMode) {
        if (editMode == mode) return
        editMode = mode
        prefs.edit { putString("local_edit_mode", mode.name) }
        denoise = prefs.getFloat(denoiseKey(mode), defaultDenoise(mode))
        prompt = prefs.getString(promptKey(mode), defaultPrompt(mode)) ?: defaultPrompt(mode)
        // A mask often targets a different semantic region after changing mode.
        maskBitmap?.takeIf { !it.isRecycled }?.recycle()
        maskBitmap = null
        detectedRegion = null
        resultPreview?.takeIf { !it.isRecycled }?.recycle()
        resultPreview = null
        resultBitmap?.takeIf { !it.isRecycled }?.recycle()
        resultBitmap = null
    }

    fun runRemoval() {
        val source = imported ?: return
        val mask = maskBitmap ?: return
        val model = selectedModel ?: return
        if (editMode == LocalEditMode.OUTFIT && prompt.isBlank()) {
            errorMessage = "请先描述要替换成什么衣服"
            return
        }
        if (editMode == LocalEditMode.NUDE && prompt.isBlank()) {
            errorMessage = "请先填写去衣描述，或使用下方预设"
            return
        }
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
                val region = ObjectRemovalPolicy.classifyApproxBodyRegion(
                    previewMaskBox,
                    mask.width,
                    mask.height,
                )
                detectedRegion = region
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
                val effectivePrompt = when (editMode) {
                    LocalEditMode.OUTFIT ->
                        prompt + ", " + outfitRegionHint(region) +
                            ", preserve the same person, face, pose, body proportions, background and lighting"
                    LocalEditMode.NUDE ->
                        prompt + ", " + nudeRegionHint(region) +
                            ", preserve the same person, face, pose, body proportions, background and lighting, no clothing"
                    LocalEditMode.REMOVE -> prompt
                }
                generated = ObjectRemovalEngine.run(
                    context = context,
                    model = model,
                    source = workSource!!,
                    mask = workMask!!,
                    prompt = effectivePrompt,
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
                title = { Text("AI 消除 / 换衣 / 去衣") },
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
                when (editMode) {
                    LocalEditMode.OUTFIT -> "本地换衣 · 只重绘圈选衣物区域 · 不上传云端"
                    LocalEditMode.NUDE -> "本地去衣 · 圈选衣物后重绘为自然皮肤 · 不上传云端"
                    LocalEditMode.REMOVE -> "本地离线处理 · 只重绘圈选区域 · 图片不会上传到云端"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { picker.launch(arrayOf("image/*")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = null)
                Text(if (imported == null) " 导入手机图片" else " 更换图片")
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    LocalEditMode.REMOVE to "AI 消除",
                    LocalEditMode.OUTFIT to "换衣服",
                    LocalEditMode.NUDE to "去衣",
                ).forEach { (mode, label) ->
                    if (editMode == mode) {
                        Button(
                            onClick = { selectMode(mode) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    } else {
                        FilledTonalButton(
                            onClick = { selectMode(mode) },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
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
                                when (editMode) {
                                    LocalEditMode.OUTFIT -> " 圈出要替换的衣物"
                                    LocalEditMode.NUDE -> " 圈出要去掉的衣物"
                                    LocalEditMode.REMOVE -> " 圈出要消除的部分"
                                }
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
                            when (editMode) {
                                LocalEditMode.OUTFIT -> " 开始换衣"
                                LocalEditMode.NUDE -> " 开始去衣"
                                LocalEditMode.REMOVE -> " 开始 AI 消除"
                            },
                        )
                    }
                }

                detectedRegion?.let { region ->
                    Text(
                        "自动识别区域：" + regionLabel(region) + "（按圈选位置粗略估计）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

                OutlinedTextField(
                    value = prompt,
                    onValueChange = {
                        prompt = it
                        prefs.edit { putString(promptKey(editMode), it) }
                    },
                    label = {
                        Text(
                            when (editMode) {
                                LocalEditMode.OUTFIT -> "新衣服描述，例如：黑色西装、白衬衫"
                                LocalEditMode.NUDE -> "去衣描述（默认即可，也可改细节）"
                                LocalEditMode.REMOVE -> "修复提示（可留默认）"
                            },
                        )
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                if (editMode == LocalEditMode.OUTFIT) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "black tailored suit" to "黑色西装",
                                "white casual shirt" to "白衬衫",
                                "denim jacket" to "牛仔外套",
                            ).forEach { (preset, label) ->
                                OutlinedButton(
                                    onClick = {
                                        prompt = preset
                                        prefs.edit { putString("outfit_prompt", preset) }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "sheer black lingerie, lace details" to "黑色内衣",
                                "micro bikini, high cut" to "比基尼",
                                "wet white shirt, see-through" to "湿白衬衫",
                            ).forEach { (preset, label) ->
                                OutlinedButton(
                                    onClick = {
                                        prompt = preset
                                        prefs.edit { putString("outfit_prompt", preset) }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }
                }
                if (editMode == LocalEditMode.NUDE) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "completely nude, bare skin, natural skin texture, soft lighting, nipples" to "全裸",
                                "bare breasts and torso, natural skin, nipples, no clothing" to "上身",
                                "bare lower body, natural skin, detailed anatomy, no underwear" to "下身",
                            ).forEach { (preset, label) ->
                                OutlinedButton(
                                    onClick = {
                                        prompt = preset
                                        prefs.edit { putString("nude_prompt", preset) }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "completely nude, wet skin, water droplets, soft light" to "湿身",
                                "completely nude, from behind, bare back and hips" to "背面",
                                "completely nude, lying down, soft bed lighting" to "躺姿",
                            ).forEach { (preset, label) ->
                                OutlinedButton(
                                    onClick = {
                                        prompt = preset
                                        prefs.edit { putString("nude_prompt", preset) }
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                ) { Text(label) }
                            }
                        }
                    }
                }
                Text(
                    when (editMode) {
                        LocalEditMode.OUTFIT -> "衣物重绘强度 "
                        LocalEditMode.NUDE -> "去衣重绘强度 "
                        LocalEditMode.REMOVE -> "修复强度 "
                    } + (denoise * 100).roundToInt() + "%",
                )
                Slider(
                    value = denoise,
                    onValueChange = {
                        denoise = it
                        prefs.edit { putFloat(denoiseKey(editMode), it) }
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
