package io.github.xororz.localdream.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.ui.screens.GenerationParameters
import io.github.xororz.localdream.utils.schedulerDisplayName

@Composable
fun GenerationParamsDialog(
    title: String,
    params: GenerationParameters,
    modelId: String,
    displayMode: GenerationMode? = null,
    showImg2imgButton: Boolean,
    showShareButton: Boolean = true,
    showReproduceButton: Boolean = true,
    onShare: () -> Unit,
    onSendToImg2img: () -> Unit,
    onReproduce: () -> Unit,
    onQuickReplay: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f))
                if (showShareButton) {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share),
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // A primary, visible action on the FIRST screen of the
                // parameter sheet, not behind another "restore" dialog.
                // Only text-to-image carries enough stored data for replay.
                if (onQuickReplay != null &&
                    (displayMode ?: params.mode) == GenerationMode.TXT2IMG
                ) {
                    Button(
                        onClick = onQuickReplay,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.enhanced_reproduce_generate_now))
                    }
                }
                Column {
                    Text(
                        stringResource(R.string.basic_params),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.basic_model, modelId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.basic_step, params.steps),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "CFG: %.1f".format(params.cfg),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.basic_size, params.width, params.height),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    params.seed?.let {
                        Text(
                            stringResource(R.string.basic_seed, it),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        stringResource(
                            R.string.basic_runtime,
                            if (params.runOnCpu) {
                                if (params.useOpenCL) "GPU" else "CPU"
                            } else {
                                "NPU"
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "${stringResource(R.string.scheduler)}: ${schedulerDisplayName(params.scheduler)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val mode = displayMode ?: params.mode
                    if (mode != GenerationMode.UNKNOWN) {
                        Text(
                            stringResource(R.string.basic_mode, mode.name.lowercase()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (mode == GenerationMode.IMG2IMG ||
                            mode == GenerationMode.INPAINT ||
                            mode == GenerationMode.ULTRAFIX
                        ) {
                            Text(
                                stringResource(R.string.basic_denoise, params.denoiseStrength),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.basic_time, params.generationTime ?: "unknown"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Column {
                    Text(
                        stringResource(R.string.image_prompt),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(params.prompt, style = MaterialTheme.typography.bodyMedium)
                }

                Column {
                    Text(
                        stringResource(R.string.negative_prompt),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(params.negativePrompt, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showImg2imgButton) {
                    TextButton(onClick = onSendToImg2img) {
                        Text("img2img")
                    }
                } else {
                    Spacer(modifier = Modifier.width(0.dp))
                }
                Row {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close))
                    }
                    if (showReproduceButton) {
                        TextButton(onClick = onReproduce) {
                            Text(stringResource(R.string.reproduce))
                        }
                    }
                }
            }
        },
    )
}
