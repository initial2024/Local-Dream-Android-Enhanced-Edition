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
}
