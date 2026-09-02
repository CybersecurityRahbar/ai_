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
The semantic subsystem was upgraded from an image-only placeholder to a two-tower runtime.

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
`MainActivity.kt` stages the existing import button for the two-tower model import and routes the existing text search button to MobileCLIP Text→Image when the Text TFLite is installed; otherwise the legacy OCR/object keyword path remains available.

### Documentation
`app/src/main/assets/models/semantic/README.md` documents the two separate TFLite towers, their contracts, the production tokenizer decision, and the offline runtime flow.

## 2026-09-01 — Android build and tokenizer parity investigation
### Build result
Android Build run `#244` on commit `cee4935a60c15603b8eaaec8350d60ba571d99a8` succeeded. `:app:assembleDebug` completed and `personal-memory-ai-debug-apk` artifact was uploaded successfully.

The prior compilation failure was caused by positional construction of `EmbeddingEntity`; it was corrected to named schema fields (`ownerType`, `ownerId`, `vector`, `dimension`, `modelName`, `modelVersion`, `normalized`).

### Initial Android tokenizer parity failure
The original Instrumentation parity workflow used an x86_64 emulator without KVM and generated large QEMU/ADB logs. This was correctly identified as an unsuitable environment for a pure tokenizer algorithm test.

The parity workflow was changed to a JVM Unit Test using the same production `vocab.json` and `merges.txt`, eliminating emulator/ADB dependency.

The first JVM run (`MobileCLIP Android Tokenizer Parity` run `#7`, job `99894386753`, workflow run `33519360557`) reached the actual test but failed before tokenization because `OpenClipTokenizer.kt` used `org.json.JSONObject.length()`. Android's mocked `org.json` implementation is not callable in a local JVM test. The compact CI summary showed exactly:
`java.lang.RuntimeException: Method length in org.json.JSONObject not mocked.`

### 2026-09-02 — Current tokenizer fix
`OpenClipTokenizer.kt` was corrected in commit `17015ec9d16293b3ac2e649e5f565e1f33bd3e2a`:
- removed the JVM-sensitive `org.json.JSONObject` dependency from the production tokenizer path;
- added a self-contained flat JSON string→integer parser suitable for both Android and JVM tests;
- retained the same 49,408 vocabulary contract and SOT/EOT assertions;
- hardened merges parsing to accept generic whitespace separators.

The tokenizer already uses numeric-run matching (`[\\p{N}]+`) and CLIP-style text spans.

## Current full-review findings
- The immediate JVM blocker is now fixed in the code, but the new commit has not yet produced a completed CI result in the available Actions listing at the time of this entry.
- `AppleMobileClipImageEncoder` has been reviewed for tensor layout handling, 256×256 preprocessing, RGB packing and 512-D normalization; it correctly supports both NHWC and NCHW input tensors while requiring the verified spatial contract.
- `MobileClipTextModelManager` has been reviewed for atomic import via a temporary file, minimum-size guard, TFLite tensor-contract validation, finite/non-zero health inference, and cleanup on failure.
- `MobileClipTextEncoder` has been reviewed for `[1,77]` INT64 input, direct native-order buffer construction, one-output FLOAT32 handling, 512-D normalization, finite-value checks and lifecycle cleanup.
- `SemanticSearchService` has been reviewed for separate Image/Text managers, compatibility filtering by model name/version/dimension, finite-vector validation, cosine ranking, progress/error isolation during image indexing, and backward-compatible model import/search APIs.
- Remaining correctness gate: prove Kotlin tokenizer token-for-token against all 19 Apple golden cases. Do not declare semantic production parity until that passes.

## Next action
1. Verify CI result for commit `17015ec9d16293b3ac2e649e5f565e1f33bd3e2a` and fix any remaining tokenizer parity failure.
2. Add/strengthen normalization edge-case golden vectors if the 19 canonical cases pass.
3. Run a real MobileCLIP-S2 Text TFLite smoke inference using the verified 77-token IDs and assert finite/non-zero normalized 512-D output.
4. Run controlled end-to-end Text→Image ranking against the existing image embeddings and compare the ranking against the Apple/PyTorch oracle.
5. Only after all gates pass, mark MobileCLIP semantic search production-ready.
