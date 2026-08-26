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
- Haar + Classical + Advanced from the same bitmap;
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

V2 applies consensus and contradiction penalties. Strong color or texture alone cannot freely inflate a result when structural evidence disagrees.

### Advanced candidate recall/fusion

Advanced computes its own top 64 V2 candidates and unions them with the existing Reverse Image top 64 before fusion. This prevents either family from suppressing a strong candidate from the other.

### Advanced query variants

Advanced evaluates original, 90°, 180°, 270°, and center crops 92%, 82%, 72%. The best query variant is retained and shown in result evidence.

### Advanced Regional Consistency layer

`AdvancedRegionConsistencyVerifier.kt` adds a deterministic spatial cross-check using already persisted V2 data:

- pooled 8×8 structure derived from 16×16 grayscale;
- 4×4 spatial color;
- 4×4 spatial texture;
- 8×8 layout;
- stable-region ratio;
- inter-signal disagreement.

It adds no database image copies and no additional persistent index. It is designed to suppress false positives caused by global color/texture agreement with incompatible spatial arrangement.

### Advanced Multiscale Structural Consensus layer

`AdvancedStructuralConsensusEngine.kt` checks coarse and fine structural agreement plus layout using the existing stored V2 signatures. No additional stored fingerprint is required.

### Advanced Evidence Gate

The final Advanced score combines:

`existing Classical/Haar evidence + Advanced V2 + Regional Consistency + Multiscale Structural Consensus`.

It applies explicit penalties for weak region alignment, strong coarse/weak fine structure, and spatial evidence disagreement.

`confidencePercent` is separate from `finalPercent`; it is an evidence-strength heuristic, NOT a statistical probability. Do not call it statistically calibrated before benchmark data.

### Advanced retrieval performance

Advanced V2 variant comparisons are parallelized with bounded four-way CPU concurrency while retaining all corpus items and all 7 variants. Indexed target fingerprints are mapped by `itemId` once to avoid repeated O(N) lookups.

## Explainability

Advanced result evidence exposes:

- final similarity;
- evidence confidence;
- Classical/Haar evidence;
- Advanced V2 component evidence;
- regional consistency;
- stable-region coverage;
- spatial disagreement;
- structural consensus;
- coarse/fine structure;
- best query variant;
- reason/contradiction codes.

The aggregate score is never mislabeled as Haar.

## Current user instruction

The user explicitly does **not** want to install or test intermediate builds. Complete the important Advanced Visual Intelligence work first, then create one coherent build for comprehensive testing of the whole system.

## Latest completed changes in this conversation

- Confirmed CI #128 succeeded for the prior Advanced V2 compile-safe baseline.
- Added `AdvancedRegionConsistencyVerifier` and corrected its 8×8 structural alignment.
- Added `AdvancedStructuralConsensusEngine`.
- Integrated both into `AdvancedVisualIntelligenceService`.
- Added regional, structural, and confidence evidence fields.
- Updated `AdvancedVisualResultAdapter` to display the new evidence.
- Parallelized all Advanced query-variant/global-corpus comparisons with bounded four-way concurrency without reducing recall.
- Fixed potential O(N²) indexed-fingerprint lookup by prebuilding an `itemId -> Fingerprint` map.
- Updated `PROJECT_PROGRESS.md` and this ledger with the new analytical layers and invariants.

## Next work sequence

1. Verify fresh CI for the latest Advanced region/structural/parallel changes.
2. Complete expandable `Why this result?` UI if the current card remains too dense.
3. Finish scale/durable background validation only after Advanced is considered feature-complete.
4. Run 1k → 5k → 6k indexing benchmarks.
5. Run accuracy benchmarks: exact duplicate, recompression, resize, screenshot/UI frame, crops, illumination/color changes, burst near-duplicates, unrelated images, rotations, perspective/viewpoint, image-inside-screenshot.
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
- Never call the aggregate score Haar.
- Never claim accuracy/performance improvement without measured evidence.
- Never add algorithms merely for novelty; every engine must have a defined role, representation, metric, cost, and benchmark value.
