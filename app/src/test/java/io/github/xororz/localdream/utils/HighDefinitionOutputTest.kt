package io.github.xororz.localdream.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HighDefinitionOutputTest {
    @Test fun keepsOriginalAspectAndUsesBoundedPixels() {
        assertEquals(640 to 640, HighDefinitionOutput.aspectExportDimensions(640, 640, 100))
        assertEquals(960 to 960, HighDefinitionOutput.aspectExportDimensions(640, 640, 150))
        assertEquals(1280 to 1280, HighDefinitionOutput.aspectExportDimensions(640, 640, 200))
        assertEquals(1440 to 1088, HighDefinitionOutput.aspectExportDimensions(1440, 1088, 100))
        assertEquals(2160 to 1632, HighDefinitionOutput.aspectExportDimensions(1440, 1088, 150))
        assertEquals(4096 to 4096, HighDefinitionOutput.aspectExportDimensions(4096, 4096, 100))
    }

    @Test fun rejectsOversizedOrInvalidScaling() {
        assertThrows(IllegalArgumentException::class.java) {
            HighDefinitionOutput.aspectExportDimensions(1440, 1088, 200)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HighDefinitionOutput.aspectExportDimensions(640, 640, 300)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HighDefinitionOutput.aspectExportDimensions(0, 640, 100)
        }
    }
}
