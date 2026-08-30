# Durable Conversation Ledger

## User policy
This file is the single ongoing durable conversation ledger for the project. Each substantive project-related turn, investigation, decision, experiment result, error, correction, architectural decision, and concrete repository change must be recorded here with date/time and a clear next action. The full historical conversation remains in the root `session-1788115451049.md`; this ledger is the concise operational memory and must never invent unavailable history.

## Project baseline
Personal Memory AI is a local-first Android intelligence/search system. Major established subsystems are:
- Local Reverse Image Search: Haar, pHash, dHash, HSV, Sobel, AKAZE/RANSAC, SIFT/RANSAC. Its candidate and verification pipeline is protected from regressions.
- Advanced Visual Intelligence: Advanced feature extractor V2 plus independent Fusion V4. It is intentionally separate from Reverse Image Search.
- Shared local indexing/import: durable WorkManager/foreground processing, shared image decode, per-item failure isolation, MediaStore aggregate `VOLUME_EXTERNAL` enumeration, and scalable Folder/Tree import for very large selections.
- Face/Vision: MediaPipe detection/landmarks, quality/pose/shape evidence, MobileFaceNet, optional FaceNet-512, and initial person clustering. The new Face Identity & Visual Memory V1 architecture is specified but not yet a completed person-level multi-prototype engine.
- Semantic Search: MobileCLIP-S2 Image↔Text is the active integration track.

## MobileCLIP-S2 verified provenance
Candidate package: `plainhub/mobileclip-s2-tflite`
Pinned HF revision: `868dc14eb50de4a8347714b019aae242a0778675`
Image TFLite SHA-256: `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`
Text TFLite SHA-256: `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`
Third-party tokenizer SHA-256: `166a5e8118fe3aa2f60a1877925a4dd5168ce93c58dd5efabc32a9a9eb8335ec`
Official Apple MobileCLIP source commit used by the oracle: `aecfb5453d022e9deff12f81a150ea8f35194baa`
Official Apple `MobileCLIP-S2-OpenCLIP` tokenizer revision: `a406a1bd0b882b27509e608f3cb199de52010c4d`

Verified model contracts: shared embedding dimension 512; image input 1×3×256×256 FLOAT32 in the oracle; text input 1×77 INT64; text/image output 512-D FLOAT32; context length 77; vocabulary size 49,408; SOT 49,406; EOT 49,407. Apple’s OpenCLIP reference uses shortest-side resize to 256, center crop to 256×256, raw RGB float input with mean 0/std 1, and the CLIP/OpenCLIP BPE tokenizer.

## 2026-08-30 — Deep Oracle V1 result
The successful Apple-vs-TFLite oracle established:
- Image Apple vs Image TFLite normalized cosine: `1.000000000`.
- Text TFLite matches Apple PyTorch at approximately 1e-6 scale when fed Apple’s exact token IDs (for example `a diagram` max absolute difference `1.90735e-06`; `a dog` `6.67572e-06`; `a cat` `2.86102e-06`).
- Using the third-party tokenizer produced different text embeddings and wrong cross-modal top-1 ranking.
Conclusion: neither TFLite binary should be replaced. The remaining problem was tokenizer behavior.

## 2026-08-30 — Tokenizer differential investigation
V3/V4/V6/V7 progressively isolated the tokenizer issue.

Final V7 result from GitHub Actions:
- Third-party `plainhub/tokenizer.json`: `18 / 19` test cases divergent from Apple runtime.
- All `49,408 / 49,408` vocabulary token strings are common.
- All `49,408 / 49,408` token→ID mappings are identical.
- Zero remapped, Apple-only, or third-party-only tokens.
- Representative segmentation differences: Apple `a</w>` vs third-party `adi`, Apple `two</w>` vs `two`, Apple `hello</w>` vs `hello`.
- Official Apple tokenizer JSON: `0 / 19` divergent cases.
- Production verdict: `USE_OFFICIAL_APPLE_JSON_OR_FAITHFUL_OPENCLIP_IMPLEMENTATION`.

Artifact: `mobileclip-s2-tokenizer-differential-report`, ID `9738443724`, digest `0fd380ffc72e9416a0c9f0d0bf9df31c0fb435bf8dd76d11d00417cc5e903e08`.

Therefore the production decision is locked: keep both TFLite towers; do not use the third-party tokenizer JSON as the Android production tokenizer; reproduce Apple/OpenCLIP tokenizer semantics locally.

## 2026-08-30 — Android semantic implementation started
The semantic subsystem was upgraded from an image-only placeholder to a two-tower runtime:

### Tokenizer
`app/src/main/java/com/example/personalmemoryai/semantic/OpenClipTokenizer.kt`
- Loads official Apple/OpenCLIP `vocab.json` and `merges.txt` as application assets.
- Uses CLIP-style regex tokenization, byte-to-unicode conversion, BPE ranks, SOT/EOT, 77-token padding/truncation.
- Assets are prepared by Gradle from pinned Apple revision `a406a1bd0b882b27509e608f3cb199de52010c4d` so runtime remains offline.

