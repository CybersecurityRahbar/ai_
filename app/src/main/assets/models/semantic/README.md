# Semantic model

The MobileCLIP-S2 FP16 TFLite model is intentionally not stored in this Git repository because it is a large binary.

At runtime the user imports the validated `.tflite` model from device storage. The application copies it into private app storage at:

`<app-private-files>/models/semantic/mobileclip_s2_fp16.tflite`

The model is **not downloaded from the internet**. `MobileClipModelManager` owns the import, validation, persistent local copy, and deletion lifecycle.

Text Encoder support is intentionally reserved through `TextEncoder.kt` and can be added later without changing the image embedding database format.
