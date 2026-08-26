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
- Existing Reverse Image search strength invariant: **999+ full-corpus global retrieval → 64 shortlist → AKAZE/RANSAC across all 64 → 16 SIFT/RANSAC**.
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
5. The first queue worker loaded the entire URI queue into memory. Reworked to streaming line processing.
6. MediaDocumentsProvider decode errors are now isolated per image; the pipeline copies content via `ContentResolver` streams and validates with `BitmapFactory.Options.inJustDecodeBounds` before corpus insertion.
7. Prior CI failures included Kotlin List/Array mismatch, suspend DAO misuse, expression-body returns, missing queue constants, invalid MediaStore query constants, WorkInfo/UUID mismatch, and latest visual-entity constructor parameter-shift errors. These were corrected or are being corrected in later commits/builds.

## Current Reverse Image strength

The current production baseline remains:

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
- 24-bin gradient orientation histogram weighted by gradient magnitude;
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
- exposes persisted WorkManager progress.

### Shared visual index

`UnifiedVisualIndexWorker` uses `OptimizedUnifiedVisualIndexService`.

The optimized index service:
- uses unchanged Haar, Classical V4, and Advanced V1 engines;
- decodes each source image once per indexing pass;
- processes bounded batches of 16 with up to four CPU workers;
- collects prepared feature rows only for the current batch;
- persists Haar + Classical + Advanced rows through `VisualIndexBatchDao.insertBatch()` in one Room transaction;
- isolates per-image failures rather than aborting the entire batch;
- preserves existing feature outputs and engine versions.

### Latest compile issue discovered in Run #103

Run `#103` (`docs(context): record batch indexing implementation and latest build …`) failed during `compileDebugKotlin` in `OptimizedUnifiedVisualIndexService.kt:124–126`.

Root cause: the service used **positional constructors** for `HaarFingerprintEntity`, `ClassicalVisualFingerprintEntity`, and `AdvancedVisualFingerprintEntity`, but their definitions include `id` and `createdAt` fields and the exact field order differs. Kotlin therefore shifted `String/Long/Int/ByteArray/Float` values into the wrong parameters and produced the cascade of type mismatch errors.

Fix applied: replace all three positional constructor calls with **named arguments**, matching the exact entity definitions. `AppDatabase` was re-read and confirmed to already expose `visualIndexBatchDao()`.

This fix is required before the batch-index service can be considered build-verified.

### Latest implementation principle

Do not add more advanced algorithms until the current shared-scale foundation is compile-verified and stable. The purpose of this phase is to make 5,000–6,000+ image ingestion/indexing reliable and fast **without reducing any accuracy-bearing stage**.

## Current remaining work

### Priority A — Verify/harden scale behavior

1. Verify CI after the latest constructor fix.
2. Test 5,000–6,000 selections on the target device.
3. Test mixed valid/corrupt/unsupported/MediaDocumentsProvider URIs.
4. Test rotation during import and indexing.
5. Test backgrounding, screen-off, long absence, and process recreation.
6. Add explicit persisted operation/checkpoint records beyond WorkManager progress so interrupted operations resume from durable per-item state.

### Priority B — Indexing performance

1. Stage timings for copy, bounds decode, full bitmap decode, Haar, Classical, AKAZE extraction, Advanced V1, Room transaction, item and batch totals.
2. Verify that optimized fingerprints/descriptors are equivalent to previous engine outputs for the same engine versions.
3. Tune concurrency by measured RAM/CPU behavior, not by reducing algorithmic coverage.
4. Consider reuse/caching of native image representations where safe.

### Priority C — Advanced Visual Intelligence expansion

Only after A/B are stable:

1. Add additional classical analytical signals selected by measured value.
2. Keep all Advanced features in its own versioned index.
3. Share ingestion/decode with the current classical pipeline.
4. Add robust multi-region, scale, illumination, texture, contour, gradient and geometric evidence where it demonstrably improves discrimination.
5. Add contradiction penalties and explainable evidence fusion.

### Priority D — Accuracy benchmark

Controlled cases:
- exact duplicate;
- JPEG recompression;
- resize;
- screenshot with UI frame;
- small and large crop;
- brightness/contrast/color changes;
- near-duplicate burst;
- unrelated image;
- 90/180/270-degree rotation;
- perspective/viewpoint change;
- image-inside-screenshot.

Record Top-1/Top-10 and every component; never claim accuracy improvement without measurements.

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
