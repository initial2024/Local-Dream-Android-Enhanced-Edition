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
        assertEquals(0, w % 64)
        assertEquals(0, h % 64)
        assertTrue(ObjectRemovalPolicy.isValidNativeCanvas(w, h))
    }

    @Test fun ordinaryRectangularRoisObeyNativeHttpContract() {
        // Previous policy could return, e.g., 640x456 or 328x512:
        // those are divisible by 8 but rejected with HTTP 400 by MNN.
        val boxes = listOf(
            ObjectRemovalPolicy.Box(0, 0, 800, 571),
            ObjectRemovalPolicy.Box(0, 0, 360, 243),
            ObjectRemovalPolicy.Box(0, 0, 480, 820),
            ObjectRemovalPolicy.Box(0, 0, 128, 128),
            ObjectRemovalPolicy.Box(0, 0, 2000, 10),
        )
        for (box in boxes) {
            val (w, h) = ObjectRemovalPolicy.workSize(box)
            assertTrue("${box.width}x${box.height} -> ${w}x${h}",
                ObjectRemovalPolicy.isValidNativeCanvas(w, h))
            assertTrue(w in 128..640 && h in 128..640)
        }
        assertTrue(ObjectRemovalPolicy.isValidNativeCanvas(448, 640))
        assertTrue(!ObjectRemovalPolicy.isValidNativeCanvas(448, 632))
        assertTrue(!ObjectRemovalPolicy.isValidNativeCanvas(504, 640))
    }

    @Test fun refusesHugeFullResolutionReplacement() {
        assertThrows(IllegalArgumentException::class.java) {
            ObjectRemovalPolicy.paddedSourceBox(
                ObjectRemovalPolicy.Box(10, 10, 20, 20),
                100, 100, 9000, 5000,
            )
        }
    }
    @Test fun classifiesCoarsePaintedRegion() {
        assertEquals(
            ObjectRemovalPolicy.ApproxBodyRegion.HEAD_SHOULDERS,
            ObjectRemovalPolicy.classifyApproxBodyRegion(
                ObjectRemovalPolicy.Box(350, 30, 650, 210),
                1000,
                1000,
            ),
        )
        assertEquals(
            ObjectRemovalPolicy.ApproxBodyRegion.UPPER_BODY,
            ObjectRemovalPolicy.classifyApproxBodyRegion(
                ObjectRemovalPolicy.Box(250, 260, 750, 520),
                1000,
                1000,
            ),
        )
        assertEquals(
            ObjectRemovalPolicy.ApproxBodyRegion.LOWER_BODY,
            ObjectRemovalPolicy.classifyApproxBodyRegion(
                ObjectRemovalPolicy.Box(300, 650, 700, 900),
                1000,
                1000,
            ),
        )
        assertEquals(
            ObjectRemovalPolicy.ApproxBodyRegion.FULL_BODY,
            ObjectRemovalPolicy.classifyApproxBodyRegion(
                ObjectRemovalPolicy.Box(250, 120, 750, 820),
                1000,
                1000,
            ),
        )
    }

}
