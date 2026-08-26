# Personal Memory AI — Persistent Conversation Context Ledger

## Purpose

This file is the durable memory ledger for the reverse-image/indexing development conversation. It records user requirements, decisions, observed failures, implementation changes, and the exact next order of work.

**Rule:** Read this file and `PROJECT_PROGRESS.md` before changing reverse-image, shared corpus, indexing, or Advanced Visual Intelligence architecture.

## 2026-08-26 — Current state and permanent requirements

### Architecture demanded by the user

- `IntelligenceHomeActivity` remains the **single Android launcher**.
- Existing `ReverseImageSearchActivity` remains a distinct top-level feature and screen.
- `AdvancedVisualIntelligenceActivity` is another distinct top-level feature and screen in the same Intelligence Command Center.
- Advanced Visual Intelligence must have the same PMAI visual language: same colors, panels, buttons, spacing, and overall style.
- Advanced must NOT be a hidden mode inside Reverse Image and must NOT create a second launcher/application.
- Both feature screens consume a **shared local image corpus / ingestion layer** so the user does not import the same image twice.
- Feature stores are independent: Reverse Image keeps Haar/Classical/local descriptors; Advanced keeps its own versioned feature index.
- Existing Reverse Image algorithms remain intact: DigiKam-style Haar, Classical V4, pHash, dHash, HSV256, spatial edge/shape, AKAZE mutual/RANSAC, SIFT mutual/RANSAC, rotation/crop query variants.
- Existing Reverse Image search strength invariant: **full-corpus global retrieval → 64 shortlist → AKAZE/RANSAC across all 64 → 16 SIFT/RANSAC**.
- The user explicitly rejects any performance solution that lowers 64 or 16 or removes/weakens an algorithm.
- MobileCLIP / previous neural semantic models are intentionally out of scope until the classical systems are mature.
- Advanced Visual Intelligence must add measured, deterministic analytical signals rather than random algorithm accumulation.
- Advanced results must explain exactly why a result was returned and how its percentage was formed from evidence.
- The user wants large-batch selection/import, including approximately 5,000–6,000+ images in one workflow.
- Long indexing/import must survive Activity recreation, rotation, backgrounding, screen-off, and process recreation to the extent Android execution permits; explicit cancellation is the only normal cancel path.

### Confirmed device/test facts

- Target device: Android 12 / SM-G981U.
- 999-image corpus was intentionally selected by the user and is correct; it is not an indexing defect.
- Later runtime corpora include 1120 images.
- Selecting images is reasonably fast; heavy `BUILD HAAR INDEX` / fingerprinting is the main bottleneck.
- Prior indexing observations were roughly 3–5 seconds per image in some runs.
- Prior search behavior improved materially from >10 minutes to roughly 10–19 seconds for ~300–1100 images after staged retrieval and bounded parallel search.
- Search quality is generally good for visual similarity, but the user observes some weak/unrelated results and wants stronger discrimination and explanation.

## Reverse Image debugging history — do not repeat

1. Reverse Image was once exposed as a second launcher/application. Fixed by keeping `IntelligenceHomeActivity` as the sole `MAIN/LAUNCHER` and Reverse Image internal.
2. Results crashed because RecyclerView directly called `setImageURI()` on transient `content://` URIs. Fixed by durable app-private image copies and local display paths.
3. Repeated searches previously crashed because stale URI display state survived into a new RecyclerView bind. Fixed by clearing old results and preferring durable local file paths.
4. Large selection initially passed huge URI lists via Activity/Intent. Replaced with paged `BulkImagePickerActivity` and app-private queue-file transport.
5. The first queue worker loaded the entire queue into memory. Reworked to streaming line processing.
6. MediaDocumentsProvider decode errors are now isolated per image; the pipeline copies content via `ContentResolver` streams and validates with `BitmapFactory.Options.inJustDecodeBounds` before corpus insertion.
7. Earlier CI failures included Kotlin List/Array mismatch, suspend DAO misuse, expression-body returns, missing queue constants, invalid MediaStore query constants, WorkInfo/UUID mismatch, and visual-entity constructor parameter-shift errors. These were corrected in subsequent builds.

