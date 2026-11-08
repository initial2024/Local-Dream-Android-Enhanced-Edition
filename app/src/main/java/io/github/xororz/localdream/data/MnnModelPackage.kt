package io.github.xororz.localdream.data

/**
 * Layout of a Local Dream-compatible SD1.5 MNN model package.
 *
 * This is intentionally not a general .safetensors or Qualcomm QNN import.
 * Those formats need an actual converter or separate runtime; passing them
 * straight to the MNN-only backend would fail.
 */
object MnnModelPackage {
    val requiredFiles: Set<String> = setOf(
        "tokenizer.json",
        "clip_v2.mnn",
        "unet.mnn",
        "vae_decoder.mnn",
        "vae_encoder.mnn",
        "pos_emb.bin",
        "token_emb.bin",
    )
    // Require VAE encoder because the existing UI enables img2img by default.
    // An incompatible txt2img-only package must never appear as fully usable.
    val optionalFiles: Set<String> = setOf("config.json")
    val allowedFiles: Set<String> = requiredFiles + optionalFiles

    const val MAX_FILES = 40
    const val MAX_TOTAL_BYTES = 6L * 1024 * 1024 * 1024
    const val MAX_JSON_BYTES = 32L * 1024 * 1024
    const val MIN_FREE_BYTES = 256L * 1024 * 1024

    fun cleanId(suggestedFilename: String): String {
        val base = suggestedFilename.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".zip").removeSuffix(".ZIP")
        return base.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .trim('_', '-')
            .take(48)
    }

    fun safePackageFile(entryName: String): String? {
        if (entryName.isBlank() || entryName.startsWith('/') ||
            entryName.startsWith('\\') || ':' in entryName ||
            '\\' in entryName
        ) return null
        val segments = entryName.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) return null
        return segments.last().takeIf { it in allowedFiles }
    }

    fun containsRequired(names: Collection<String>): Boolean =
        names.containsAll(requiredFiles)
}
