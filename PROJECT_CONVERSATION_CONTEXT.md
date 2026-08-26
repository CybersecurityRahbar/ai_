# Personal Memory AI — Persistent Conversation Context Ledger

## Purpose

Durable memory for the reverse-image/indexing development conversation. Read this file and `PROJECT_PROGRESS.md` before modifying Reverse Image, shared corpus/indexing, or Advanced Visual Intelligence.

## Permanent architecture requirements

- `IntelligenceHomeActivity` is the single Android launcher.
- `ReverseImageSearchActivity` is an independent top-level feature.
- `AdvancedVisualIntelligenceActivity` is an independent top-level feature in the same Intelligence Command Center and uses the same PMAI visual design language.
- Advanced must never be a hidden mode inside Reverse Image and must never create a second application/launcher.
- Both feature families consume one shared local image corpus/import/decode pipeline. The same image is not fetched/copied/decoded twice merely because two engines need it.
- Reverse Image and Advanced retain independent versioned feature stores and can be rebuilt independently.
- Reverse Image algorithms remain unchanged and full strength: DigiKam-style Haar, pHash, dHash, HSV256, spatial Sobel/shape, persisted AKAZE descriptors, AKAZE mutual/RANSAC, SIFT mutual/RANSAC, rotation/crop variants.
- Reverse Image retrieval coverage is locked: **full corpus → 64 shortlist → AKAZE/RANSAC over all 64 → 16 SIFT/RANSAC → final ranking**.
- Never lower 64/16 or remove accuracy-bearing stages to solve performance.
- MobileCLIP/neural semantic search stays postponed.
- Advanced algorithms must have defined purpose, storage, metric, cost, failure modes, and benchmark value.
- Advanced result percentages must be reconstructible from real stored evidence; never fabricate explanations.
- Large-batch selection/import must support 5,000–6,000+ images without huge Intent/list memory loads.
- Long-running work must survive rotation/background/screen-off/process recreation to the extent Android permits; cancellation is explicit.

## User/device facts

- Target device: Android 12 / SM-G981U.
- 999-image corpus is intentional and correct.
- Larger corpora including 1120 have been observed.
- Image selection is acceptably fast. Heavy `BUILD HAAR INDEX` was historically the major bottleneck.
- Historical indexing measured roughly 3–5 seconds/image in some runs.
- User reports generally good visual similarity, but some weak/unrelated results remain.
- Search is now much faster than the earlier >10-minute behavior after staged retrieval and bounded concurrency.

## Reverse Image debugging history

1. Duplicate launcher caused two app icons. Fixed: `IntelligenceHomeActivity` sole launcher.
2. `setImageURI()` on transient DocumentsProvider URIs caused RecyclerView crashes. Fixed through durable local copies.
3. Repeated-search crash fixed by clearing/replacing result state and using local display paths.
4. Huge multi-select URI lists were unsafe through Activity/Intent. Replaced by paged picker and app-private queue file.
5. Queue worker initially loaded all URIs into memory. Reworked to streaming lines and fixed batches.
6. MediaDocumentsProvider decode failures are isolated per item.
7. CI compile regressions during this work included coroutine/suspend misuse, expression-body returns, missing queue constants, invalid MediaStore constants, WorkInfo/UUID mismatches, Room positional-constructor shifts, V2 generic syntax, missing V2 constructor fields, and incorrect diagnostics API usage. These were fixed in later commits.

## Shared ingestion/indexing foundation

### Ingestion

`BulkImagePickerActivity`:
- pages metadata instead of decoding while browsing;
- queue-file output in app-private storage;
- returns only queue path through Activity result.

`ImageCorpusImportWorker`:
- foreground WorkManager;
- streams the queue file;
- bounded import batches;
- bounded concurrent URI copy/validation;
- durable local copies;
- `inJustDecodeBounds` validation;
- batched corpus insertion;
- per-item failure isolation;
- persisted WorkManager progress.

### Visual indexing

`UnifiedVisualIndexWorker` uses `OptimizedUnifiedVisualIndexService`.

The optimized service:
- one local copy;
- one decode per image per indexing pass;
- up to 4 CPU workers;
- feature batch size 16;
- Haar + Classical V4 + Advanced V2 from the same bitmap;
- one Room transaction per batch through `VisualIndexBatchDao`;
- per-image failure isolation;
- durable `VisualIndexOperationEntity` checkpoints after committed batches;
- extraction/persistence/total timing diagnostics.

Database was version 11 for operation checkpoints and version 12 for Advanced V2 fingerprint schema.

## Advanced Visual Intelligence — current implementation

### Independent UI

`IntelligenceHomeActivity` contains an independent Advanced entry → `AdvancedVisualIntelligenceActivity`.

Advanced has its own image search, shared-corpus import/build controls, progress, threshold, and explainable result list. It is not embedded in the Reverse Image screen and is not a second application.

### Advanced Visual Fingerprint V2

Engine version:

`ADVANCED-VISUAL-CLASSICAL-V2`

Persistent signals:

- 16×16 multi-scale grayscale structure;
- global RGB/saturation moments;
- 4×4 spatial RGB/saturation distribution;
- 256-bin LBP histogram;
- 4×4 spatial LBP-transition texture signature;
- 24-bin gradient-orientation histogram;
- independent gradient-magnitude histogram;
- 8×8 spatial edge/layout signature;
- 16×16 illumination-robust local variance/contrast signature;
- entropy;
- aspect ratio.

