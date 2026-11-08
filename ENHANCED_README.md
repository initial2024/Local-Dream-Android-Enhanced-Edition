# Local Dream Enhanced · Android (unofficial)

**Status:** experimental, non-commercial, independent Android edition. The original project is [Local Dream by xororz](https://github.com/xororz/local-dream). This is not an official release.

## Provenance and rights

- Upstream: `xororz/local-dream`, tag `v3.0.0-alpha.7`, pinned commit `2f1f3ba186eeca2a52268540108b3ee6e2296d17` (2026-11-01 release).
- Retain upstream **LICENSE**, its original attribution, notices, copyright and third-party license terms. The upstream project is licensed **CC BY-NC 4.0**; no commercial distribution is authorized merely by this fork.
- Original author is **xororz**. Enhancement is maintained separately by **initial2024**.
- Only the upstream **basic** Android product flavor is targeted for the enhanced release; it omits the optional built-in output checker. Do not confuse that product variant with an authorization to violate platform policy.
- This repository must not redistribute model weights, Qualcomm SDK blobs or other third-party assets without their respective permissions.

## Target device and constraints

Primary test baseline: **MediaTek Dimensity 9400+, 12 GiB physical RAM, Android 16, arm64-v8a**. It has **no Qualcomm Hexagon NPU**. Prefer applicable CPU/GPU pathways; never claim Snapdragon QNN hardware acceleration on this device. A 9–11 GB model file does not imply it can load or run in 12 GB RAM.

## First enhancement set

1. Separate application ID `io.github.initial2024.localdream.enhanced`; safe side-by-side install with original v2.8.1.
2. Local DiT model HD 4:3 preset: **1440×1088 inference canvas** (16-aligned), with **separate center-cropped 1440×1080 image export**. Native 1440×1080 is not claimed. Ordinary save/history retain the unmodified original.
3. Explicit device-memory warning for high-resolution generation; backend/weights remain the limiting factor.
4. Restore HD preset after screen re-entry; add unit tests for resolution math.
5. Fixed-release-signing design: never publish an APK as upgradable unless signed with the *same retained private key* as previous enhanced releases.

**Not yet delivered:** complete APK, working end-to-end HD benchmark on this phone, one-tap global-history auto-regeneration, output size targeting (e.g. 1 MB), real local video diffusion. These require implementation/testing beyond the first source overlay.

## Existing v2.8.1 installation and model data

Original package `io.github.xororz.localdream` stays installed. Different signature cannot update that package; a unique application ID avoids destructive replacement. Private app directories remain private. External `Download/LocalDream` storage can be reused only where Android permissions and model formats allow; if not, user-directed model import or re-download is required. Never clear old app data automatically.

## Native build caveat

Upstream's Android CMake/build scripts require the Qualcomm AI Runtime SDK and multiple Git submodules even when the target device uses a non-QNN pathway. CI must not fake a signed APK or download third-party proprietary SDK without permission. See [docs/BUILD_SIGNING.md](docs/BUILD_SIGNING.md).

## Future

See [docs/VIDEO_ROADMAP.md](docs/VIDEO_ROADMAP.md) for honest video-generator backend and memory gates, not a placeholder video button.

## Development

The bootstrap workflow pins and merges the upstream tag, preserving its code, native gitlinks, original README and license. The deterministic `enhanced/apply_overlay.py` then makes enhancement-specific changes. This fork has independent versioning and signing.
