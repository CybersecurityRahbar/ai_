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
7. CI failures encountered included coroutine suspend misuse, expression-body returns, missing queue constants, invalid MediaStore constants, WorkInfo/UUID mismatch, Room Entity positional-constructor shifts, and V2 generic syntax. These were fixed in subsequent commits.

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

V2 includes consensus and contradiction penalties. Strong color or texture alone cannot freely inflate a result when structure disagrees.

### Advanced candidate recall/fusion

Advanced search computes V2 evidence, selects its top 64, obtains up to 64 existing Reverse Image candidates, unions the IDs, then performs final fusion. Thus neither engine can erase a strong candidate from the other merely due to different global ranking.

### Advanced query variants

Advanced evaluates:
- original;
- rotation 90°;
- rotation 180°;
- rotation 270°;
- center crop 92%;
- center crop 82%;
- center crop 72%.

The winning variant is retained in each result and shown to the user.

### Advanced Regional Consistency layer — NEW

`AdvancedRegionConsistencyVerifier.kt` adds a deterministic spatial cross-check using the already stored V2 signatures:

- pooled 8×8 structure from the stored 16×16 grayscale signature;
- 4×4 spatial color;
- 4×4 spatial texture;
- 8×8 layout;
- stable-region ratio;
- inter-signal disagreement.

It adds no database image copies and no second decode. It is intended to suppress false positives where global color/texture looks similar but the spatial arrangement does not.

### Advanced Multiscale Structural Consensus layer — NEW

`AdvancedStructuralConsensusEngine.kt` reuses existing V2 grayscale/layout features and checks coarse + fine structural agreement plus layout. No additional stored fingerprint is required.

### Advanced evidence gate/fusion — NEW

The final Advanced score now combines:

`existing Classical/Haar evidence + Advanced V2 + Regional Consistency + Multiscale Structural Consensus`

with explicit penalties for weak region coverage, strong-coarse/weak-fine conflict, and spatial evidence disagreement.

`confidencePercent` is separate from `finalPercent`. It is an evidence-strength heuristic based on cross-signal consensus, regional stability, structural consensus, and existing geometric evidence. It is NOT a statistically calibrated probability and must not be presented as one before benchmark data.

### Explainability

Each result can show:

- final similarity;
- evidence confidence;
- classical/Haar evidence;
- Advanced V2 component evidence;
- regional consistency;
- stable region coverage;
- spatial disagreement;
- structural consensus;
- coarse/fine structure;
- winning query variant;
- reason codes and contradiction evidence.

The aggregate score is never mislabeled as Haar.

## Database schema

Advanced V2 fields:

- `spatialColor BLOB`
- `spatialLbp BLOB`
- `gradientMagnitude BLOB`
- `illuminationRobustStructure BLOB`

Database version 12 includes migration `11→12` for the V2 feature table.

## Current user instruction

The user does **not** want to install or test intermediate builds. Complete the important Advanced Visual Intelligence work first, then produce one coherent build for comprehensive testing of the whole system.

## Latest completed changes

- CI #128 succeeded after V2 entity compatibility fixes.
- Added `AdvancedRegionConsistencyVerifier`.
- Added `AdvancedStructuralConsensusEngine`.
- Integrated both into `AdvancedVisualIntelligenceService`.
- Added regional and structural evidence fields to `Evidence`.
- Added separate confidence percentage.
- Expanded result UI to display regional/structural evidence.
- Updated `PROJECT_PROGRESS.md` with this analytical layer.
- Fixed a potential O(N²) stored-fingerprint lookup by creating an itemId→Fingerprint map once.

## Next work sequence

1. Verify fresh CI for the newest Advanced regional/structural commits.
2. Complete expandable “Why this result?” UX if the current result card does not provide sufficient detail.
3. Hardening of scale/durable background behavior.
4. On-device benchmark at 1k → 5k → 6k images.
5. Accuracy benchmark covering exact duplicate, recompression, resize, screenshot/UI frame, crops, illumination/color changes, burst near-duplicates, unrelated images, rotations, perspective/viewpoint, and image-inside-screenshot.
6. Only after benchmark data, calibrate score/confidence bands.

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
- Never call an aggregate score Haar.
- Never claim accuracy/performance improvement without measured evidence.
- Never add algorithms merely for novelty; every engine must have a defined role, representation, metric, cost, and benchmark value.
