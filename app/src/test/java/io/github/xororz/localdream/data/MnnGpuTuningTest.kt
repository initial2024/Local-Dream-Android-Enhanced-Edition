package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MnnGpuTuningTest {
    @Test fun fastIsSafeDefaultAndUnknownIsRejected() {
        assertEquals(MnnGpuTuning.FAST, MnnGpuTuning.validated(null))
        assertEquals(MnnGpuTuning.FAST, MnnGpuTuning.validated("heavy"))
        assertEquals(MnnGpuTuning.FAST, MnnGpuTuning.validated("NORMAL"))
    }

    @Test fun normalIsExplicitOptIn() {
        assertEquals(MnnGpuTuning.NORMAL, MnnGpuTuning.validated("normal"))
        assertEquals(MnnGpuTuning.FAST, MnnGpuTuning.validated("fast"))
    }
}
