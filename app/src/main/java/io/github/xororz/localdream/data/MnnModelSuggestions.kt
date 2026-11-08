package io.github.xororz.localdream.data

/** Public model references, not bundled or preconverted artifacts.
 *
 * These checkpoints still require the app's experimental on-device
 * safetensors-to-MNN conversion. Their source license and SHA must be checked
 * by the user before download; the app NEVER silently downloads them.
 */
data class MnnSuggestedCheckpoint(
    val title: String,
    val purpose: String,
    val sourcePage: String,
    val sourceSha256: String,
    val sourceNote: String,
)

object MnnModelSuggestions {
    val options = listOf(
        MnnSuggestedCheckpoint(
            title = "DreamShaper 8",
            purpose = "Illustration / semi-realistic scenes",
            sourcePage = "https://huggingface.co/Lykon/DreamShaper/blob/main/DreamShaper_8_pruned.safetensors",
            sourceSha256 = "879db523c30d3b9017143d56705015e15a2cb5628762c11d086fed9538abd7fd",
            sourceNote = "Model creator's Hugging Face repository; 2.13 GB pruned SD1.5 checkpoint",
        ),
        MnnSuggestedCheckpoint(
            title = "Realistic Vision V6 + VAE",
            purpose = "Photorealistic portraits / scenes",
            sourcePage = "https://huggingface.co/visible-tactics/realisticVisionV60B1_v51VAE/blob/main/realisticVisionV60B1_v51VAE.safetensors",
            sourceSha256 = "15012c538f503ce2ebfc2c8547b268c75ccdaff7a281db55399940ff1d70e21d",
            sourceNote = "Community mirror, NOT original publisher; 2.13 GB SD1.5 checkpoint with VAE; verify provenance/license",
        ),
    )
}
