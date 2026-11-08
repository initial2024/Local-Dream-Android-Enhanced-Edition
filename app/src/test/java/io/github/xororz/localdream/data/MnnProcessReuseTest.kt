package io.github.xororz.localdream.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnProcessReuseTest {
    private val original = MnnProcessSignature(
        modelId = "absoluterealitycpu",
        backendType = "sd15cpu",
        cpuThreads = 4,
        listenOnAll = false,
    )

    private fun canReuse(
        from: MnnProcessSignature? = original,
        to: MnnProcessSignature = original,
        alive: Boolean = true,
        restart: Boolean = false,
        mnnOnly: Boolean = true,
    ) = MnnProcessReuse.canKeepProcess(mnnOnly, alive, restart, from, to)

    @Test fun sameModelMayChangeCanvasWithoutRelaunch() {
        assertTrue(canReuse())
    }

    @Test fun differentModelOrUnsafeReconfigurationAlwaysRelaunches() {
        assertFalse(canReuse(to = original.copy(modelId = "chilloutmixcpu")))
        assertFalse(canReuse(to = original.copy(backendType = "sdxlmnn")))
        assertFalse(canReuse(to = original.copy(cpuThreads = 6)))
        assertFalse(canReuse(to = original.copy(listenOnAll = true)))
        assertFalse(canReuse(from = null))
        assertFalse(canReuse(alive = false))
        assertFalse(canReuse(restart = true))
        assertFalse(canReuse(mnnOnly = false))
    }
}
