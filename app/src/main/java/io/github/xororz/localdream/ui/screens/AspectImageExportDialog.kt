package io.github.xororz.localdream.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.utils.HighDefinitionOutput
import io.github.xororz.localdream.utils.ImageSaveFormat

/** One export contract for fresh images and history, independent of model availability. */
@Composable
internal fun AspectImageExportDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
    onSave: (Int, ImageSaveFormat) -> Unit,
) {
    // Default to larger output pixel dimensions if the low-memory budget allows.
    // This is interpolation, not AI neural super-resolution.
    var exportPercent by remember(bitmap) {
        mutableStateOf(
            listOf(200, 150, 100).first { percent ->
                runCatching {
                    HighDefinitionOutput.aspectExportDimensions(
                        bitmap.width, bitmap.height, percent,
                    )
                }.isSuccess
            },
        )
    }
    var exportFormat by remember(bitmap) { mutableStateOf(ImageSaveFormat.PNG) }
    val dimensions = listOf(100, 150, 200).associateWith { percent ->
        runCatching {
            HighDefinitionOutput.aspectExportDimensions(bitmap.width, bitmap.height, percent)
        }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enhanced_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(
                    R.string.enhanced_export_original_size, bitmap.width, bitmap.height,
                ))
                dimensions.forEach { (percent, target) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = exportPercent == percent,
                            onClick = { exportPercent = percent },
                            enabled = target != null,
                        )
                        Text(
                            if (target != null) {
                                stringResource(
                                    R.string.enhanced_export_scale,
                                    percent,
                                    target.first,
                                    target.second,
                                )
                            } else {
                                stringResource(R.string.enhanced_export_too_large, percent)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = exportFormat == ImageSaveFormat.PNG,
                        onClick = { exportFormat = ImageSaveFormat.PNG },
                    )
                    Text(stringResource(R.string.enhanced_export_png))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = exportFormat == ImageSaveFormat.JPEG_HIGH,
                        onClick = { exportFormat = ImageSaveFormat.JPEG_HIGH },
                    )
                    Text(stringResource(R.string.enhanced_export_jpeg))
                }
                Text(
                    stringResource(R.string.enhanced_export_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = dimensions[exportPercent] != null,
                onClick = { onSave(exportPercent, exportFormat) },
            ) { Text(stringResource(R.string.enhanced_export_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
