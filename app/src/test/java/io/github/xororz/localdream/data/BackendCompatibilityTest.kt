package io.github.xororz.localdream.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

class BackendCompatibilityTest {
    @Test fun mediatekAllowsMnnCpuAndOpenCl() {
        for (type in listOf("sd15cpu", "sdxlmnn")) {
            assertNull(BackendCompatibility.rejection(type, qualcommSoc = false, compatibleHexagon = false))
        }
    }

    @Test fun mediatekRejectsQualcommNpuAndDit() {
        for (type in listOf("sd15npu", "sdxl", "anima", "upscaler",
            "zimage", "klein", "qwen21", "krea2turbo")) {
            assertNotNull(type, BackendCompatibility.rejection(type, qualcommSoc = false,
                compatibleHexagon = false))
        }
    }

    @Test fun olderSnapdragonDoesNotClaimHexagonDitSupport() {
        assertNull(BackendCompatibility.rejection("sd15npu", qualcommSoc = true,
            compatibleHexagon = false))
        assertNotNull(BackendCompatibility.rejection("klein", qualcommSoc = true,
            compatibleHexagon = false))
    }

    @Test fun newerCompatibleSnapdragonSupportsBoth() {
        for (type in listOf("sd15cpu", "sdxlmnn", "sd15npu", "sdxl",
            "zimage", "klein", "qwen21")) {
            assertNull(type, BackendCompatibility.rejection(type, qualcommSoc = true,
                compatibleHexagon = true))
        }
    }

    @Test fun enhancedBasicDoesNotAdvertiseMissingNativeImplementations() {
        assertNull(
            BackendCompatibility.rejection(
                "sd15cpu", qualcommSoc = false, compatibleHexagon = false,
                mnnOnlyBuild = true,
            ),
        )
        for (backend in listOf("sdxlmnn", "sd15npu", "sdxl", "upscaler",
            "anima", "zimage", "klein", "qwen21", "krea2turbo")) {
            val reason = BackendCompatibility.rejection(
                backend, qualcommSoc = true, compatibleHexagon = true,
                mnnOnlyBuild = true,
            )
            assertNotNull(backend, reason)
            assertTrue(reason!!.contains("MNN-only"))
        }
    }

    @Test fun unknownBackendFailsClosed() {
        assertTrue(BackendCompatibility.rejection("future_quant_backend", true, true)!!
            .contains("Unknown"))
        assertNotNull(BackendCompatibility.rejection("", true, true))
    }
}
