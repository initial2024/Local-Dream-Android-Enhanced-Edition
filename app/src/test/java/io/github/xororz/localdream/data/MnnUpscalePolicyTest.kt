package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MnnUpscalePolicyTest {
    @Test fun supportsBounded4xAndAspect() {
        assertEquals(2560 to 2560, MnnUpscalePolicy.resultSize(640, 640))
        assertEquals(2560 to 1920, MnnUpscalePolicy.resultSize(640, 480))
        assertEquals(4 to 4, MnnUpscalePolicy.resultSize(1, 1))
    }
    @Test fun rejectsOversizedAndInvalidInputs() {
        for ((w,h) in listOf(641 to 640, 640 to 641, 0 to 640, -1 to 10)) {
            assertThrows(IllegalArgumentException::class.java) {
                MnnUpscalePolicy.resultSize(w,h)
            }
        }
    }
}