V2 score applies cross-signal consensus and contradiction penalties. Strong color/texture agreement alone cannot freely inflate a result when structural evidence disagrees.

### Advanced recall/fusion

Advanced computes its V2 scores over the full Advanced corpus, keeps the top 64 Advanced candidates, obtains up to 64 candidates from the existing Reverse Image search, unions the IDs, then performs final evidence fusion. This protects cross-engine recall.

The existing Reverse Image engine remains full strength and its own 64→AKAZE/RANSAC→16→SIFT/RANSAC coverage is unchanged.

### Advanced query variants

Advanced evaluates:

- original;
- rotation 90°;
- rotation 180°;
- rotation 270°;
- center crop 92%;
- center crop 82%;
- center crop 72%.

The winning variant is retained for each result, so crop/rotation provenance is explicit without duplicating corpus images.

### Advanced Regional Consistency

`AdvancedRegionConsistencyVerifier.kt` reuses stored V2 data to compare corresponding spatial regions:

- 8×8 structural agreement pooled from the 16×16 grayscale signature;
- 4×4 spatial-color agreement;
- 4×4 spatial-texture agreement;
- 8×8 layout agreement;
- stable-region ratio;
- inter-signal disagreement.

No additional persistent fingerprint or image decode is required.

### Advanced Multiscale Structural Consensus

`AdvancedStructuralConsensusEngine.kt` checks coarse/fine structural agreement plus layout using existing V2 signatures. It adds no second stored fingerprint and no second decode.

### Advanced retrieval performance

All 7 Advanced query variants compare against the full stored corpus with bounded four-way CPU concurrency. Indexed target fingerprints are mapped once by `itemId` to avoid repeated lookups. Recall was not reduced to improve speed.

### Advanced Evidence Gate

Final Advanced ranking combines:

`existing Classical/Haar + Advanced V2 + Regional Consistency + Multiscale Structural Consensus`.

Penalties explicitly address:

- weak cross-signal consensus;
- strong color with weak structure;
- strong texture with weak structure;
- weak stable-region coverage;
- high spatial disagreement;
- strong coarse structure with weak fine structure.

`confidencePercent` is an evidence-strength heuristic, not a probability and not statistically calibrated.

### Explainability UX

Advanced result cards now show a compact summary by default and an expandable `WHY THIS RESULT ▸` section containing:

- winning query variant;
- Classical and Advanced aggregate scores;
- regional consistency and stable-region coverage;
- structural consensus/coarse/fine values;
- all Advanced component values;
- Haar/pHash/dHash/color/edge/local/RANSAC evidence;
- reason/contradiction codes.

RecyclerView expansion state is reset during binding to prevent state leakage between cards. Tapping the card still opens the image; tapping the Why control only expands/collapses evidence.

## Database

Database version 12 contains Advanced V2 fields:

- `spatialColor`
- `spatialLbp`
- `gradientMagnitude`
- `illuminationRobustStructure`

Migration `11 → 12` handles these Advanced fields while keeping the shared corpus intact.

## Build status and development policy

CI #140 succeeded and produced debug APK artifact `personal-memory-ai-debug.apk`.

The user explicitly does **not** want intermediate installation/testing. Advanced development must be completed first; only then should one coherent build be installed for comprehensive system testing.

A final benchmark plan is stored at:

`docs/ADVANCED_VISUAL_FINAL_BENCHMARK_PLAN.md`

This benchmark controls accuracy, false-positive analysis, performance, lifecycle, and acceptance gates.

## Latest completed work in this conversation

- Confirmed CI #140 success for regional/structural/parallel retrieval baseline.
- Added/validated regional consistency and multi-scale structural consensus layers.
- Parallelized Advanced full-corpus retrieval across all 7 query variants with bounded four-way concurrency.
- Fixed indexed-fingerprint lookup to avoid unnecessary repeated scans.
- Added an evidence-strength confidence heuristic separate from similarity.
- Added expandable `Why this result?` result-card UX with RecyclerView-safe state reset.
- Suppressed the new Advanced `limitedParallelism` opt-in warning without changing algorithmic behavior.
- Added the controlled final device benchmark plan.
- Updated this context ledger and `PROJECT_PROGRESS.md`.

## Next work sequence

1. Verify CI after the latest expandable-UX / warning cleanup commit.
2. Perform final static architecture review of Advanced + shared worker + database migration.
3. If static review is clean, freeze Advanced feature scope.
4. Build one final CI artifact.
5. Install once on the user's Android 12 / SM-G981U.
6. Run shared corpus import and indexing on 999 images first, then scale to 5,000–6,000.
7. Run accuracy benchmark from `docs/ADVANCED_VISUAL_FINAL_BENCHMARK_PLAN.md`.
8. Only after measurements, make threshold/calibration changes or further performance decisions.

## Permanent rules

- **999 is valid and intentional.**
- Never reduce Reverse Image 64 shortlist or 16 SIFT.
- Never weaken/remove Haar/pHash/dHash/HSV256/Sobel/AKAZE/RANSAC/SIFT in the existing Reverse Image engine.
- Never create a second launcher/application.
- Never make Advanced a hidden sub-mode.
- Never make the user import/fetch/decode the corpus twice.
- Never put thousands of URIs into an Intent.
- Never let one bad media item abort a large batch.
- Never let normal Activity destruction cancel durable indexing.
- Never call an aggregate score Haar.
- Never call `confidencePercent` a probability.
- Never claim accuracy or performance improvement without measurement.
- Never add algorithms merely for novelty; every engine must have a defined role, representation, metric, cost, and benchmark value.
