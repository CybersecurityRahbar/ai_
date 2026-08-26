# Personal Memory AI — Project Progress Log

## 2026-08-26 — Advanced Visual Intelligence V1 + shared durable indexing implementation

### User-mandated architecture

The new **Advanced Visual Intelligence** must be a distinct top-level section in the existing `Intelligence Command Center`.

- It has its **own Activity/screen**.
- It is visible as its **own action** in the central `IntelligenceHomeActivity`.
- It uses the same PMAI visual language, backgrounds, panels, buttons, spacing, and colors.
- It is **not** a hidden mode inside `ReverseImageSearchActivity`.
- It is **not** a second Android application and has no launcher entry of its own. `IntelligenceHomeActivity` remains the sole `MAIN/LAUNCHER`.
- The existing Reverse Image Search screen remains a separate screen and retains its existing classical search behavior.

### Shared corpus / independent indices

The user explicitly requires that adding/fetching images must not be performed twice merely because both sections need the same images.

The architecture now uses:

`Shared local image corpus -> one decode/normalization pass -> independent feature indices`

Current independent persistent indices:

1. Existing Reverse Image:
   - Haar/Wavelet
   - Classical V4
   - persisted AKAZE descriptors
   - separate existing tables/DAOs
2. New Advanced Visual Intelligence:
   - `advanced_visual_fingerprints`
   - independent engine version `ADVANCED-VISUAL-CLASSICAL-V1`

`ReverseImageSearchService.buildIndex()` now computes Haar + Classical V4 + Advanced V1 from **the same decoded Bitmap** and stores each feature family separately. This is the first concrete implementation of the shared-indexing requirement.

### Advanced V1 classical engines implemented

The new `AdvancedVisualFingerprintEngine` currently produces deterministic, non-neural signals:

- 16×16 multi-scale grayscale structure map.
- RGB/color moments plus saturation statistics.
- 256-bin Local Binary Pattern (LBP) texture histogram.
- 24-bin gradient-orientation histogram weighted by gradient magnitude.
- 8×8 spatial layout/edge signature.
- normalized grayscale entropy.
- aspect-ratio consistency.

The engine produces a transparent `Score` with separate:

- structure
- color
- texture
- gradient
- layout
- entropy
- aspect

components and human-readable reason codes such as `strong_multi_scale_structure`, `strong_color_distribution`, `texture_agreement`, `gradient_orientation_agreement`, `spatial_layout_agreement`, `weak_structure`, and `insufficient_advanced_evidence`.

### Explainable Advanced search implemented

`AdvancedVisualIntelligenceService` is a separate search service.

It compares the new Advanced V1 index and also retrieves the existing full-strength classical result set for the same query. It then fuses:

- existing classical evidence: 65%
- Advanced V1 evidence: 35%

for the current V1 experimental explainable ranking.

The result model preserves component evidence including:

- existing classical overall score
- Haar percentage
- pHash/dHash
- color/edge/local evidence
- RANSAC inliers
- Advanced percentage
- structure/color/texture/gradient/layout components
- reason codes

This is explicitly a **measured/experimental V1 fusion**, not an accuracy claim. Future benchmark data must validate the weights.

### Standalone Advanced UI implemented

Added:

- `AdvancedVisualIntelligenceActivity.kt`
- `activity_advanced_visual_intelligence.xml`
- `AdvancedVisualResultAdapter.kt`
- `item_advanced_visual_result.xml`

The screen contains separate sections for:

- shared corpus / independent advanced index
- shared background indexing
- advanced query
- explainable visual results

The design reuses the PMAI intelligence resources such as `bg_intelligence`, `panel_intelligence`, and `bg_intel_button` rather than introducing a different visual system.

### Main Command Center integration

`IntelligenceHomeActivity` now contains a distinct action:

`ADVANCED VISUAL INTELLIGENCE`

It launches only `AdvancedVisualIntelligenceActivity`.

No second `MAIN/LAUNCHER` was added. `AndroidManifest.xml` currently has `IntelligenceHomeActivity` as the sole launcher, while both Reverse Image and Advanced Visual are non-exported internal screens.

### Durable shared indexing worker implemented

Added:

- `UnifiedVisualIndexWorker.kt`
- `VisualIndexWorkScheduler.kt`

