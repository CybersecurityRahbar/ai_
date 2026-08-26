# Personal Memory AI — Persistent Conversation Context Ledger

## Purpose

Durable memory for the reverse-image/indexing development conversation. Read this file and `PROJECT_PROGRESS.md` before modifying Reverse Image, shared corpus/indexing, or Advanced Visual Intelligence.

## Latest runtime discovery — 2026-08-27

The first comprehensive device installation exposed a runtime integration defect that CI compilation could not detect.

User action:
- Opened `ADVANCED VISUAL INTELLIGENCE` and/or `LOCAL REVERSE IMAGE SEARCH`.
- Pressed `ADD IMAGES TO REVERSE-SEARCH CORPUS`.
- Application terminated immediately.

Diagnostics:
- `APP_CRASH / UNCAUGHT_EXCEPTION`
- `android.content.ActivityNotFoundException: No Activity found to handle Intent { (has extras) }`
- Reverse path: `ReverseImageSearchActivity.onCreate$lambda$3`.
- Advanced path: `AdvancedVisualIntelligenceActivity.onCreate$lambda$2`.

Root cause:
- `BulkImagePickerActivity` was present in AndroidManifest, but `BulkImagePickerActivity.launchIntent()` returned a bare `Intent()` containing only extras.
- `ActivityResultContracts.StartActivityForResult` therefore attempted to resolve an implicit intent with no action/component and Android found no handler.

Fix:
- Commit `377d7c36a687a04d237652ea4262f5b0f0899013` changed `BulkImagePickerActivity.launchIntent()` to an explicit intent targeting `com.example.personalmemoryai.ui.BulkImagePickerActivity`.
- Added regression test `app/src/test/java/com/example/personalmemoryai/ui/BulkImagePickerIntentTest.kt` in commit `52dd932401c6ea24a2ef67f2cb0327782c195cbc` to assert the returned Intent component and title extra.

Runtime verification requirement:
- A successful Gradle build cannot prove ActivityResult routing.
- The next installed APK must explicitly test both Reverse Image and Advanced `ADD IMAGES` buttons and confirm that the in-app bulk picker opens.

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

## Current architecture and progress

### Shared ingestion/indexing

`BulkImagePickerActivity` pages MediaStore metadata and writes a queue file in app-private storage. It must launch explicitly from both Reverse and Advanced screens.

`ImageCorpusImportWorker` streams the queue file, processes bounded batches with bounded concurrency, creates durable app-private image copies, validates dimensions using bounds-only decoding, isolates item failures, inserts accepted corpus records in batches, and publishes persistent progress.

`UnifiedVisualIndexWorker` invokes `OptimizedUnifiedVisualIndexService` as foreground WorkManager work.

The optimized indexer performs one decode per image, computes Haar + Classical V4 + Advanced V2 from the same bitmap, uses bounded four-way CPU parallelism, persists feature batches transactionally, and checkpoints operation state in Room.

### Advanced Visual Intelligence

Independent screen, independent V2 feature storage, independent results and explanations. Current engine family includes:
- multi-scale grayscale structure;
- global and spatial color;
- global/spatial LBP texture;
- gradient orientation and magnitude;
- spatial layout/edge signature;
- illumination-robust structure;
- entropy and aspect ratio;
- regional consistency;
- multiscale structural consensus;
- 7 query variants: original, 90°, 180°, 270°, center crops 92/82/72%;
- cross-signal contradiction penalties;
- confidence separated from similarity;
- expandable `WHY THIS RESULT` evidence panel.

Advanced retrieval unions Advanced candidates with candidates from the existing Classical/Haar path before final fusion so one engine cannot suppress recall from the other.

## Device testing state

The user has now begun runtime testing. The first discovered fatal error was the bulk-picker ActivityNotFoundException above. Do not declare the app runtime-ready until this path is tested after the explicit-intent fix.

## Build status

CI #146 successfully built and uploaded a debug APK before the bulk-picker fix. Subsequent commits require their own CI validation. A green build proves compilation/package correctness but does not prove runtime integration.

## Required next sequence

1. CI for the explicit bulk-picker fix + regression test.
2. Install that exact APK.
3. Test `ADD IMAGES` from both Reverse Image and Advanced.
4. Test picker permission path and large multi-select.
5. Test import worker and shared index start/resume.
6. Test rotation, screen-off, backgrounding and process recreation.
7. Test repeated searches and result display.
8. Test Advanced explainability/evidence.
9. Only after runtime stability, execute the full accuracy/performance benchmark.

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
