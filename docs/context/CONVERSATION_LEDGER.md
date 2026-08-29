# Durable Conversation Ledger

## Policy established by the user
- Treat durable project context as a first-class requirement.
- Every project-related conversation turn, investigation, decision, experiment result, error, correction, and architectural decision should be recorded in the repository so future work does not lose context.
- This ledger is an ongoing project memory, not a substitute for source code or test evidence.

## 2026-08-29 — Semantic Search / MobileCLIP model discovery and audit

### User objective
Reactivate the previously disabled semantic image/text search system in the Android Personal Memory AI project.
Use a MobileCLIP model with a matching text encoder and tokenizer. The user specifically remembers a Hugging Face package containing:
- MobileCLIP image model
- MobileCLIP text encoder/model
- tokenizer
- both model towers in `.tflite` format
The intended deployment pattern is: build the Android application first, then import the full model package on the phone, validate it, and use it locally.

### Current repository state found
Package: `app/src/main/java/com/example/personalmemoryai/semantic/`
Current files include:
- `MobileClipImageEncoder.kt`
- `MobileClipModelManager.kt`
- `SemanticSearchService.kt`
- `TextEncoder.kt`

The current `MobileClipModelManager.kt` only accepts `mobileclip_s2_fp16.tflite` and validates a single TFLite model intended for the image path.
The current `MobileClipImageEncoder.kt` implements image-only inference and normalization.
The current `SemanticSearchService.kt` is image-semantic search only; it explicitly says the text encoder is deferred.
The current `TextEncoder.kt` is only an interface plus `UnavailableTextEncoder`; text inference is not implemented yet.

### Exact Hugging Face candidate located
Repository/model package:
`plainhub/mobileclip-s2-tflite`

Hugging Face page:
https://huggingface.co/plainhub/mobileclip-s2-tflite

Files currently shown in the repository:
- `mobileclip_s2_image.tflite` — remote size 144,120,668 bytes; SHA-256 `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`
- `mobileclip_s2_text.tflite` — remote size 253,874,828 bytes; SHA-256 `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`
- `tokenizer.json` — approximately 1.71 MB
- license shown by Hugging Face: Apache-2.0
- repository model tree identifies base model as `apple/MobileCLIP-S2`

The files were added together in Hugging Face commit `868dc14eb50de4a8347714b019aae242a0778675`, whose verified commit page lists all three files in the same upload.

### Direct links
Model package: https://huggingface.co/plainhub/mobileclip-s2-tflite
Files:
- https://huggingface.co/plainhub/mobileclip-s2-tflite/blob/main/mobileclip_s2_image.tflite
- https://huggingface.co/plainhub/mobileclip-s2-tflite/blob/main/mobileclip_s2_text.tflite
- https://huggingface.co/plainhub/mobileclip-s2-tflite/blob/main/tokenizer.json

### Deep audit findings from public model metadata
1. The package is real and contains the three requested artifacts together.
2. The image tower is 144 MB and the text tower is 254 MB; together the two TFLite files are roughly 398 MB, plus tokenizer metadata.
3. Both TFLite files are stored through Xet/LFS; Hugging Face exposes their remote sizes and SHA-256 values.
4. The repository model tree declares `apple/MobileCLIP-S2` as the base model.
5. The model card README in `plainhub/mobileclip-s2-tflite` is currently empty/minimal, so the third-party package itself does NOT document tensor names, exact tensor shapes/dtypes, preprocessing, tokenizer contract, or runtime examples sufficiently.
6. The official Apple `MobileCLIP-S2` configuration specifies:
   - shared embedding dimension: 512
   - image size: 256 x 256
   - text context length: 77
   - text vocabulary size: 49,408
   - text width/dimension: 512
   - 12 text transformer layers
   - 8 attention heads per layer
   - non-causal text masking in the model config