## Current Reverse Image strength

The current baseline remains:

`full corpus global retrieval -> 64 shortlist -> AKAZE mutual/RANSAC across 64 -> 16 SIFT/RANSAC -> final ranking`

No future performance change may reduce these values without an explicit user request.

## Advanced Visual Intelligence

### Current top-level structure

`IntelligenceHomeActivity`
- `LOCAL REVERSE IMAGE SEARCH` → `ReverseImageSearchActivity`
- `ADVANCED VISUAL INTELLIGENCE` → `AdvancedVisualIntelligenceActivity`

`AndroidManifest.xml`
- `IntelligenceHomeActivity` is the only launcher.
- Reverse/Advanced/Bulk picker activities are internal (`exported=false`).

### Shared corpus principle

`source URI → one durable local copy → one decode/normalization pass → independent engine outputs`

No duplicate image import/fetch/decode solely because two visual sections need the same image.

### Advanced V1 currently implemented

`AdvancedVisualFingerprintEngine` generates deterministic, non-neural evidence:

- 16×16 multi-scale grayscale structure;
- RGB/color moments + saturation statistics;
- 256-bin LBP texture histogram;
- 24-bin gradient orientation histogram weighted by magnitude;
- 8×8 spatial layout/edge signature;
- grayscale entropy;
- aspect ratio.

`AdvancedVisualIntelligenceService` preserves component evidence and reason codes. Existing classical evidence remains separate from Advanced evidence.

### Explainable result requirements

Every future result should expose real, reconstructible evidence:

- final percentage;
- true Haar percentage;
- pHash/dHash evidence;
- HSV/color evidence;
- edge/shape evidence;
- AKAZE good matches + RANSAC inliers;
- SIFT good matches + RANSAC inliers;
- Advanced structure/color/texture/gradient/layout evidence;
- best query variant (original/rotation/crop);
- geometric consistency;
- contradiction/weak-evidence penalties;
- explicit reason codes.

The displayed score must be mathematically reproducible from stored components. Never label overall score as Haar.

## Scale and durability implementation

### Large-batch selection/import

`BulkImagePickerActivity`:
- paginates MediaStore metadata in batches of 100;
- does not decode image bitmaps while browsing;
- supports select-all over available media volumes;
- writes selected URI strings to an app-private queue file;
- returns only the small queue-file path to the caller, avoiding Binder limits for thousands of URIs.

`ImageCorpusImportWorker` + scheduler:
- runs as unique foreground WorkManager work;
- streams queue lines instead of loading all URIs into memory;
- processes fixed batches rather than one giant in-memory list;
- performs bounded concurrent copy/validation;
- validates dimensions with `inJustDecodeBounds` and creates one durable local copy;
- uses batched corpus insertion;
- isolates per-item failures;
- exposes WorkManager progress.

### Shared visual index

`UnifiedVisualIndexWorker` uses `OptimizedUnifiedVisualIndexService`.

The optimized index service:
- uses unchanged Haar, Classical V4, and Advanced V1 engines;
- decodes each source image once per indexing pass;
- processes bounded batches of 16 with up to four CPU workers;
- collects prepared feature rows only for the current batch;
- persists Haar + Classical + Advanced rows through `VisualIndexBatchDao.insertBatch()` in one Room transaction;
- isolates per-image failures rather than aborting the entire batch;
- preserves existing feature outputs and engine versions;
- records an operation ID and updates a durable Room checkpoint after every committed batch;
- records extraction/persistence/total timing diagnostics.

`VisualIndexOperationEntity` + `VisualIndexOperationDao` persist:
- operation ID;
- rebuild flag;
- total/processed/indexed/skipped/failed counts;
- local-feature count;
- engine versions;
- status (`RUNNING`, `COMPLETED`, `FAILED`);
- start/update/finish timestamps;
- latest error where applicable.

`AppDatabase` is now at version 11 with migration `10→11` creating `visual_index_operations`.

### Latest build history

- Run #103 failed because positional constructors in `OptimizedUnifiedVisualIndexService.kt` did not match the actual Room Entity field order. Fixed by named arguments.
- Run #105 then succeeded completely and produced a debug APK, proving the entity/batch layer compiled.
- A later optimization pass added durable operation checkpoints and improved timing, so those later commits must also be CI-verified before testing on-device.

