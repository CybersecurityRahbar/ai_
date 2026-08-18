# Semantic model

The MobileCLIP-S2 FP16 TFLite model is intentionally not stored in this Git repository because it is a large binary.

At runtime the application downloads the validated model into:

`<app-private-files>/models/semantic/mobileclip_s2_fp16.tflite`

The downloader and persistent local storage are implemented by `MobileClipModelManager`.

Text Encoder support is intentionally reserved through `TextEncoder.kt` and can be added later without changing the image embedding database format.
