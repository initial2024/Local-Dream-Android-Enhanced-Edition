package io.github.xororz.localdream.data

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
