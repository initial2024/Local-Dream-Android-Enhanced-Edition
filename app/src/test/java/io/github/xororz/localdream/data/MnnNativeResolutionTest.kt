package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnNativeResolutionTest {
    @Test fun legacySquareResolutionsRemainSupported() {
        for (side in 128..512 step 64) {
            assertTrue(MnnNativeResolution.isAllowed(side, side))
            assertFalse(MnnNativeResolution.needsExperimentalWarning(side, side))
        }
    }

    @Test fun experimentalNativeHdPresetsHaveRealPixels() {
        for (resolution in MnnNativeResolution.presets) {
            assertTrue(
                "Invalid preset ${resolution.width}x${resolution.height}",
                MnnNativeResolution.isAllowed(resolution.width, resolution.height),
            )
        }
        assertTrue(MnnNativeResolution.needsExperimentalWarning(1024, 768))
        assertTrue(MnnNativeResolution.isAllowed(768, 1024))
        assertTrue(MnnNativeResolution.isAllowed(1024, 1024))
    }

    @Test fun rejectUnboundedAndUnalignedAllocations() {
        assertFalse(MnnNativeResolution.isAllowed(0, 512))
        assertFalse(MnnNativeResolution.isAllowed(1440, 1080))
        assertFalse(MnnNativeResolution.isAllowed(1024, 1088))
        assertFalse(MnnNativeResolution.isAllowed(2048, 2048))
        assertFalse(MnnNativeResolution.isAllowed(1024, 1000))
        assertFalse(MnnNativeResolution.isAllowed(-512, 512))
        assertFalse(MnnNativeResolution.isAllowed(Int.MAX_VALUE, Int.MAX_VALUE))
    }
}
