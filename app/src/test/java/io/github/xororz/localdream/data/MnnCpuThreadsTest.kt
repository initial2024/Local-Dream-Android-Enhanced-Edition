package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MnnCpuThreadsTest {
    @Test fun defaultAndTypicalValues() {
        assertEquals(4, MnnCpuThreads.DEFAULT)
        for (count in 1..8) {
            assertEquals(count, MnnCpuThreads.validated(count))
        }
    }

    @Test fun corruptedPreferencesCannotEscapeSafeBounds() {
        assertEquals(1, MnnCpuThreads.validated(Int.MIN_VALUE))
        assertEquals(1, MnnCpuThreads.validated(0))
        assertEquals(8, MnnCpuThreads.validated(9))
        assertEquals(8, MnnCpuThreads.validated(Int.MAX_VALUE))
    }
}
