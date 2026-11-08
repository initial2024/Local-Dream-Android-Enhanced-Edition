# Experimental SD1.5 MNN native high-resolution diffusion (0.1.0-alpha.2)

The original fixed 512×512 ceiling was a **Local Dream client/server policy**, not a measured hardware maximum. In alpha.2, the MNN SD1.5 backend can attempt larger native UNet/VAE tensor shapes. A successful app build does **not** prove that the user's Dimensity 9400+ and Mali GPU can complete those shapes without allocation failure.

## Native output versus HD export

- **Native**: selected dimensions drive the UNet latent tensor and the VAE decoder. On SD1.5, 1024×768 is 786,432 inference pixels (3× the 512×512 pixel count).
- **Export**: resizing/cropping a 512×512 bitmap to 1440×1080 creates a larger file, not new diffusion detail. Alpha.2 keeps this feature separately and labels it accurately.
- **AI superresolution**: requires an actual compatible 2×/4× model and a separately validated MNN inference path. This build still has no validated AI upscaler; don't confuse offline pixel interpolation with detail recovery.
- **SD1.5 itself was primarily trained near 512×512**. At larger sizes it may duplicate subjects or change composition. A true 1024×768 PNG does not automatically have better perceptual quality than a 512px image.

## Bounded dimensions

Model-native presets: 512×512, 640×640, 768×768, 1024×768 (landscape), 768×1024 (portrait), 1024×1024. Square slider supports the same 64-pixel grid from 128 to 1024. Both Android and C++ reject sides outside 128–1024, non-64-aligned sizes and any request over 1,048,576 native pixels. Server rejects invalid sizes **before** latent noise allocations. The new OpenCL cache names distinguish 1024×768 from 1024×1024.

1440×1080 is **not** enabled as native SD1.5 inference: its pixel count exceeds the initial safe budget and native MNN/GPU compatibility remains unverified.

## Suggested real-device test order

1. Preserve the original v2.8.1 app and existing model files. Update enhanced app with the same fixed signing certificate (versionCode 2).
2. On exactly the same `absoluterealitycpu` MNN weights, select 512×512, CPU 4 threads, 20 steps, CFG 7, Seed 42, **DPM++ 2M with no Karras**, 1 image. Keep the source prompt and negative prompt unchanged. Save the real output.
3. Repeat on OpenCL; separately record **first** run after app/backend cold start and at least three **warm** runs. The prior reported 2m34 CPU, 1m46 GPU and 1m10 GPU are not directly matched because one screenshot used **DPM++ 2M Karras**.
4. Try **640×640** on OpenCL. If successful and memory/temperature permit, try 768×768, then 1024×768, then 1024×1024. Keep steps fixed and repeat each 3 times.
5. For each case verify the **actual saved image file dimensions**. A display screenshot or the 1440 export button is not proof that the diffusion size changed. Check history metadata, OpenCL driver errors and success/failure handling.
6. Record wall-clock model loading, generation-only time, RAM/high-water RSS, free memory, thermals, power mode, and whether the model was loaded on this run. Use the same scheduler in all comparison cases.
7. If OpenCL kernel allocation, OOM, thermal throttling, process death, corruption or unexpected restarts occur: switch back to 512×512 or CPU. Do not delete downloaded model files. Do not claim a performance regression without controlling the sampler and cold/warm state.

## Shipping gate

Alpha.2 only qualifies as a **signed experimental APK** after Kotlin tests, arm64 native compilation and GitHub signing/package gates. It is not production-stable until real phone tests demonstrate valid native 640/768/1024 outputs. The native backend does not currently include a validated SDXL/FLUX/DiT or AI upscaler on MediaTek.
