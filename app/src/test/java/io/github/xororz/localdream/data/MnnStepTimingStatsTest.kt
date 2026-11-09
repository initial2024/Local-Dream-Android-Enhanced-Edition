package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MnnStepTimingStatsTest {
    @Test fun reportsIndependentSameImageRunTimingVariation() {
        val a = longArrayOf(6100, 6400, 6500, 6800, 6700, 6900, 7000)
        val s = MnnStepTimingStats.summarize(a)!!
        assertEquals(6100L, s.minimumMs)
        assertEquals(6700L, s.medianMs)
        assertEquals(7000L, s.maximumMs)
        assertEquals(6500L, s.firstFiveMedianMs)
        assertEquals(6800L, s.lastFiveMedianMs)
    }

    @Test fun neverPretendsMissingOrInvalidNativeMetricsAreMeasured() {
        assertNull(MnnStepTimingStats.summarize(null))
        assertNull(MnnStepTimingStats.summarize(longArrayOf()))
        assertNull(MnnStepTimingStats.summarize(longArrayOf(1, -1, 3)))
        assertNull(MnnStepTimingStats.summarize(LongArray(51) { 5L }))
        assertNull(MnnStepTimingStats.summarize(longArrayOf(120001L)))
        assertEquals(123L, MnnStepTimingStats.summarize(longArrayOf(123))!!.medianMs)
    }

    @Test fun medianHandlesEvenSeriesWithoutFloatingPointNondeterminism() {
        assertEquals(15L, MnnStepTimingStats.median(listOf(20, 10)))
    }
}
