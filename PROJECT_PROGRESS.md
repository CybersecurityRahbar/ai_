# Personal Memory AI — Project Progress Log

## 2026-08-26 — Advanced Visual Intelligence V1 + scalable shared indexing implementation

### User-mandated architecture

The new **Advanced Visual Intelligence** is a distinct top-level section in the existing `Intelligence Command Center`.

- Own Activity/screen.
- Own visible action in `IntelligenceHomeActivity`.
- Same PMAI visual language: backgrounds, panels, buttons, spacing, colors.
- Not a hidden mode inside `ReverseImageSearchActivity`.
- Not a second Android application and has no launcher entry. `IntelligenceHomeActivity` remains the sole `MAIN/LAUNCHER`.
- Existing Reverse Image Search remains a separate screen and retains its existing search stack.

### Shared corpus / independent feature indices

The user requires that the same images are not fetched/copied/decoded twice merely because both sections need them.

Architecture:

`Shared local image corpus -> one durable local copy -> one decode/normalization pass -> independent feature indices`

Independent indices:

1. Existing Reverse Image:
   - Haar/Wavelet
   - Classical V4
   - persisted AKAZE descriptors
   - existing tables/DAOs
2. Advanced Visual Intelligence:
   - `advanced_visual_fingerprints`
   - engine version `ADVANCED-VISUAL-CLASSICAL-V1`

`ReverseImageSearchService.buildIndex()` computes Haar + Classical V4 + Advanced V1 from the **same decoded Bitmap** and stores each feature family separately.

### Advanced Visual Intelligence V1

`AdvancedVisualFingerprintEngine` currently produces deterministic, non-neural signals:

- 16x16 multi-scale grayscale structural map;
- RGB/color moments and saturation statistics;
- 256-bin LBP texture histogram;
- 24-bin gradient-orientation histogram weighted by magnitude;
- 8x8 spatial layout/edge signature;
- normalized grayscale entropy;
- aspect-ratio consistency.

Its score exposes structure, color, texture, gradient, layout, entropy, aspect plus reason codes such as `strong_multi_scale_structure`, `strong_color_distribution`, `texture_agreement`, `gradient_orientation_agreement`, `spatial_layout_agreement`, `weak_structure`, and `insufficient_advanced_evidence`.

### Explainable search

`AdvancedVisualIntelligenceService` is a separate search service. V1 combines existing full-strength classical evidence (65%) with Advanced V1 evidence (35%). These weights are explicitly experimental and must be benchmarked before being treated as final.

The result model retains:

- final score;
- existing classical overall score;
- **true Haar score** from the Haar engine, not the overall classical score;
- pHash/dHash;
- color/edge/local evidence;
- RANSAC inliers;
- Advanced score;
- structure/color/texture/gradient/layout evidence;
- reason codes.

The UI can therefore explain the components rather than mislabe​​ling an overall score as Haar.

### Standalone Advanced UI

Added:

- `AdvancedVisualIntelligenceActivity.kt`
- `activity_advanced_visual_intelligence.xml`
- `AdvancedVisualResultAdapter.kt`
- `item_advanced_visual_result.xml`

The screen has separate sections for shared corpus, background indexing, query, and explainable results, while using the same PMAI intelligence drawables.

### Main Command Center integration

`IntelligenceHomeActivity` contains its own action:

`ADVANCED VISUAL INTELLIGENCE`

It launches only `AdvancedVisualIntelligenceActivity`.

`AndroidManifest.xml` contains one launcher only: `IntelligenceHomeActivity`. `ReverseImageSearchActivity`, `AdvancedVisualIntelligenceActivity`, and `BulkImagePickerActivity` are internal (`exported=false`). This preserves the integration architecture and avoids the earlier duplicate-application launcher bug.

### Durable shared indexing

Added:

- `UnifiedVisualIndexWorker.kt`
- `VisualIndexWorkScheduler.kt`

