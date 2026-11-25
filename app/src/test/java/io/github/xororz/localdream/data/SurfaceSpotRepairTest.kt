package io.github.xororz.localdream.data

import org.junit.Assert.*
import org.junit.Test

class SurfaceSpotRepairTest {
    @Test fun restoresUniformBackgroundAndPreservesOutside() {
        val white = -1
        val black = -0x1000000
        val source = IntArray(40 * 40) { white }
        for (y in 18..20) for (x in 18..20) source[y * 40 + x] = black
        val selection = requireNotNull(SurfaceSpotRepair.select(source, 40, 40, 19, 19))
        assertEquals(9, selection.count)
        val output = SurfaceSpotRepair.repair(source, 40, 40, selection.mask)
        assertTrue(output.all { it == white })
        assertEquals(black, source[19 * 40 + 19])
    }

    @Test fun rejectsUniformAndEdgeConnectedRegion() {
        val source = IntArray(40 * 40) { -1 }
        assertNull(SurfaceSpotRepair.select(source, 40, 40, 20, 20))
        source[0] = -0x1000000
        assertNull(SurfaceSpotRepair.select(source, 40, 40, 0, 0))
    }

    @Test fun neverChangesUnselectedPixels() {
        val width = 32
        val source = IntArray(width * width) { i ->
            -0x1000000 or ((i % 32 * 7) shl 16) or ((i / 32 * 7) shl 8)
        }
        val mask = BooleanArray(source.size)
        mask[16 * width + 16] = true
        val output = SurfaceSpotRepair.repair(source, width, width, mask)
        for (i in source.indices) if (!mask[i]) assertEquals(source[i], output[i])
    }

    @Test fun approximatesLinearLightingGradient() {
        val w = 48
        val reference = IntArray(w * w) { i ->
            val gray = 30 + (i % w) * 3 + (i / w)
            -0x1000000 or (gray shl 16) or (gray shl 8) or gray
        }
        val damaged = reference.copyOf()
        val mask = BooleanArray(damaged.size)
        for (y in 20..26) for (x in 20..26) {
            mask[y * w + x] = true
            damaged[y * w + x] = -0x1000000
        }
        val output = SurfaceSpotRepair.repair(damaged, w, w, mask)
        for (i in mask.indices) {
            if (!mask[i]) assertEquals(reference[i], output[i])
            else assertTrue("gradient error at " + i,
                kotlin.math.abs((reference[i] and 255) - (output[i] and 255)) <= 2)
        }
    }

    @Test fun refusesTransparentBoundaryInsteadOfBleedingHiddenRgb() {
        val w = 16
        val source = IntArray(w * w) { -1 }
        val mask = BooleanArray(source.size)
        val center = 8 * w + 8
        mask[center] = true
        source[center - 1] = 0x00000000
        try {
            SurfaceSpotRepair.repair(source, w, w, mask)
            fail("Expected transparent boundary rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals("Repair touches a transparent boundary", e.message)
        }
        assertEquals(0, source[center - 1])
    }
}
