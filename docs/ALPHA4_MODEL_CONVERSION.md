# alpha.4 — More SD1.5 models on Dimensity via genuine offline MNN conversion

**Status:** Code and native compile under review. Neither listed candidate is asserted as already converted, compatible, benchmarked or downloaded. Do not present the standalone conversion command as a completed device-tested feature.

## Why this path
Original Local Dream has **5 confirmed pre-converted SD1.5 MNN packages** from `xororz/sd-mnn`: Absolute Reality, ChilloutMix, Anything V5, QteaMix, CuteYukiMix. These should remain selectable without redownloading any existing model; downloading others is optional.

Random external .mnn bundles often have a different tokenizer, CLIP graph, tensor naming, memory format, quantization or sampler. For example Q-Dream's LCM models and Nezumi's Realistic Vision conversion are **not automatically interoperable** with the Local Dream MNN runtime. Qualcomm .bin packages must **never** appear as runnable on MediaTek.

Instead, restore upstream conversion of an SD1.5 checkpoint with the original pinned Local Dream MNN base graphs. This alpha uses the original `SafeTensor2MNN.hpp` and quantization structures in a separate standalone `--convert` process, with no QNN SDK; a separately verified conversion and image-generation test must precede claims of support.

## Suggested candidates — not bundled

1. **DreamShaper 8** — general illustration / stylized realism. Author `Lykon`, source: https://huggingface.co/Lykon/DreamShaper/blob/main/DreamShaper_8_pruned.safetensors ; roughly 2.13 GB, SHA256 `879db523c30d3b9017143d56705015e15a2cb5628762c11d086fed9538abd7fd`. License: check the model creator's model card and conditions; no model bytes are mirrored or bundled here.
2. **Realistic Vision V6 + VAE** — photorealism. Community mirror https://huggingface.co/visible-tactics/realisticVisionV60B1_v51VAE/blob/main/realisticVisionV60B1_v51VAE.safetensors ; roughly 2.13 GB, SHA256 `15012c538f503ce2ebfc2c8547b268c75ccdaff7a281db55399940ff1d70e21d`. The publisher is a community mirror, **not the original model creator**; check provenance and full license at source. The original `SG161222/Realistic_Vision_V6.0_B1_noVAE` has a no-VAE option unsuitable for the original phone converter.

## User workflow (only after native + package gates pass)

1. In model list select `Import SD1.5 .safetensors (convert offline)`, grant access to the locally downloaded **single-file** checkpoint and choose a new unique model ID.
2. Choose CLIP skip (1 or 2) from the creator's model-specific instructions. This alpha does not apply loose LoRA files. Merge LoRA into the source checkpoint first.
3. Converter checks file size and free storage, stages the source with original pinned Local Dream base graphs, and invokes local `libstable_diffusion_core.so --convert <hidden-staging-dir>` without a network connection.
4. Native conversion checks the header and source assets. After a successful exit the app checks all CLIP/UNet/VAE models, embedding tables and *.mnn.weight files, deletes only the input copy and temporary graph files *inside the staging directory*, then atomically renames the directory. Existing model directories are NEVER removed or overwritten. On failed conversion staging is cleaned and original installed models remain.
5. Select the new model explicitly. Test at 512×512 first, then 640 only if stable. Record memory peak, model load, generation time, prompt, sampler and actual image file size.

## Risk and stop conditions

- This is a real CPU and storage intensive conversion; 12 GB RAM is not a guarantee. Converting a ~2.13 GB SD1.5 model may require several GiB spare RAM and about **7 GiB free storage** during copying and quantization. Large memory/OOM/thermal failures: STOP, retain existing model, do not attempt a larger checkpoint.
- Only SD1.5 complete checkpoint with **included VAE** is accepted. No SDXL / FLUX / DiT / QNN / arbitrary .mnn layout or cross-model LoRA hot-swap.
- In the old upstream conversion UI code an existing destination could be deleted; the enhanced path MUST NOT call this old destructive code. The enhanced Kotlin conversion runs only through the new non-destructive `MnnSafetensorsConverter`; the full upstream path stays only in the non-enhanced product flavor.
- If native conversion exits successfully but actual model inference fails, mark the model incompatible; do not remove other models. Never infer pixel-quality improvements from a different checkpoint without matched settings.
- Full SD1.5 MNN Model CLI and Kotlin CI, signed alpha4 APK, first real conversion and image-generation on the owner's MediaTek phone are **separate gates**.
