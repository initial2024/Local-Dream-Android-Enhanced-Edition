#!/usr/bin/env python3
"""Fail-closed source overlay for upstream xororz/local-dream v3.0.0-alpha.7.

Pins expected code anchors. Stops when the upstream source changes unexpectedly.
"""
from __future__ import annotations
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(relative: str, old: str, new: str):
    path = ROOT / relative
    data = path.read_text(encoding="utf-8")
    if new in data:
        if old in data:
            raise RuntimeError(f"ambiguous overlay: {relative}")
        return
    if data.count(old) != 1:
        raise RuntimeError(f"{relative}: source anchor count {data.count(old)} != 1")
    path.write_text(data.replace(old, new, 1), encoding="utf-8")


def create_new(relative: str, source: str):
    path = ROOT / relative
    if path.exists():
        if path.read_text(encoding="utf-8") != source:
            raise RuntimeError(f"source differs from managed overlay: {relative}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


def patch():
    gradle = "app/build.gradle.kts"
    replace_once(gradle, 'applicationId = "io.github.xororz.localdream"',
                 'applicationId = "io.github.initial2024.localdream.enhanced"')
    replace_once(gradle, 'versionCode = 74\n        versionName = "3.0.0-alpha.7"',
                 'versionCode = 1\n        versionName = "0.1.0-alpha.1"')
    replace_once(gradle, 'LocalDream_armv8a_$versionName.apk',
                 'LocalDreamEnhanced_arm64_$versionName.apk')

    dit = "app/src/main/java/io/github/xororz/localdream/data/DitResolution.kt"
    replace_once(dit,
        "fun snap(value: Float): Int = ((value / SIZE_STEP).roundToInt() * SIZE_STEP)\n        .coerceIn(MIN_SIZE, MAX_SIZE)",
        """// HD 4:3 preset uses 16-aligned width between normal slider ticks.
    // Preserve it on saved-parameter restoration.
    fun snap(value: Float): Int = if (value.roundToInt() == 1440) {
        1440
    } else {
        ((value / SIZE_STEP).roundToInt() * SIZE_STEP).coerceIn(MIN_SIZE, MAX_SIZE)
    }""")
    replace_once(dit, "(value - MIN_SIZE) % SIZE_STEP == 0",
                 "((value - MIN_SIZE) % SIZE_STEP == 0 || value == 1440)")

    adv = "app/src/main/java/io/github/xororz/localdream/ui/screens/AdvancedSettingsDialog.kt"
    replace_once(adv, "    onDitHeightChange: (Float) -> Unit = {},\n    onSchedulerChange:",
                 "    onDitHeightChange: (Float) -> Unit = {},\n    onDitHdPreset: () -> Unit = {},\n    onSchedulerChange:")
    replace_once(adv,
        "                if (isDit) {\n                    Column(modifier = Modifier.fillMaxWidth()) {\n",
        """                if (isDit) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDitHdPreset, enabled = !isRunning) {
                            Text(stringResource(R.string.enhanced_hd_preset))
                        }
                        Text(
                            stringResource(R.string.enhanced_hd_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
""")

    screen = "app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt"
    replace_once(screen,
        "import io.github.xororz.localdream.utils.ImportedParams\n",
        "import io.github.xororz.localdream.utils.HighDefinitionOutput\nimport io.github.xororz.localdream.utils.ImportedParams\n")
    replace_once(screen,
        """                                    onDitHeightChange = onDitHeightChange,
                                    runOnCpu = model?.runOnCpu ?: false,""",
        """                                    onDitHeightChange = onDitHeightChange,
                                    onDitHdPreset = {
                                        if (!isRunning) {
                                            currentWidth = 1440
                                            currentHeight = 1088
                                            aspectRatio = "4:3"
                                            saveAllFields()
                                        }
                                    },
                                    runOnCpu = model?.runOnCpu ?: false,""")
    replace_once(screen,
        """                            onSaveClick = { bitmap ->
                                handleSaveImage(""",
        """                            onSaveHdClick = { bitmap ->
                                // Explicit crop: ordinary Save/history keep native 1440x1088.
                                val cropped = HighDefinitionOutput.cropTo1440x1080(bitmap)
                                handleSaveImage(
                                    context = context,
                                    bitmap = cropped,
                                    onSuccess = {
                                        Toast.makeText(context, msgImageSaved, Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { error ->
                                        Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                                    },
                                )
                            },
                            onSaveClick = { bitmap ->
                                handleSaveImage(""")

    page = "app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunPages.kt"
    replace_once(page, "import androidx.compose.material3.Text\n",
                 "import androidx.compose.material3.Text\nimport androidx.compose.material3.TextButton\n")
    replace_once(page, "import io.github.xororz.localdream.ui.theme.Motion\n",
                 "import io.github.xororz.localdream.ui.theme.Motion\nimport io.github.xororz.localdream.utils.HighDefinitionOutput\n")
    replace_once(page, "    onSaveClick: (Bitmap) -> Unit,\n    onPreviewClick:",
                 "    onSaveClick: (Bitmap) -> Unit,\n    onSaveHdClick: (Bitmap) -> Unit,\n    onPreviewClick:")
    replace_once(page,
        """                        if (recentHistory.size > 1) {
""",
        """                        if (currentBitmap != null &&
                            HighDefinitionOutput.isHdCanvas(currentBitmap.width, currentBitmap.height)
                        ) {
                            TextButton(onClick = { onSaveHdClick(currentBitmap) }) {
                                Text(stringResource(R.string.enhanced_hd_export))
                            }
                        }

                        if (recentHistory.size > 1) {
""")

    strings = {
        "app/src/main/res/values/strings.xml": (
            "HD 4:3 preset (1440×1080 export)",
            "Inference uses 1440×1088 and may require much more RAM. 1440×1080 is a separate center-cropped export, not a native diffusion size.",
            "Save 1440×1080 (center crop)"
        ),
        "app/src/main/res/values-zh/strings.xml": (
            "高清 4:3 预设（导出 1440×1080）",
            "实际推理尺寸为 1440×1088，可能消耗更多内存。1440×1080 是单独居中裁剪导出，非原生推理尺寸。",
            "保存 1440×1080（居中裁剪）"
        ),
    }
    for relative, v in strings.items():
        path = ROOT / relative
        s = path.read_text(encoding="utf-8")
        block = (f'    <string name="enhanced_hd_preset">{v[0]}</string>\n'
                 f'    <string name="enhanced_hd_warning">{v[1]}</string>\n'
                 f'    <string name="enhanced_hd_export">{v[2]}</string>\n')
        if 'name="enhanced_hd_preset"' not in s:
            if s.count("</resources>") != 1:
                raise RuntimeError(f"invalid resources anchor: {relative}")
            path.write_text(s.replace("</resources>", block + "</resources>"), encoding="utf-8")

    create_new("app/src/main/java/io/github/xororz/localdream/utils/HighDefinitionOutput.kt",
"""package io.github.xororz.localdream.utils

import android.graphics.Bitmap

/** Source canvas and export target are deliberately distinct. */
object HighDefinitionOutput {
    const val CANVAS_WIDTH = 1440
    const val CANVAS_HEIGHT = 1088
    const val EXPORT_WIDTH = 1440
    const val EXPORT_HEIGHT = 1080
    private const val TOP_CROP = (CANVAS_HEIGHT - EXPORT_HEIGHT) / 2

    fun isHdCanvas(width: Int, height: Int): Boolean =
        width == CANVAS_WIDTH && height == CANVAS_HEIGHT

    /** Called only by the explicit HD export action; original pixels remain unchanged. */
    fun cropTo1440x1080(bitmap: Bitmap): Bitmap {
        require(isHdCanvas(bitmap.width, bitmap.height)) {
            "HD export requires a 1440x1088 image"
        }
        return Bitmap.createBitmap(bitmap, 0, TOP_CROP, EXPORT_WIDTH, EXPORT_HEIGHT)
    }
}
""")
    create_new("app/src/test/java/io/github/xororz/localdream/data/DitResolutionEnhancedTest.kt",
"""package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DitResolutionEnhancedTest {
    @Test fun retainsHdPresetAcrossRestart() {
        assertEquals(1440, DitResolution.snap(1440f))
        assertTrue(DitResolution.isSupported(1440))
        assertTrue(DitResolution.isSupported(1088))
    }

    @Test fun rejectsUnsupportedNative1080Height() {
        assertFalse(DitResolution.isSupported(1080))
        assertEquals(1088, DitResolution.snap(1080f))
    }

    @Test fun keepsOriginalSliderBehavior() {
        assertEquals(512, DitResolution.snap(500f))
        assertEquals(1024, DitResolution.snap(1024f))
        assertEquals(2048, DitResolution.snap(2050f))
    }
}
""")
    print("Overlay applied. New app ID; HD preset/export; two localized strings; unit tests.")


def check():
    contracts = {
        "app/build.gradle.kts": ["io.github.initial2024.localdream.enhanced", "0.1.0-alpha."],
        "app/src/main/java/io/github/xororz/localdream/data/DitResolution.kt": ["value == 1440"],
        "app/src/main/java/io/github/xororz/localdream/ui/screens/AdvancedSettingsDialog.kt": ["onDitHdPreset:"],
        "app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunScreen.kt": ["onSaveHdClick =", "onDitHdPreset ="],
        "app/src/main/java/io/github/xororz/localdream/ui/screens/ModelRunPages.kt": ["onSaveHdClick:"],
        "app/src/main/java/io/github/xororz/localdream/utils/HighDefinitionOutput.kt": ["cropTo1440x1080"],
    }
    for relative, needles in contracts.items():
        content = (ROOT / relative).read_text(encoding="utf-8")
        for needle in needles:
            if needle not in content:
                raise RuntimeError(f"{relative}: missing {needle}")
    print("Static overlay contract PASS (NOT an Android build or handset run).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    (check if args.check else patch)()
