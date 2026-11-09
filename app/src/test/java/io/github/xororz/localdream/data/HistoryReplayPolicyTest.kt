package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplayPolicyTest {
    private fun accept(
        modelId: String? = "absoluterealitycpu",
        active: String = "absoluterealitycpu",
        mode: GenerationMode = GenerationMode.TXT2IMG,
        seed: Long? = 12345L,
        width: Int = 640,
        height: Int = 640,
        steps: Int = 20,
        cfg: Float = 7f,
        downloaded: Boolean = true,
        backendReady: Boolean = true,
        busy: Boolean = false,
        inputImages: Boolean = false,
    ) = HistoryReplayPolicy.canAutoGenerate(
        modelId, active, mode, seed, width, height, steps, cfg,
        downloaded, mnnOnly = true, runOnCpu = true, remote = false,
        backendReady = backendReady, busy = busy,
        hasInputImageOrReferences = inputImages,
    )

    @Test fun reproducesNative640And768WithOriginalModel() {
        assertTrue(accept())
        assertTrue(accept(width = 768, height = 512))
        assertTrue(accept(width = 1024, height = 1024))
    }

    @Test fun neverInventsMissingAssetsOrModel() {
        assertFalse(accept(modelId = "dreamshaper8_mnn"))
        assertFalse(accept(mode = GenerationMode.IMG2IMG))
        assertFalse(accept(mode = GenerationMode.INPAINT))
        assertFalse(accept(mode = GenerationMode.ULTRAFIX))
        assertFalse(accept(seed = null))
        assertFalse(accept(downloaded = false))
        assertFalse(accept(inputImages = true))
    }

    @Test fun rejectsInvalidOrBusySnapshots() {
        assertFalse(accept(width = 704, height = 1025))
        assertFalse(accept(width = 641))
        assertFalse(accept(steps = 0))
        assertFalse(accept(cfg = Float.NaN))
        assertFalse(accept(busy = true))
        assertFalse(accept(backendReady = false))
    }
}
