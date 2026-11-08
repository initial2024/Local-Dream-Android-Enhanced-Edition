package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnCheckpointPolicyTest {
    @Test fun boundsAndOverflowNeverAuthorizeHugeCheckpoint() {
        assertFalse(MnnCheckpointPolicy.acceptableSourceSize(1))
        assertFalse(MnnCheckpointPolicy.acceptableSourceSize(Long.MAX_VALUE))
        assertNull(MnnCheckpointPolicy.requiredFreeBytes(Long.MAX_VALUE))
        assertNull(MnnCheckpointPolicy.requiredFreeBytes(-1))
        assertTrue(MnnCheckpointPolicy.acceptableSourceSize(2L * 1024 * 1024 * 1024))
        assertEquals(
            3L * (2L * 1024 * 1024 * 1024) + 512L * 1024 * 1024,
            MnnCheckpointPolicy.requiredFreeBytes(2L * 1024 * 1024 * 1024),
        )
    }

    @Test fun safeIdsRejectReservedAndFilesystemPaths() {
        assertEquals("dreamshaper8", MnnCheckpointPolicy.safeModelId("dreamshaper8"))
        assertNull(MnnCheckpointPolicy.safeModelId("absoluterealitycpu"))
        assertNull(MnnCheckpointPolicy.safeModelId("__"))
        assertEquals("x", MnnCheckpointPolicy.safeModelId("../../x"))
    }
}
