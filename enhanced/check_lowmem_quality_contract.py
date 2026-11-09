#!/usr/bin/env python3
"""Static regression gate for the phone-verified SD1.5 lowmem GPU data path.

Alpha.7 rendered the fixed 640x640 reference image. Alpha.8 introduced
persistent host encoder tensors and moved timestep uploads; the same seed
then produced saturated color noise. Restore the alpha.7 call ordering and
make it impossible to silently reintroduce that speculative optimization.

This source-only check is NOT a pixel-quality or real-device GPU test.
"""
from pathlib import Path

source = Path("app/src/main/cpp/src/PipelineSd15Cpu.hpp").read_text(encoding="utf-8")
start = source.index("    if (req.mnn_sequential_cfg && !skip_uncond) {")
end = source.index("    const size_t output_count = (skip_uncond ? 1 : 2) * latent_count;", start)
step = source[start:end]
needed = (
    "run_half(0, cond.negHidden());",
    "run_half(1, cond.posHidden());",
    "memcpy(ts_nchw_tensor->host<int>(), &timestep, sizeof(int));",
    "memcpy(enc_nchw_tensor->host<float>(), conditioning,",
    "samp->copyFromHostTensor(samp_nchw_tensor);",
    "ts->copyFromHostTensor(ts_nchw_tensor);",
    "enc->copyFromHostTensor(enc_nchw_tensor);",
    'unet_interpreter_->runSession(unet_session_)',
)
for token in needed:
    if token not in step:
        raise SystemExit(f"LOWMEM QUALITY REGRESSION: required call missing: {token}")
for broken in ("unet_encoder_negative_host_", "unet_encoder_positive_host_"):
    if broken in source:
        raise SystemExit(f"LOWMEM QUALITY REGRESSION: unsafe persistent staging: {broken}")
if "_serial_cfg_quality_recovery" not in source:
    raise SystemExit("LOWMEM QUALITY REGRESSION: MNN OpenCL cache must be isolated")
if step.index("samp->copyFromHostTensor") > step.index("enc->copyFromHostTensor"):
    raise SystemExit("LOWMEM QUALITY REGRESSION: unexpected MNN input ordering")
for token in (
    "Session_Release",
    "Session_Resize_Defer",
    "unet_cache_before_bytes_ = cacheFileSize",
    "unet_cache_after_bytes_ = cacheFileSize",
):
    if token not in source:
        raise SystemExit(f"SAFE GPU CACHE REGRESSION: missing session/cache guard: {token}")
if source.index("Session_Release") > source.index("Session_Resize_Defer"):
    raise SystemExit("SAFE GPU CACHE REGRESSION: release mode must be set first")
pipeline = Path("app/src/main/cpp/src/Pipeline.hpp").read_text(encoding="utf-8")
service = Path("app/src/main/java/io/github/xororz/localdream/service/BackgroundGenerationService.kt").read_text(encoding="utf-8")
for token in (
    "unet_step_times_ms.push_back(call_ms);",
    "cfg_half_times_ms = cfgHalfTimesMs();",
    "scheduler_step_total_ms +=",
):
    if token not in pipeline:
        raise SystemExit(f"UNET TIMING REGRESSION: missing pipeline field {token}")
for token in (
    "cfg_negative_total_ms_ += pass_ms",
    "cfg_positive_total_ms_ += pass_ms",
    "run_half(0, cond.negHidden());",
    "run_half(1, cond.posHidden());",
):
    if token not in source:
        raise SystemExit(f"UNET TIMING REGRESSION: missing pass evidence {token}")
if "nativeUnetStepTimesMs" not in service:
    raise SystemExit("UNET TIMING REGRESSION: Android diagnostic collection absent")
print("PASS: read-only UNet step and CFG split timings wired; math inputs unchanged")
print("PASS: release-mode session and cache-file presence diagnostics wired")
print("PASS: alpha.7 lowmem input sequencing restored; separate tuning cache")
print("NOTE: real device same-seed image comparison is still required")
