# MNN-only Android native backend (experimental)

This directory contains an **independent native executable target**, not a modified Qualcomm QNN SDK. It is derived from the original Local Dream public source and uses the same Local Dream SD1.5 MNN pipeline.

**Intent:** first make SD1.5 CPU/OpenCL genuinely work on a MediaTek Dimensity device without requiring or distributing proprietary Snapdragon NPU runtime blobs.

## Referenced projects

- [Original Local Dream](https://github.com/xororz/local-dream) — model directory layout, prompts, native generation pipeline, local HTTP/SSE and Android app
- [Alibaba MNN Diffusion](https://github.com/alibaba/MNN/tree/master/transformers/diffusion) — Android MNN and OpenCL build/low-memory engine options; not a drop-in compatible model format
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) — possible separate future GGUF/Vulkan backend; no assertion that these models fit 12GB RAM
- [MNN Stable Diffusion example](https://github.com/wangzhaode/mnn-stable-diffusion) — reference only, with different resource layout

## Planned / available compatibility

| Native feature | Source present | Compile verified | Phone verified |
|---|---|---|---|
| SD1.5 MNN CPU | Yes | Pending native CI | No |
| SD1.5 MNN OpenCL | Yes | Pending native CI | No |
| /health, /tokenize, /generate SSE | Yes | Pending native CI | No |
| SD1.5 img2img/inpaint | Inherited code | Pending native CI | No |
| SDXL MNN | No (existing class inherits QNN code) | No | No |
| Qualcomm QNN / DiT | Intentionally excluded | Not applicable | Not applicable |
| MNN upscaler | Currently returns HTTP 501 | No | No |
| Wi-Fi remote host | Disabled: original host transport lacks authentication | Not applicable | No |
| Real AI video | Not implemented | No | No |

### Existing Local Dream model layout

This backend expects exactly the original Local Dream **SD1.5 CPU** model package directory, including `tokenizer.json`, `clip_v2.mnn`, `unet.mnn`, `vae_decoder.mnn`, `pos_emb.bin` and `token_emb.bin`. A VAE encoder is required unless `--no_img2img` is passed. These files can potentially be reused from public `Download/LocalDream` storage, without re-downloading, but cannot be read from the original differently-signed app's private data directory.

MNN official Diffusion demos use a different package schema and tokenizer. **Do not download official MNN diffusion models and assume they load directly in the Local Dream package layout.**

### API compatibility

The Android app's native service expects a local executable under `nativeLibraryDir/libstable_diffusion_core.so`, listening on `127.0.0.1:8081`. The experimental source implements `--type sd15cpu`, `--model_dir`, `--no_img2img`, `--use_v_pred`, `--port` and HTTP `/health`, `/tokenize`, and `/generate` using Local Dream's progress and complete SSE events. Request body is bounded at 64 MiB; other CLI types return explicit errors.

### Compile and shipment gates

1. Build the native target with the pinned open-source submodules and the Android NDK; do not fetch QNN SDK.
2. The native CI artifact is explicitly marked **EXPERIMENTAL—not an APK** and cannot be presented as a working generator.
3. Integrate into APK only after resolving native compile errors; check ABI and runtime link dependencies.
4. Install side by side with upstream v2.8.1 using the separate enhanced application ID and user-retained fixed signing cert.
5. Perform phone tests: 512x512 prompt generation, OpenCL fallback, model reuse, RSS/time/thermal metrics, image export and failure recoverability.
6. Release workflow must verify real native binary, original license notices, and matching signing certificate before distributing an APK.

For model-format and chipset gates, see [P0 GitHub issue](https://github.com/initial2024/Local-Dream-Android-Enhanced-Edition/issues/1).
