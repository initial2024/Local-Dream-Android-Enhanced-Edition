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

## Alpha.7 focused acceptance: visible replay + true inference breakdown

- Historical TXT2IMG apple image: open **History → image → info / Generation parameters**. The primary visible button must read **“一键复刻原模型并生成”**. The secondary option is **“复用参数”**, not “复制”.
- One click must restore the original installed model ID, both prompts, exact seed, DPM++ 2M scheduler, CFG 7, 20 steps, 640×640 native canvas, and CPU/GPU selection; it must start exactly one foreground generation after the correct backend is ready. No implicit fallback to 512×512.
- Verify cases: historical model removed, seed missing, missing/incompatible model, img2img/inpaint/UltraFix, previous generation still active, and navigation back during backend init. These must not accidentally launch a new generation.
- For repeatable performance, run 640×640 GPU lowmem 20 steps three times without other heavy apps; then compare controlled warm Buffer and experimental Image modes **only if phone remains responsive**. An image appearing is not proof of a speed gain.
- In Advanced Settings, compare OpenCL tuning **Fast (original)** versus **Normal (experimental)** while retaining the same GPU memory profile. Normal uses a separately keyed per-shape cache; cold runs can take longer, and only repeated warm median times establish an improvement. The report records the effective `tuning` value. This follows [MNN official Session API guidance](https://github.com/alibaba/MNN/blob/master/docs/inference/session.md); it is not yet a verified speedup on this Mali GPU.
- Copy the app's latest 20-record JSONL report. Alpha.7 adds `clip_ms`, `unet_setup_ms`, `denoise_loop_ms`, `vae_decode_ms`, `native_first_unet_step_ms` and `native_generation_ms`. The denoise loop includes scheduler and CPU overhead, not isolated GPU kernel time. `first_progress_ms` is an SSE progress milestone, not first UNet execution.
- Alpha.6 actual phone observation (NOT an alpha.7 speed test): 640×640 / 20 steps / lowmem GPU, wall 181337 ms; available system RAM 2579→1803 MiB, thermal 0→0, battery 29.4→31.3°C. These measurements do not prove a memory leak or thermal throttling.
- Keep APK package and signing certificate unchanged; reject a build with a stale pinned alpha.6 native `.so`. Compare images with same full generation settings and installed model revision, recognizing model hashes and engine revision are not preserved in legacy history.

## Alpha.8 quality regression / alpha.9 recovery gate (2026-11-09)

**Known broken:** alpha.8 reported `success=true` and a faster 132756ms wall time for
Absolute Reality, 640×640, DPM++ 2M, 20 steps, CFG7, seed 418731773,
`gpu=true`, `profile=lowmem`, `tuning=fast`, but saved a full-frame
highly saturated multicolor noise image, rather than the correct apple scene.
Speed on a corrupted image must not be scored as an optimization.

**Evidence-based rollback:** Alpha.8 was compared directly against alpha.7.
The additional persistent dual-host CFG encoder buffers and once-per-step
timestep copy are reverted to the alpha.7 order of **negative then positive
conditioning, complete sample/timestep/encoder transfers per half**. The
lowmem UNet tuning cache uses a new `_serial_cfg_quality_recovery` namespace
to prevent reuse of any alpha.8 optimization metadata. FAST remains forced.

**Numerical gate:** Reject non-finite GPU UNet/VAE float values before emitting
a complete image. This does not catch all visually corrupt but finite images,
so `success` and `image_quality_verified` are distinct metrics. The static
CI lowmem-order regression test is NOT a substitute for an Android image test.

**Required device regression (must pass before any speed claim):**
1. Cover-install alpha.9 over alpha.8 without wiping existing models/history.
2. First cold run: Absolute Reality, exactly the above parameters, 640×640 GPU
   lowmem FAST. Verify the **apple, table and window**, not colored noise.
3. Open History and reproduce that same output with original settings.
   Ensure a second result is a recognizable apple; if not, **STOP** and
   revert to the published alpha.7 APK (same signing cert).
4. Record 3 warm-run `denoise_loop_ms`, total time and thermal status with
   comparable device load; compare median to the valid alpha.7 158522ms
   (not to alpha.8's invalid 132756ms).
5. If still corrupt on alpha.9, keep original and enhanced model data intact;
   fall back to alpha.7. Escalate with one new diagnostic JSONL report and a
   screenshot. Never infer a quality pass from CI success alone.

## Alpha.10: safe OpenCL warm-cache startup and per-step performance test

User device observations (same model, 640×640, 20 steps, GPU lowmem FAST,
same seed): alpha.7 correct-image 158522ms (UNet setup 7421ms, denoise 145950ms);
alpha.9 correct-image 180537ms (setup 28681ms, denoise 147002ms).
The 22015ms wall difference is almost fully explained by a 21260ms
setup difference. Alpha.9 intentionally created the new
`unet_cache_serial_cfg_quality_recovery.mnnc.640` namespace, so cold-cache
preparation is a plausible hypothesis. Single trials do not prove persistent
regression or a validated cache hit.

Alpha.10 keeps the exact known-good alpha.9 negative/positive CFG transfers,
seed, 640px pixels, scheduler, model and FP16 precision. It sets the native
MNN UNet interpreter to `Session_Release` (disables operation debug callback
bookkeeping) and records the *filesystem bytes* before and after the UNet
resize+cache-save stage. These values are PASSIVE observations: an existing
cache file can be stale, invalid or internally rejected by MNN. No claim of a
confirmed cache hit is allowed.

Phone acceptance gate before recommending alpha.10 as faster:
1. Install alpha.10 over alpha.9 without wiping any app history/model data.
2. Reproduce the same red apple/wood table/window image using the saved seed.
   If noisy or semantically broken, STOP. Do not count its speed.
3. Generate the same image **three times** with no heavy app concurrency;
   record both native `unet_setup_ms` and `denoise_loop_ms`, plus
   `unet_cache_file_before_bytes` and `unet_cache_file_after_bytes`.
4. Compare median of the 2nd/3rd successful runs with alpha.9 (and with
   alpha.7 where directly comparable), not only the first cold run.
5. If the cache file exists but setup remains >20s in repeated runs,
   inspect MNN validation logs / model geometry / cache invalidation;
   do not repeatedly rename caches or switch to NORMAL tuning.
6. No auto-selected batch=2 Buffer at 640px: user previously reported
   whole-phone stalls under concurrent apps. Low-memory remains default.

## Alpha.11 independent deterministic replay / UNet step variance audit

The user explicitly confirmed the two identical PNG files came from
**separate 640×640 generation runs** (not a duplicated file export).
The two uploaded files have the same SHA256 and pixels:
`02a4249e6909438615f24a373802cc614f05a77776603a98e3f8ef24a31d8666`.
This establishes **observed same-seed reproducibility** on that device,
not identical execution timing or GPU frequency.

Two correct alpha.10 runs used the same model/seed/CFG/20 steps/profile:
- Run A: 140446ms total; UNet setup 5289ms; denoise 130252ms.
- Run B: 157974ms total; UNet setup 5950ms; denoise 147560ms.
- UNet cache file existed and stayed 628596 bytes during both runs.
- System thermal status 0/0 does not prove GPU frequency stability.
- Most of the 17528ms difference came from the denoise loop, not cache setup.

Alpha.11 **does not change** kernel tuning, CFG input copies, GPU
precision, model weights, scheduler equations, or native cache namespace.
It only measures elapsed time for each 20-step UNet call (including tensor
uploads, GPU inference, output synchronization and CPU tensor handling),
plus scheduler.step time and separate lowmem negative/positive branches.
A 20-element `unet_step_times_ms` array and early/late medians can
distinguish a single anomalously slow step from a whole-run slowdown.
These timings are **NOT isolated GPU kernel times**.

Acceptance:
1. Cover-install with fixed signing cert; do not delete installed model/history.
2. Two independent TXT2IMG generations using the exact same saved apple
   settings must still produce visually normal images; compare file SHA256.
3. Collect the per-step arrays and `cfg_uncond_total_ms` vs
   `cfg_positive_total_ms` from the private report. Confirm the count
   matches the steps actually run (20 for this TXT2IMG reference).
4. Compare cold/warm runs without concurrent heavy apps; then separately
   test while GPT is active. Record Android thermal and system RAM but do
   not conflate either with actual Mali GPU clocks or GPU allocation.
5. If visual output differs or returns chromatic noise, STOP; reject this
   version even if nominal timing improves.
6. Do not claim a speed gain from additional profiling. Only
   repeated, valid outputs and warm median timings can establish it.

## Separate later gates
- Photo-to-MP4 encoder: verify offline video playback and duration on real Android hardware; it is not an AI video generation model.
- SDXL MNN, Qwen Image, FLUX or true image-to-video: each requires a separate independently verified backend, format conversion, licensing, memory and output-quality assessment. No support is implied by the initial SD1.5 test APK.