The shared indexing operation is now launched as a unique WorkManager task rather than being tied only to an Activity coroutine.

The worker:

- runs the shared Haar + Classical + Advanced indexing pass;
- publishes persisted WorkManager progress (`processed`, `total`, `indexed`, `skipped`, `failed`, `localFeatures`, `percent`);
- runs as a long-running foreground worker with a user-visible notification;
- uses the `dataSync` foreground-service classification appropriate for local file processing.

Android's documentation explicitly supports WorkManager long-running workers and foreground execution for important local processing tasks. citeturn984758search2turn366781search0

This is intended to survive Activity recreation, rotation, temporary backgrounding, and screen-off better than a `lifecycleScope` job. Android 12+ restricts arbitrary background foreground-service starts, so the user-initiated WorkManager path is the appropriate architecture here. citeturn984758search1turn984758search3

### Reverse Image screen integration

The existing `ReverseImageSearchActivity` now launches the same unique shared indexing worker for `BUILD HAAR INDEX` / `REBUILD` and observes its durable progress.

This preserves the existing Reverse Image UI while ensuring its indexing pass also generates the Advanced index in the same decode pass.

The Reverse Image search algorithm itself remains:

- full corpus global retrieval
- 64 shortlist
- AKAZE/RANSAC over the shortlist
- 16 SIFT/RANSAC candidates
- no recall-reduction shortcut

### Large-batch local picker implemented

Added `BulkImagePickerActivity` as an in-app local gallery/picker with:

- paged loading in groups of 100;
- `SELECT ALL` that collects only URI strings rather than decoding images into memory;
- incremental page loading;
- selected-count telemetry;
- one final URI list returned to the calling screen.

This replaces the previous reliance on the system `GetMultipleContents()` picker for corpus acquisition in Reverse Image and Advanced Visual. The purpose is to remove the observed 500-item/large-selection limitation of the system picker path and support thousands of selected images without creating a giant in-memory bitmap workload.

The picker requests the appropriate image-read permission (`READ_EXTERNAL_STORAGE` on Android 12 and `READ_MEDIA_IMAGES` on Android 13+).

### Current known limitations still being worked on

1. The new picker is now paged, but the large-batch ingestion path still needs full **streaming/chunked queueing** so a 5,000–6,000 selection is not sent as one giant list to a single Activity coroutine.
2. `ReverseImageSearchService.addImages()` still processes the selected URI list synchronously and must be moved to durable/bounded ingestion work next.
3. The WorkManager worker is durable and resumable through versioned skip checks, but explicit chunk checkpoints and a user-facing persisted operation history still need to be added.
4. The current indexing pass still performs separate Room reads/writes per item; shared decoding is implemented, but batched DB transactions and multi-image CPU workers are the next performance optimization.
5. ContentResolver URI decoding is more robust for app-private copies, but the failing DocumentsProvider cases need dedicated per-item import diagnostics and recovery tests.
6. Advanced search V1 is deliberately an initial classical evidence layer. It must be benchmarked before more engines or weight changes are justified.

### Hard accuracy/performance invariants

- Do not reduce the existing 64 shortlist.
- Do not reduce the 16 SIFT verification set.
- Do not remove Haar, pHash, dHash, HSV256, Sobel/shape, AKAZE, mutual matching, RANSAC, SIFT, or query variants to gain speed.
- Performance improvements must come from shared decoding, batching, caching, memory control, native allocation reduction, and CPU scheduling.
- MobileCLIP remains untouched by this phase.

## 2026-08-26 — Scale, durability, indexing performance, and Advanced Visual Intelligence roadmap

The current device findings, large-batch defects, rotation/background defects, zero-recall-loss indexing strategy, explainability requirements, and phased roadmap remain valid and are expanded by the implementation above.

## 2026-08-26 — Full-strength reverse-image search performance pass

- Restored full `64` shortlist and full `16` SIFT stage.
- Added bounded parallel execution without reducing recall.
- Query variants are computed once and reused.

## 2026-08-25 — Reverse-image classical stack v4

- SIFT/RANSAC verifier.
- Rotation-aware retrieval.
- HSV256.

## 2026-08-25 — Reverse-image classical stack v3 and integration history

- DigiKam-style Haar/Wavelet anchor.
- Classical corrections and staged retrieval.
- Reverse Image remains inside the existing application shell and is not a second Android application.
