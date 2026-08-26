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
- 999-image corpus was intentionally selected and is correct.
- Larger corpora including 1120 have been observed.
- Image selection is acceptably fast. Heavy `BUILD HAAR INDEX` was historically the major bottleneck.
- Historical indexing measured roughly 3–5 seconds/image in some runs.
- Search improved from >10 minutes to roughly 10–19 seconds for ~300–1100 indexed images after staged retrieval and bounded parallelism.
- User reports generally good visual similarity, but some weak/unrelated results remain.

## Reverse Image debugging history

1. Duplicate launcher caused two app icons. Fixed: `IntelligenceHomeActivity` sole launcher.
2. `setImageURI()` on transient DocumentsProvider URIs caused RecyclerView crashes. Fixed through durable local copies.
3. Repeated searches crashed due stale/transient result display state. Fixed by clearing/replacing result state and local display paths.
4. Huge multi-select URI lists were unsafe through Activity/Intent. Replaced by paged picker and app-private queue file.
5. Queue worker initially loaded all URIs into memory. Reworked to streaming lines and fixed batches.
6. MediaDocumentsProvider decode failures are isolated per item; import uses ContentResolver streams and bounds-only validation.
7. CI failures encountered included coroutine suspend misuse, expression-body returns, missing queue constants, invalid MediaStore constants, WorkInfo/UUID mismatch, and Room Entity positional-constructor shifts. These were fixed in subsequent commits.

## Shared ingestion/indexing foundation

### Ingestion

`BulkImagePickerActivity`:
- pages metadata instead of decoding while browsing;
- queue-file output in app-private storage;
- returns only queue path through Activity result.

`ImageCorpusImportWorker`:
- foreground WorkManager;
- streams the queue file;
- processes bounded import batches;
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
- Haar + Classical V4 + Advanced from the same bitmap;
- one Room transaction per batch through `VisualIndexBatchDao`;
- per-image failure isolation;
- durable `VisualIndexOperationEntity` state after each committed batch;
- extraction/persistence/total timing diagnostics.

Database was version 11 for operation checkpoints, then upgraded to version 12 for Advanced V2 fingerprint schema.

## Advanced Visual Intelligence — current implementation

### Independent UI

`IntelligenceHomeActivity` contains an independent Advanced entry → `AdvancedVisualIntelligenceActivity`.

Advanced has its own search, index/build controls, progress, and explainable result list. It shares corpus ingestion and indexing with Reverse Image but is not embedded inside the Reverse Image screen.

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

The V2 score contains independent evidence fields and uses consensus plus contradiction penalties. Strong color or texture alone cannot freely create a high score when structure disagrees.

### Advanced candidate recall/fusion

Advanced search computes V2 global evidence and obtains up to 64 candidates from the existing Reverse Image search. It takes the top 64 Advanced candidates, unions both sets, and then fuses evidence. Thus one engine cannot erase a strong candidate from the other merely because of differing global scores.

### Advanced query variants

For each query, Advanced now evaluates:
- original;
- 90° rotation;
- 180° rotation;
- 270° rotation;
- center crop 92%;
- center crop 82%;
- center crop 72%.

For every candidate the system retains the variant that produced the strongest Advanced score. The result card displays this `bestQueryVariant`, providing real provenance for crop/rotation matches without duplicating stored corpus images.

### Explainability

Every Advanced result exposes:

- final percentage;
- existing classical percentage;
- true Haar percentage;
- pHash/dHash/color/edge/local/RANSAC evidence;
- Advanced total;
- structure;
- global/spatial color;
- global/spatial texture;
- gradient direction/magnitude;
- layout;
- illumination robustness;
- entropy/aspect;
- best query variant;
- consensus and contradiction reason codes.

The UI labels the engine `ADVANCED-V2` and never calls the aggregate score Haar.

### Database schema

Advanced V2 added:

- `spatialColor BLOB`
- `spatialLbp BLOB`
- `gradientMagnitude BLOB`
- `illuminationRobustStructure BLOB`

Database version 12 includes migration `11→12` which rebuilds only `advanced_visual_fingerprints`. Existing shared corpus data remains intact. Advanced V2 must be rebuilt before quality measurements.

## Latest completed work in this conversation

- Confirmed successful CI #111 for the previous durable shared indexing foundation.
- Upgraded Advanced engine from V1 to V2 with spatial color/texture, gradient magnitude, and illumination-robust evidence.
- Updated the shared batch index service to persist all V2 fields.
- Added Room migration 11→12.
- Updated Advanced fusion to union Reverse Image and Advanced candidate sets before ranking.
- Added contradiction penalties and reason codes.
- Added multi-variant Advanced query analysis for rotation/crop robustness and provenance.
- Expanded the Advanced result UI to display all V2 evidence and the winning query variant.
- Updated `PROJECT_PROGRESS.md` with the V2 architecture and constraints.

## Current user instruction

The user explicitly does NOT want to install or test intermediate builds. Complete the important Advanced Visual Intelligence work first, then create one coherent build for comprehensive testing of the whole system.

## Next work sequence

### 1. CI verification

All current V2/schema/service/UI changes must be CI-verified before calling the version device-ready.

### 2. Advanced remaining quality layer

After CI passes:
- add a measured region-consistency/geometric verifier where useful;
- strengthen duplicate/near-duplicate evidence without changing Reverse Image's 64/16 pipeline;
- improve score calibration/confidence bands only after device benchmark data;
- add an expandable “Why this result?” view showing actual contribution and contradiction details.

### 3. Scale/durability hardening

- test 5,000–6,000 selections;
- mixed valid/corrupt/provider URIs;
- rotation during import/index;
- background/screen-off/long absence;
- process recreation and resume;
- verify durable per-batch checkpoint behavior.

### 4. Index performance benchmark

Measure on device:
- copy;
- bounds validation;
- full decode;
- Haar;
- Classical V4;
- AKAZE extraction;
- Advanced V2;
- Room transaction;
- batch throughput;
- total throughput.

Performance optimization must not reduce algorithmic coverage.

### 5. Final accuracy benchmark

Test exact duplicates, recompression, resize, screenshots, crops, brightness/color changes, burst near-duplicates, unrelated images, rotations, perspective/viewpoint changes, and image-inside-screenshot. Record Top-1/Top-10 plus all signal components.

## Permanent rules

- **999 is valid and intentional.**
- Never reduce Reverse Image 64 shortlist or 16 SIFT.
- Never weaken/remove Haar/pHash/dHash/HSV256/Sobel/AKAZE/RANSAC/SIFT in the existing Reverse Image engine.
- Never create a second launcher/application.
- Never make Advanced a hidden sub-mode.
- Never make the user import/fetch/decode the same corpus twice.
- Never put thousands of URIs into an Intent.
- Never let one bad media item abort a large batch.
- Never let Activity destruction cancel durable indexing.
- Never call the aggregate score Haar.
- Never claim accuracy/performance improvement without measured evidence.
- Never add algorithms merely for novelty.
