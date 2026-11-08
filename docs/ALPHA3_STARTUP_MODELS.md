# Alpha.3 — startup responsiveness, native model replacement and first-use UI

## Reported device evidence (2026-11-08)
- Dimensity 9400+ successfully ran SD1.5 MNN **640×640** OpenCL at 20 steps, seed 42, CFG 7, DPM++ 2M, with two reported wall-clock times **3m59s** and **3m12s**. This proves working native 640 output in that setup, **not** a controlled thermal/cache benchmark.
- The original welcome/help popup should not auto-open in the enhanced edition. Help stays under the overflow menu.
- Model-switch and startup improvements should not cost another model download or delete any existing MNN files.

## What changed
1. The model list no longer blocks local disk model discovery on optional remote-host reconnection.
2. Concurrent initial repository scans collapse to one scan guarded by a mutex.
3. The SD1.5 MNN server keeps its tokenizer process alive when the model id, backend type, CPU threads and binding policy match and **only the requested canvas size changes**. The C++ native /generate route accepts dynamic width and height. Model replacement, thread-count changes, crashes and explicit restarts still create a fresh process.
4. The generation page model title opens an installed-model selector; only downloaded MNN SD1.5 models are listed. Navigation must not change models during generation.
5. The model list has a dedicated ZIP importer for **preconverted Local Dream SD1.5 MNN** packages. Archive entry allowlist, required-component check, size bound and same-filesystem staging protect existing models. There is no conversion from arbitrary safetensors or Qualcomm binary packages.
6. Startup no longer automatically opens the upstream welcome popup in the enhanced flavor, but Help remains accessible on demand.
7. Generation logs distinguish time to first progress from total SSE completion; this is not yet a model load or GPU driver breakdown.

## Hard limits and stop conditions
- The only supported experimental local native inference backend is **SD1.5 MNN CPU/OpenCL**. Built-in Absolute Reality, ChilloutMix, Anything V5, QteaMix and CuteYukiMix use their separately downloaded original-format packages. Downloading five different models is **optional**, not part of startup.
- Import does not overwrite a model id that already exists. Preserve the installed original and all finished model folders. Complete ZIP must include tokenizer.json, clip_v2.mnn, unet.mnn, vae_decoder.mnn, vae_encoder.mnn, pos_emb.bin, token_emb.bin; config.json is optional.
- Model import and active generation/model-storage move are mutually exclusive; do not claim hot swap of a running UNet or multiple simultaneous loaded models.
- Do not claim that CPU/GPU run times are faster until cold and warm runs with the same scheduler, canvas, phone thermal conditions and preloaded model are compared. Capture first-progress and total time.
- 1440×1080 export remains scaled/cropped; it is not yet an AI-superresolved image.
- Keep signing certificate unchanged. In-place update uses versionCode 3; this branch requires Android unit/resource CI, native unchanged code check and signed APK verification before release.
