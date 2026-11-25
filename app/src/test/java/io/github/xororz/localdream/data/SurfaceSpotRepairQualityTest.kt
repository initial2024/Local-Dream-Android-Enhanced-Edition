package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Synthetic non-person surfaces with exact, known ground truth. */
class SurfaceSpotRepairQualityTest {
    private val w = 64
    private val h = 64

    private fun rgb(r: Int, g: Int, b: Int): Int =
        -0x1000000 or (r shl 16) or (g shl 8) or b

    private fun evaluate(name: String, reference: IntArray): Double {
        val mask = BooleanArray(w * h) { i ->
            i % w in 27..36 && i / w in 27..36
        }
        val damaged = reference.copyOf()
        for (i in mask.indices) if (mask[i]) damaged[i] = rgb(0, 0, 0)
        val result = SurfaceSpotRepair.repair(damaged, w, h, mask)
        var sum = 0L
        var count = 0
        for (i in mask.indices) {
            if (!mask[i]) {
                assertEquals("Outside-mask pixel " + i, reference[i], result[i])
            } else {
                for (shift in intArrayOf(0, 8, 16)) {
                    sum += abs(((reference[i] ushr shift) and 255) -
                        ((result[i] ushr shift) and 255)).toLong()
                    count++
                }
            }
        }
        val mae = sum.toDouble() / count
        println("SURFACE_QUALITY " + name + " masked_rgb_mae=" + mae +
            " outside_mask_mismatch=0")
        return mae
    }

    @Test fun flatSurfaceGroundTruth() {
        assertEquals(0.0, evaluate("flat", IntArray(w * h) { rgb(120, 150, 180) }), 0.0)
    }

    @Test fun coloredGradientGroundTruth() {
        val reference = IntArray(w * h) { i ->
            rgb(25 + 2 * (i % w), 30 + 2 * (i / w), 40 + i % w + i / w)
        }
        assertTrue("Smooth gradient error", evaluate("gradient", reference) <= 2.0)
    }

    @Test fun patternedSurfaceBaseline() {
        val reference = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if ((x / 4 + y / 4) % 2 == 0) rgb(65, 85, 105)
            else rgb(185, 205, 225)
        }
        // Diagnostic only: interpolation is not texture reconstruction.
        // The invariant on unchanged pixels is enforced by evaluate().
        val mae = evaluate("checker_pattern", reference)
        assertTrue(mae.isFinite() && mae in 0.0..255.0)
    }
}
