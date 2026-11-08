package io.github.xororz.localdream.data

/**
 * Optional, user-initiated SD1.5 checkpoint sources. These are NOT .mnn
 * packages: installation always requires SHA256 validation followed by the
 * offline Local Dream conversion process. No checkpoint bytes are bundled.
 *
 * Keep identifiers stable across releases to preserve model history.
 */
data class MnnSuggestedCheckpoint(
    val id: String,
    val title: String,
    val purpose: String,
    val sourcePage: String,
    val downloadUrl: String,
    val sourceSha256: String,
    val sourceNote: String,
    val clipSkip: Int = 1,
)

object MnnModelSuggestions {
    val options: List<MnnSuggestedCheckpoint> = listOf(
        MnnSuggestedCheckpoint(
            id = "dreamshaper8mnn",
            title = "DreamShaper 8",
            purpose = "Art / illustration / semi-realistic scenes (SD1.5)",
            sourcePage = "https://huggingface.co/Lykon/DreamShaper/blob/main/DreamShaper_8_pruned.safetensors",
            downloadUrl = "https://huggingface.co/Lykon/DreamShaper/resolve/main/DreamShaper_8_pruned.safetensors",
            sourceSha256 = "879db523c30d3b9017143d56705015e15a2cb5628762c11d086fed9538abd7fd",
            sourceNote = "Original creator Lykon, 2.13 GB; repository license listed as other; review terms before downloading",
        ),
        MnnSuggestedCheckpoint(
            id = "realisticvisionv6mnn",
            title = "Realistic Vision V6 + VAE",
            purpose = "Photorealistic portraits / realistic scenes (SD1.5)",
            sourcePage = "https://huggingface.co/visible-tactics/realisticVisionV60B1_v51VAE/blob/main/realisticVisionV60B1_v51VAE.safetensors",
            downloadUrl = "https://huggingface.co/visible-tactics/realisticVisionV60B1_v51VAE/resolve/main/realisticVisionV60B1_v51VAE.safetensors",
            sourceSha256 = "15012c538f503ce2ebfc2c8547b268c75ccdaff7a281db55399940ff1d70e21d",
            sourceNote = "Community mirror (not original author), 2.13 GB, CreativeML Open RAIL-M; verify origin and conditions",
        ),
    )

    fun byId(id: String): MnnSuggestedCheckpoint? =
        options.firstOrNull { it.id == id }
}
