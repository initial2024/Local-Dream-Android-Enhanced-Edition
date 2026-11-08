# Future local video generation (design only, not implemented)

## Decision

Video must be a separate optional backend behind a capability API; do not force multi-GB video models into the image-only APK. On a Dimensity 9400+ with 12 GiB physical RAM, large text-to-video models are not assumed feasible merely because image generation works.

## Proposed interface (not production code)

- Input: image reference (for image-to-video), optional prompt and negative prompt, model fingerprint, seed, output width/height, frame count, fps, frame conditioning and motion strength.
- Capability response: supported runtime/quantization, exact model checksum, maximum safe width/height/frame budget, estimated peak RAM and free-space requirement, resumable download support.
- Controls: explicit user consent; separate model downloads; cancel; bounded generation queue; progress and model cleanup after use.
- Output: locally muxed MP4 via Android MediaCodec/MediaMuxer or a vetted encoder implementation; no silent cloud upload.
- Failure: fail closed on insufficient available RAM, storage, thermal pressure or missing backend instead of OOM/restart loops.

## Roadmap

P0: image generation correctness, HD export, stable signatures, real on-device metrics.
P1: history recipe preservation and accurate reproduce action; CPU/GPU model profiling and thermal throttling.
P2: optional 2–4 second low-resolution image-to-video prototype, then frame interpolation and encoding.
P3: conditional higher resolution/frame count only after repeatable device test evidence.

No video generation functionality is claimed in current APK/source overlay.
