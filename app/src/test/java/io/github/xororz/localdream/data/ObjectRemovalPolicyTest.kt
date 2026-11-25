package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectRemovalPolicyTest {
    @Test fun roiMapsPreviewToSourceAndKeepsBounds() {
        val out = ObjectRemovalPolicy.paddedSourceBox(
            ObjectRemovalPolicy.Box(400, 400, 600, 600),
            1000, 1000, 4000, 3000,
        )
        assertTrue(out.left >= 0 && out.top >= 0)
        assertTrue(out.right <= 4000 && out.bottom <= 3000)
        assertTrue(out.width > 800)
        assertTrue(out.height > 600)
    }

    @Test fun workCanvasIsBoundedAndAligned() {
        val (w, h) = ObjectRemovalPolicy.workSize(ObjectRemovalPolicy.Box(0, 0, 1400, 700))
        assertEquals(640, w)
        assertTrue(h in 128..640)
        assertEquals(0, w % 8)
        assertEquals(0, h % 8)
    }

    @Test fun refusesHugeFullResolutionReplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjectRemovalPolicy.paddedSourceBox(
                ObjectRemovalPolicy.Box(10, 10, 20, 20),
                100, 100, 9000, 5000,
            )
        }
    }
}
