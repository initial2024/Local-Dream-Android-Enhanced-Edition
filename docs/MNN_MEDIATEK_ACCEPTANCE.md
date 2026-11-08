# Dimensity 9400+ MNN-only device acceptance matrix

**Status:** Native standalone arm64 build PASS; Android signed release and actual image generation must be independently verified. Never interpret this document as a completed device benchmark.

## Preconditions
- Install enhanced `io.github.initial2024.localdream.enhanced` side-by-side with upstream v2.8.1. Do not uninstall original app or erase original private model data.
- Use an original Local Dream-compatible **SD1.5 MNN CPU** model with the expected `tokenizer.json`, `clip_v2.mnn`, `unet.mnn`, `vae_decoder.mnn`, `pos_emb.bin`, `token_emb.bin`, and optional `vae_encoder.mnn`. A model marked Qualcomm QNN-only will not load on MediaTek.
- Record exact APK SHA256, signing certificate SHA256, app version, model identifier + file checksums, Android version, battery level, performance mode, ambient conditions and storage free space.
- Keep phone offline after model download to verify genuinely local generation.

## Functional smoke (fail if any mandatory item fails)
1. First boot: launch without crash, create model directory, list MNN-only catalog, do not trigger QNN FastRPC or force any OS permission unrelated to model storage.
2. On **CPU**: 512×512, 20 steps, CFG=7, DPM scheduler, seed=42, same simple test prompt. Verify full progress, SSE `complete` event, valid RGB image, actual saved file and history record. A green status or /health alone is not proof of correct inference.
3. Repeat the same recipe: ensure history reproduction restores prompt, negative prompt, steps, CFG, seed, scheduler and dimensions. Expect approximate semantic reproduction; model changes and backend nondeterminism can prevent pixel identity.
4. On **GPU/OpenCL**: generate the same 512×512 recipe, report whether it really uses OpenCL and whether OpenCL initialization fails. If unsupported, show an actionable error and retry explicitly on CPU; do not silently mislabel CPU as GPU.
5. Cancel during a UNet step: confirm no finished image is falsely saved and a subsequent generation works. Verify model resources are released when the backend stops.
6. Configure 2, 4, 6 and 8 CPU threads, restart the model each time, and confirm the setting reaches the backend. Avoid extrapolating from one generation.
7. Try incorrect/nonexistent MNN component, unsupported SDXL/QNN/DiT file, invalid resolution and invalid request parameters. Verify explicit rejection, no partial outputs and no modification/deletion of model files.
8. Phone reboot; existing model and history remain accessible without forced re-download. Basic variant must not ship a safety checker model unexpectedly.
9. Export 1440×1080 from the native low-resolution result and verify file dimensions, center cropping and accurate UI label that it is *not* native 1440 diffusion detail.

## Reproducible performance sampling
- First successful generation is a **cold-cache** observation, not directly comparable to warm measurements.
- For each of CPU 2, 4, 6, 8 threads and GPU/OpenCL, allow the device to return to a comparable thermal state and perform at least 3 warm runs using **the same model, seed, steps, CFG and native resolution**.
- Record median `generation_time_ms`, `first_step_time_ms`, peak app and native RSS, thermal status, model load time, disk cache size and battery consumption. Preserve artifacts per run.
- Select defaults only after confirming an actual advantage and adequate thermal stability. Faster one-off results are insufficient.
- During high thermal throttling, OOM, repeated GPU driver failure, wrong certificate, corrupted images or unintended data deletion: **STOP** the rollout and retain the original v2.8.1 app.

## Separate later gates
- Photo-to-MP4 encoder: verify offline video playback and duration on real Android hardware; it is not an AI video generation model.
- SDXL MNN, Qwen Image, FLUX or true image-to-video: each requires a separate independently verified backend, format conversion, licensing, memory and output-quality assessment. No support is implied by the initial SD1.5 test APK.