### Text model
`MobileClipTextModelManager.kt`
- Imports `mobileclip_s2_text.tflite` into private app storage.
- Validates INT64 `[1,77]` input and FLOAT32 512-D output.
- Performs finite/non-zero health validation before activation.

`MobileClipTextEncoder.kt`
- Runs the verified Text TFLite tower with tokenizer-produced INT64 IDs.
- Normalizes the 512-D output.

### Image model
`MobileClipModelManager.kt`
- Canonical image file is now `mobileclip_s2_image.tflite`; legacy `mobileclip_s2_fp16.tflite` remains recognized for compatibility.

`AppleMobileClipImageEncoder.kt`
- Added as the production semantic image path.
- Matches Apple/OpenCLIP preprocessing: shortest-side resize, centered 256×256 crop, RGB `[0,1]`, no CLIP mean/std normalization.

### Search service
`SemanticSearchService.kt`
- Owns separate image and text model managers.
- Retains the existing image-import API for compatibility.
- Adds explicit `importImageModel()` and `importTextModel()`.
- Adds `ensureTextModel()`, text-model status/size accessors, and `searchByText()`.
- Text search performs `text query → OpenCLIP tokenizer → Text TFLite → normalized 512-D → cosine against persisted IMAGE embeddings`.
- Existing image→image semantic search remains available.
- No Room schema migration is required for text queries because only query embeddings are computed transiently.

### Main UI behavior
`MainActivity.kt` now stages the existing import button:
1. first import selects/validates the Image TFLite tower;
2. pressing the same button again imports the Text TFLite tower;
3. when Text TFLite is installed, the existing text search button automatically performs MobileCLIP Text→Image semantic search; otherwise it preserves the legacy OCR/object keyword search.
This avoids adding a parallel UI flow while keeping backward compatibility.

### Documentation
`app/src/main/assets/models/semantic/README.md` now documents the two separate TFLite towers, their contracts, the production tokenizer decision, and the offline runtime flow.

## Important current limitation
The Android OpenCLIP tokenizer implementation is intentionally a faithful Kotlin implementation using official Apple vocab/merges, but it has not yet been proven token-for-token against Apple runtime on-device. In particular, Android does not provide Python `ftfy`; the current implementation uses Android HTML decoding, whitespace normalization, NFC normalization and lowercasing. A dedicated Kotlin golden-parity test still needs to be added before calling Android semantic search production-equivalent.

## Current CI / verification gate
The Android build is required to validate the newly added Kotlin classes and generated tokenizer assets. Do not claim production readiness until the build passes and the Android tokenizer receives golden parity coverage. After that:
1. run/review Android build;
2. add deterministic tokenizer golden tests based on the verified Apple IDs;
3. perform Android Text TFLite smoke test;
4. perform end-to-end Text→Image ranking against a controlled corpus;
5. only then enable the semantic subsystem as production-ready.

## User model-download state
The user confirmed downloading both `mobileclip_s2_image.tflite` and `mobileclip_s2_text.tflite` to the phone. They should be kept; no binary replacement is currently justified.

## 2026-08-30 — Current continuation
### User request
Continue the project after the tokenizer parity decision and proceed with implementation.

### Assistant work
Inspected the current Android semantic stack and implemented the Image+Text MobileCLIP runtime, official OpenCLIP tokenizer asset preparation, text-to-image semantic retrieval, staged model import behavior, and Apple-compatible image preprocessing. The old `TextEncoder` placeholder contract was updated to describe the installed implementation. The semantic README was synchronized with the verified two-tower architecture.

### Concrete commits/work
- `4be11abc8e627a3fcadd9048708650088c28b0b7` — initial OpenCLIP tokenizer implementation.
- `864307b44fea75bd6cbab775bd932a889733176f` — MobileCLIP-S2 text model manager.
- `00c87d1a5a6f72ad4cb9b67cff409cb76dd460db` — MobileCLIP-S2 text encoder.
- `143ced1097bc87120337538c1d1d00a181fe6737` — canonical image model manager.
- `2298d5907c9f003e2b07b3f9f38db11c8f3fece0` — Apple-compatible semantic image encoder.
- `9f09dfdb585861db4c217070a3b8559045e4b367` — semantic service wired to Image+Text towers and Text→Image search.
- `47c8479b970a653a558d1523fc9f5461ce8fac9d` — staged MainActivity import/search behavior.
- `7745dbb44fb878ca7e941f5b328a36947a886295` — pinned Apple tokenizer asset preparation in Gradle.
- `0bd436461b6849c66944c6173bc6b9c19b0e3111` — semantic model documentation update.
- `f93f96634f0c2c0e5aa226944081083713a84eb7` — TextEncoder contract documentation update.

### Next action
Validate the current main branch with Android Build. Any compile/runtime error discovered there must be fixed before adding the tokenizer golden test and declaring the semantic path production-ready.