## Most recent user conversation entry — 2026-08-26

### User request

The user confirmed the successful build and ordered continued work on the remaining fixes and roadmap with maximum care. The user requires that the conversation context, decisions, failures, and progress be recorded in GitHub so the project can resume without losing context.

The user reaffirmed:

- no weakening of current search algorithms;
- no reduction of 64/16;
- continue large-scale background/chunked ingestion and batch persistence;
- then continue Advanced Visual Intelligence with strong, measured classical/analytical engines;
- Advanced remains a separate top-level screen with identical PMAI design language;
- current Reverse Image remains a separate unchanged feature;
- one shared corpus/import/decode pass serves both feature families;
- each family maintains its own feature/index storage;
- explainability must be based on real evidence and reproducible percentages.

### Work performed after that request

1. Corrected the latest batch entity constructor issue discovered by Run #103.
2. Added `VisualIndexOperationEntity` and `VisualIndexOperationDao` for durable operation state.
3. Upgraded `AppDatabase` from version 10 to 11 with a `10→11` migration for operation checkpoints.
4. Upgraded `OptimizedUnifiedVisualIndexService` so each image's fingerprints are computed exactly once per pass, feature results are held only for the current bounded batch, and Haar/Classical/Advanced rows are committed atomically per batch.
5. Added durable `RUNNING`, per-batch progress, `COMPLETED`, and `FAILED` state updates plus extraction/persistence/total timing diagnostics.
6. Confirmed `UnifiedVisualIndexWorker` is wired to the optimized service.
7. Kept `BATCH_SIZE=16` and `PARALLELISM=4` while preserving all accuracy-bearing search settings (`64` and `16`) unchanged.
8. The latest GitHub Actions workflow triggered after these changes was still `in_progress` at the last observed status. Do not call the latest unverified commit an installable APK until CI finishes successfully.

## Next execution order

### A — Finish durable scale layer

1. CI-verify latest checkpoint/batch changes.
2. Test 5,000–6,000 selections on Android 12 device.
3. Test mixed valid/corrupt/unsupported/MediaDocumentsProvider URIs.
4. Test rotation during import/indexing.
5. Test long backgrounding and screen-off.
6. Test process recreation and durable resume.
7. Persist a precise per-item completion/checkpoint key if needed so restart does not unnecessarily rescan the corpus.

### B — Indexing performance without accuracy loss

1. Benchmark stage timings from real device runs.
2. Optimize native/OpenCV allocations and shared intermediate representations.
3. Confirm one decode per pass and batch Room persistence.
4. Tune bounded concurrency from measured RAM/CPU behavior, never by reducing algorithmic coverage.
5. Verify output equivalence for unchanged engine versions.

### C — Advanced Visual Intelligence expansion

After A/B are verified stable:

1. Add multi-region structural descriptors.
2. Add scale/illumination-robust structural evidence.
3. Add stronger contour/gradient/texture descriptors where measurable.
4. Add region consistency and geometric contradiction penalties.
5. Add independent V2 feature/index versioning.
6. Add advanced result evidence cards and mathematically reproducible score fusion.

### D — Benchmark

Test exact duplicate, recompression, resize, screenshot/UI frame, small/large crop, brightness/color changes, near-duplicate burst, unrelated image, rotation, perspective/viewpoint change, and image-inside-screenshot. Record Top-1/Top-10 and component evidence.

## Permanent rules

- **999 is valid and intentional.**
- Never reduce 64 shortlist or 16 SIFT to solve performance.
- Never silently weaken algorithm strength.
- Never create a second launcher for a feature screen.
- Never require duplicate user import/fetching for separate visual engines.
- Never put thousands of URIs into an Intent.
- Never let one malformed media item abort an entire large import batch.
- Never let Activity destruction cancel durable background indexing.
- Never call a total/aggregate score “Haar” unless it is the real Haar component.
- Never claim accuracy improvement without measured benchmark evidence.
- Do not add advanced algorithms merely for novelty; each must have a defined metric, storage schema, cost, and benchmark purpose.
