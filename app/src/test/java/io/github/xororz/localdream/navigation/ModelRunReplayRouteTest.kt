package io.github.xororz.localdream.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRunReplayRouteTest {
    @Test fun historyReplayPreservesModelAndExactItem() {
        assertEquals(
            "model_run/absoluterealitycpu?remote=false&historyId=418731773&replayNow=true",
            Screen.ModelRun.createRoute(
                modelId = "absoluterealitycpu",
                historyId = 418731773,
                replayNow = true,
            ),
        )
    }

    @Test fun oldModelNavigationRemainsNonReplay() {
        assertTrue(Screen.ModelRun.createRoute("absoluterealitycpu").contains("replayNow=false"))
        assertTrue(Screen.ModelRun.createRoute("absoluterealitycpu", remote = true)
            .contains("remote=true"))
    }
}
