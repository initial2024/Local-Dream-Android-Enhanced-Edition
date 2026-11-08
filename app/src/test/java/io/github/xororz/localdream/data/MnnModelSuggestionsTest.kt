package io.github.xororz.localdream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnModelSuggestionsTest {
    @Test fun curatedCheckpointsUsePinnedHttpsWeightsAndDistinctIds() {
        val all = MnnModelSuggestions.options
        assertEquals(2, all.size)
        assertEquals(all.size, all.map { it.modelId }.distinct().size)
        for (entry in all) {
            assertTrue(entry.downloadUrl.startsWith("https://huggingface.co/"))
            assertTrue(entry.downloadUrl.endsWith(".safetensors"))
            assertFalse(entry.downloadUrl.contains("/blob/"))
            assertEquals(64, entry.sourceSha256.length)
            assertTrue(entry.sourceSha256.all { it in '0'..'9' || it in 'a'..'f' })
            assertTrue(entry.clipSkip == 1 || entry.clipSkip == 2)
            assertTrue(ModelRepository.isReservedModelId(entry.modelId))
        }
    }

    @Test fun eachCheckpointPinsItsActualOwnerAndChecksum() {
        val dream = MnnModelSuggestions.options.single { it.modelId == "dreamshaper8_mnn" }
        assertEquals(
            "https://huggingface.co/Lykon/DreamShaper/resolve/main/DreamShaper_8_pruned.safetensors",
            dream.downloadUrl,
        )
        assertEquals(
            "879db523c30d3b9017143d56705015e15a2cb5628762c11d086fed9538abd7fd",
            dream.sourceSha256,
        )
        val realistic = MnnModelSuggestions.options.single { it.modelId == "realisticvision6_mnn" }
        assertEquals(
            "https://huggingface.co/visible-tactics/realisticVisionV60B1_v51VAE/resolve/main/realisticVisionV60B1_v51VAE.safetensors",
            realistic.downloadUrl,
        )
        assertEquals(
            "15012c538f503ce2ebfc2c8547b268c75ccdaff7a281db55399940ff1d70e21d",
            realistic.sourceSha256,
        )
    }
}