The visual-index operation is now a unique WorkManager task with foreground execution and persisted progress (`processed`, `total`, `indexed`, `skipped`, `failed`, `localFeatures`, percent).

The existing Reverse Image screen and the new Advanced screen both trigger/observe the same shared work item. UI recreation does not own the indexing operation.

### Zero-recall-loss indexing performance pass

The shared index now:

- preloads existing Haar/Classical/Advanced fingerprint rows once into maps;
- decodes each source image once;
- computes Haar + Classical V4 + Advanced V1 from that single Bitmap;
- processes images in bounded chunks of four with up to four concurrent CPU tasks;
- retains all existing algorithms and descriptor stages unchanged;
- does not reduce the 64 shortlist or 16 SIFT stages.

The goal is to reduce repeated Room reads and serial per-image execution without weakening any fingerprint.

### Large-batch local picker

`BulkImagePickerActivity` is now the corpus selector for both Reverse Image and Advanced Visual.

- Loads MediaStore metadata in pages of 100.
- Does not decode image bitmaps for selection.
- Supports explicit select-all across discovered media volumes.
- Requests `READ_EXTERNAL_STORAGE` on Android 12 and `READ_MEDIA_IMAGES` on Android 13+.
- Most importantly, the selected URI strings are now written to an **app-private queue file** before returning to the caller. The caller receives only a tiny file path in the result `Intent`, avoiding Binder transaction limits when selecting thousands of images.
- Android 24–25 query fallback is present for the paginated query path; API 26+ uses MediaStore query args.

This is designed for 5,000–6,000+ image selections without passing thousands of `Uri` objects through Binder.

### Import robustness

`ReverseImageSearchService.addImages()` now isolates each URI in its own try/catch and records per-item import diagnostics rather than aborting the whole batch when one URI cannot be decoded/copied.

Durable app-private copies remain the canonical analysis/search source.

### Remaining scale/durability hardening

1. Convert `addImages()` itself into durable chunked background ingestion rather than processing the entire queue in one Activity coroutine.
2. Add explicit persisted operation/checkpoint records so progress and resume state are visible beyond WorkManager's current output/progress payload.
3. Add batched Room transactions for index persistence.
4. Add stage-level latency metrics: private-copy, decode, Haar, Classical V4, AKAZE extraction, Advanced V1, DB write, total item.
5. Add dedicated tests for corrupt/unsupported ContentResolver providers and recovery/quarantine.
6. Test screen-off, rotation, app backgrounding, and process recreation on the Android 12 device.

### Current accuracy/performance invariants

- Never reduce the existing 64 shortlist.
- Never reduce the 16 SIFT verification set.
- Never remove Haar, pHash, dHash, HSV256, Sobel/shape, AKAZE, mutual matching, RANSAC, SIFT, or query variants to gain speed.
- Speed must come from shared decoding, caching, batching, memory control, native allocation reduction, and scheduling.
- MobileCLIP remains untouched.

### Current CI state

The latest code commit `b90290b42c062853f3fd4fcef8ab9c2ced0043f9` includes the true-Haar explainability fix and shared indexing performance pass. GitHub Actions run #76 (`33000684859`) was queued at the last check; do not consider this version build-verified until that run completes successfully.

## Earlier reverse-image phases

### 2026-08-26 — Full-strength performance architecture

- Restored full 64 shortlist and 16 SIFT.
- Added bounded parallel search without recall reduction.
- Query variants computed once and reused.

### 2026-08-25 — Classical V4

- Added SIFT/RANSAC shortlist verification.
- Rotation-aware retrieval.
- HSV256.

### 2026-08-25 — Classical V3 / integration history

- DigiKam-style Haar/Wavelet anchor validated by the user.
- Classical corrections and staged retrieval.
- Reverse Image remains inside the existing application shell, not a second application.
- Durable app-private copies address transient DocumentsProvider permission failures.
