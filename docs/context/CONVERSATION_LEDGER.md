# Durable Conversation Ledger

## Policy established by the user
- Treat durable project context as a first-class requirement.
- Every project-related conversation turn, investigation, decision, experiment result, error, correction, and architectural decision should be recorded in the repository so future work does not lose context.
- This ledger is an ongoing project memory, not a substitute for source code or test evidence.

## 2026-08-29 — Semantic Search / MobileCLIP model discovery

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
- `mobileclip_s2_image.tflite` — approximately 144 MB; remote size 144,120,668 bytes; SHA-256 `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`
- `mobileclip_s2_text.tflite` — approximately 254 MB; remote size 253,874,828 bytes; SHA-256 `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`
- `tokenizer.json` — approximately 1.71 MB
- license shown by Hugging Face: Apache-2.0
- repository model tree identifies base model as `apple/MobileCLIP-S2`

The files were added together in Hugging Face commit `868dc14eb50de4a8347714b019aae242a0778675`, whose verified commit page lists all three files in the same upload.

### Important verification caveat
The `plainhub/mobileclip-s2-tflite` repository's README is currently empty/minimal, so its existence of the three artifacts is verified, but the runtime tensor contract, tokenizer exactness, preprocessing contract, and cross-modal embedding compatibility must still be validated directly before we make it the production model package.

### Related alternative found
`anton96vice/mobileclip2_tflite` contains MobileCLIP TFLite model variants and discusses image-text retrieval/semantic search, but its model card explicitly says the tokenizer must be obtained separately and matched to the model. Therefore it is NOT the user's remembered single-folder/full-package candidate and should not be preferred for this requirement.

### Official reference
`apple/MobileCLIP-S2` is the official source family. Apple documents a CLIP-style pair of image and text encoders and a model-specific tokenizer. The official example normalizes image and text features and compares them in the shared embedding space.

### Architectural direction agreed
Do NOT build the semantic engine around a guessed model first.
First lock down the exact model package and its real tensor/tokenizer contract, then implement the Android runtime around that contract.

Target architecture:
Image -> MobileCLIP image encoder -> shared embedding
Text -> exact MobileCLIP tokenizer -> MobileCLIP text encoder -> shared embedding
Then cosine similarity / ranking for semantic retrieval.

The long-term project direction is a shared AI visual core where semantic embeddings, reverse-image signals, advanced visual analysis, and face embeddings can share decoding/caching/index infrastructure instead of repeatedly decoding the same image.

### Validation requirements before production use
The eventual import manager must validate at least:
1. All required files are present.
2. SHA-256 / integrity checks are recorded.
3. Image TFLite tensor inputs/outputs, shape and types are compatible with the Android encoder.
4. Text TFLite tensor inputs/outputs, token ID shape/type, and output embedding are compatible.
5. Tokenizer configuration, vocabulary/BPE data, special tokens, and context length match the text model.
6. Image preprocessing matches the model's trained contract.
7. Both output embeddings are finite, non-zero, and L2-normalized.
8. Cross-modal sanity tests show image/text pairs produce sensible relative similarity (not just that inference runs).
9. Model version/manifest is persisted so stale embeddings cannot silently mix with a different model/tokenizer version.
10. Only after these checks should the model be marked READY and used for indexing/search.

### User's requested future workflow
- Import the complete model package after app installation, as done previously with MobileCLIP.
- Rebuild/rework the semantic system rather than keeping the current image-only implementation as the final design.
- Preserve the previous project context and all subsequent investigation results in this repository ledger.

### Search evidence captured on 2026-08-29
- Hugging Face `plainhub/mobileclip-s2-tflite` tree lists exactly `mobileclip_s2_image.tflite`, `mobileclip_s2_text.tflite`, and `tokenizer.json` together.
- Hugging Face file pages expose the remote SHA-256 values above for both TFLite files.
- Hugging Face model tree states the base model is `apple/MobileCLIP-S2`.
- Official Apple MobileCLIP-S2 model documentation demonstrates paired image/text encoders with a model-specific tokenizer and normalized feature comparison.
