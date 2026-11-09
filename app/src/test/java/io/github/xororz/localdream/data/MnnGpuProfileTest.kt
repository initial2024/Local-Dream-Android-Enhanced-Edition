package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MnnGpuProfileTest {
    @Test fun autoPreservesVerified512AndProtects640Plus() {
        assertEquals(MnnGpuProfile.BUFFER, MnnGpuProfile.effective(null, 512, 512))
        assertEquals(MnnGpuProfile.LOW_MEMORY, MnnGpuProfile.effective(null, 640, 640))
        assertEquals(MnnGpuProfile.LOW_MEMORY, MnnGpuProfile.effective("auto", 768, 512))
    }

    @Test fun explicitChoicesRemainStable() {
        assertEquals(MnnGpuProfile.BUFFER, MnnGpuProfile.effective("buffer", 1024, 1024))
        assertEquals(MnnGpuProfile.IMAGE, MnnGpuProfile.effective("image", 640, 640))
        assertEquals(MnnGpuProfile.LOW_MEMORY, MnnGpuProfile.effective("lowmem", 512, 512))
        assertEquals(MnnGpuProfile.AUTO, MnnGpuProfile.validated("unknown"))
    }
}
