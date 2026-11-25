# Ordinary surface repair quality baseline

This independent tool is intended for isolated marks on non-person surfaces.
It is a classical interpolation tool, not a generative neural model.

The quality regression tests use procedural flat, gradient and checker-pattern
backgrounds with known ground truth. They report mean absolute RGB error
inside the damaged region and require exact equality outside the selected
region. Pattern reconstruction is diagnostic rather than a claimed success.
Passing these tests does not establish real-photo quality or phone performance.

A future dedicated scene-background reconstruction experiment must be
evaluated separately on non-person scenes with known reference images,
including complex texture, repeating patterns, edges and lighting gradients.
Compare masked-region quality, outside-region preservation, peak memory,
latency, download size and offline operation on the target Android device.
Do not label an untested model as supported or a classical algorithm as AI.
No new model, runtime dependency or generative editor is introduced here.

Research context: Suvorov et al., Resolution-Robust Large Mask Inpainting
With Fourier Convolutions, WACV 2022:
https://openaccess.thecvf.com/content/WACV2022/html/Suvorov_Resolution-Robust_Large_Mask_Inpainting_With_Fourier_Convolutions_WACV_2022_paper.html