7. Official Apple inference encodes image and text separately, L2-normalizes both feature vectors, and compares them in the shared embedding space. The official code also exposes model-specific tokenization.
8. Apple’s official tokenizer implementation uses the CLIP-style byte/BPE vocabulary with `<start_of_text>` and `<end_of_text>` special tokens and a default context length of 77. The exact IDs and implementation details must be recovered from the matching tokenizer/package, not guessed.
9. The third-party `tokenizer.json` is a large JSON tokenizer artifact and public rendering exposes its vocabulary content, but the public model card does not provide a clean runtime contract. It must be parsed and validated against the text TFLite model before production use.
10. A second TFLite repository (`anton96vice/mobileclip2_tflite`) exists, but its documented workflow treats tokenizer acquisition separately, so it is not the user’s remembered single-folder package requirement.

### Important limitation of this audit
The web-accessible Hugging Face interface exposes metadata and LFS/Xet hashes for the binary TFLite files but does not provide their internal FlatBuffer tensor tables in text. The local runtime currently lacks TensorFlow/TFLite/FlatBuffers inspection libraries. Therefore this audit CONFIRMS package identity, file integrity metadata, model family, and official architecture configuration, but does NOT yet claim exact binary input/output tensor names, dtypes, quantization parameters, or graph signatures for the two third-party TFLite files.

That binary contract must be obtained by actually downloading the TFLite artifacts into a controlled environment and inspecting the FlatBuffer/interpreter metadata before wiring them into Android.

### Required production validation before accepting the model
The eventual import manager must validate at least:
1. All required files are present.
2. SHA-256 / integrity checks are recorded.
3. Image TFLite tensor inputs/outputs, shape and types are compatible with the Android encoder.
4. Text TFLite tensor inputs/outputs, token ID shape/type, and output embedding are compatible.
5. Tokenizer configuration, vocabulary/BPE data, special tokens, and context length match the text model.
6. Image preprocessing matches the model’s trained contract (256x256 according to official S2 configuration unless binary inspection proves otherwise).
7. Both output embeddings are finite, non-zero, and 512-dimensional according to the official S2 configuration.
8. Cross-modal sanity tests show known matching image/text pairs rank sensibly relative to mismatched pairs.
9. Model version/manifest is persisted so stale embeddings cannot silently mix with a different model/tokenizer version.
10. Only after these checks should the model be marked READY and used for indexing/search.

### Architectural direction agreed
Do NOT build the semantic engine around a guessed model first.
First lock down the exact model package and its real tensor/tokenizer contract, then implement the Android runtime around that contract.

Target architecture:
Image -> MobileCLIP image encoder -> 512-D shared embedding
Text -> exact MobileCLIP tokenizer -> MobileCLIP text encoder -> 512-D shared embedding
Then cosine similarity / ranking for semantic retrieval.

The long-term project direction is a shared AI visual core where semantic embeddings, reverse-image signals, advanced visual analysis, and face embeddings can share decoding/caching/index infrastructure instead of repeatedly decoding the same image.

### User’s requested future workflow
- Import the complete model package after app installation, as done previously with MobileCLIP.
- Rebuild/rework the semantic system rather than keeping the current image-only implementation as the final design.
- Preserve the previous project context and all subsequent investigation results in this repository ledger.
- The user explicitly requires project conversation context to be recorded in this GitHub ledger as an ongoing rule.

### Search evidence captured on 2026-08-29
- Hugging Face `plainhub/mobileclip-s2-tflite` tree lists exactly `mobileclip_s2_image.tflite`, `mobileclip_s2_text.tflite`, and `tokenizer.json` together.
- Hugging Face file pages expose the remote SHA-256 values above for both TFLite files.
- Hugging Face model tree states the base model is `apple/MobileCLIP-S2`.
- Official Apple MobileCLIP-S2 configuration exposes 512-D embeddings, 256x256 image input, 77-token text context, and 49,408 vocabulary size.
- Official Apple MobileCLIP code demonstrates paired image/text encoders with normalized feature comparison in a shared embedding space.

### Current conclusion
This is the strongest match to the user’s remembered full MobileCLIP-S2 TFLite package. It is suitable as the leading candidate, but it is NOT yet production-approved until the two binary TFLite graphs and tokenizer are downloaded and mechanically validated against the Android runtime contract.
