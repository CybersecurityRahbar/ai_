# Personal Memory AI — Project Progress Log

## 2026-08-27 — Native system file/gallery picker added without removing bulk gallery

### User-requested picker architecture

The existing paged MediaStore bulk picker is retained. It is NOT replaced.

A second source is now available from the same `BulkImagePickerActivity`:

- `OPEN SYSTEM FILES / GALLERY`
- Uses Android `ActivityResultContracts.OpenMultipleDocuments()` with `image/*`.
- Opens the native system document experience so users can choose from Gallery, Files, folders, and other document providers exposed by Android.
- No application-defined 500/1000 selection cap is imposed by this route.
- Provider/UI limits, if any, remain platform/provider limits.
- Selected document URIs are deduplicated and converted into the same app-private `.uris` queue used by the existing ingestion pipeline.
- Persistent read permission is attempted with `takePersistableUriPermission`; providers that do not support it are handled without crashing.
- Reverse Image and Advanced both continue to use the same `BulkImagePickerActivity`, so the new route is automatically available to both top-level sections.

### Implementation commits

- `f396a51c2563b627f62c8c8c16a0c1ebc234bf50` — system multi-document picker implementation.
- `367756339658898f9ae39ae4f5f27b13a58466c2` — picker UI button/subtitle.
- `34726b69b80bac0f81dbf8ef2c8b4c8aecf6ddc4` — persistent context ledger update.

### Existing picker retained

`BulkImagePickerActivity` still provides:

- paged MediaStore browsing at 100 items per page;
- `SELECT ALL`;
- `LOAD NEXT 100`;
- `ADD SELECTED`;
- the explicit Activity component fix for `launchIntent()` that resolved the prior `ActivityNotFoundException`.

### Shared ingestion remains unchanged

Both picker sources ultimately produce the same `RESULT_QUEUE_FILE` and feed `ImageCorpusImportWorker`.

No duplicate image decode or second indexing architecture is introduced.

## 2026-08-26 — Advanced Visual Intelligence feature-complete candidate

### Architecture locked by user

`IntelligenceHomeActivity` remains the sole Android launcher and central shell.

Top-level visual sections:

- `LOCAL REVERSE IMAGE SEARCH` → `ReverseImageSearchActivity` — existing full-strength classical search.
- `ADVANCED VISUAL INTELLIGENCE` → `AdvancedVisualIntelligenceActivity` — independent screen in the same PMAI design language.

Advanced is NOT a hidden mode inside Reverse Image and NOT a second application.

### Shared corpus / independent feature stores

`source URI → one durable local copy → one decode/normalization pass → independent engine outputs`.

Reverse Image retains its own versioned Haar/Classical/AKAZE/SIFT feature stores. Advanced retains `advanced_visual_fingerprints`. User imports the corpus once.

### Reverse Image strength invariant

- DigiKam-style Haar/Wavelet `DIGIKAM-HAAR-128-40-YIQ-V2`.
- pHash.
- dHash.
- HSV256.
- Spatial Sobel/shape.
- Persisted AKAZE descriptors.
- AKAZE mutual matching + RANSAC.
- SIFT mutual matching + RANSAC.
- Rotation variants.
- Crop variants.
- Full-corpus global retrieval.
- 64 global shortlist.
- AKAZE/RANSAC across all 64.
- SIFT/RANSAC across all 16 final candidates.

**Never reduce 64/16 or weaken the existing engine for performance.**

MobileCLIP/neural semantic search remains postponed.

### Device baseline

Target device: Android 12 / SM-G981U.

999-image corpus is intentional and correct. Larger corpora including 1120 have been used. Search became materially faster after staged retrieval and bounded concurrency, while indexing remains the heavier task.

### Shared ingestion/indexing

Components:

- `BulkImagePickerActivity`
- `ImageCorpusImportWorker`
- `ImageCorpusImportScheduler`
- `UnifiedVisualIndexWorker`
- `OptimizedUnifiedVisualIndexService`
- `VisualIndexBatchDao`
- `VisualIndexOperationEntity/Dao`

Strategy:

`paged metadata → queue file → streaming import → bounded URI copy/validation → shared corpus → batch 16 → up to 4 workers → one decode → Haar + Classical + Advanced → one Room transaction → durable checkpoint`.

The design targets rotation/background/screen-off durability; final process-death behavior must be validated on the device.

### Advanced Visual Intelligence V2

Engine: `ADVANCED-VISUAL-CLASSICAL-V2`

Persistent signals:

- 16×16 grayscale multi-scale structure;
- global RGB/saturation moments;
- 4×4 spatial RGB/saturation;
- 256-bin LBP;
- 4×4 spatial LBP-transition texture;
- 24-bin gradient orientation;
- gradient magnitude;
- 8×8 edge/layout;
- 16×16 illumination-robust local contrast/variance;
- entropy;
- aspect ratio.

V2 uses cross-signal consensus and explicit contradiction penalties.

### Advanced recall/fusion

Advanced evaluates its own full-corpus V2 evidence and keeps its top 64. It unions those IDs with the existing Reverse Image top 64 before final fusion. This preserves cross-engine recall.

### Query variants

- original;
- 90°;
- 180°;
- 270°;
- center crop 92%;
- center crop 82%;
- center crop 72%.

Best variant is retained as provenance per result.

### Regional Consistency

`AdvancedRegionConsistencyVerifier` checks corresponding spatial regions using stored V2 signatures:

- pooled 8×8 structural agreement;
- 4×4 spatial color;
- 4×4 spatial texture;
- 8×8 layout;
- stable-region ratio;
- cross-signal disagreement.

It adds no second image decode or persistent image copy.

### Multiscale Structural Consensus

`AdvancedStructuralConsensusEngine` checks coarse/fine structural agreement and layout using existing V2 signatures.

### Retrieval performance

All seven query variants compare against the full Advanced corpus with bounded four-way CPU concurrency. `itemId → Fingerprint` is built once to avoid repeated lookup scans. No accuracy-bearing candidate stage was reduced.

### Evidence Gate / confidence

Final Advanced ranking combines:

`Classical/Haar + Advanced V2 + Regional Consistency + Multiscale Structural Consensus`.

Penalties cover weak consensus, color/structure conflict, texture/structure conflict, weak regional stability, spatial disagreement, and strong-coarse/weak-fine conflict.

`confidencePercent` is an evidence-strength heuristic, not a probability and not statistically calibrated.

### Explainability UX

`AdvancedVisualResultAdapter` now has:

- compact result summary;
- expandable `WHY THIS RESULT ▸` control;
- detailed component breakdown;
- actual Haar/pHash/dHash/classical evidence;
- Advanced component evidence;
- regional/structural evidence;
- contradiction/reason codes;
- winning query variant.

RecyclerView state is reset on bind so expanded state cannot leak between results. Card tap opens the image; Why control only expands details.

### Database

Database version 12 stores the V2-specific fields:

- `spatialColor`;
- `spatialLbp`;
- `gradientMagnitude`;
- `illuminationRobustStructure`.

Migration `11 → 12` changes only Advanced fingerprint storage; shared corpus remains intact.

### Final benchmark gate

A controlled device benchmark is stored at:

`docs/ADVANCED_VISUAL_FINAL_BENCHMARK_PLAN.md`

It covers accuracy, false positives, performance, large-scale indexing, lifecycle, and release acceptance. It explicitly forbids claiming calibration or improvement without measurements.

### Runtime correction previously found

The first comprehensive device installation exposed an `ActivityNotFoundException` on the Add Images path because `BulkImagePickerActivity.launchIntent()` returned a bare `Intent()` with extras only. That was fixed by explicit component routing, and a regression test was added.

## Current CI status

CI #149 successfully built and uploaded a debug APK after the explicit bulk-picker Intent fix. The newer system-picker commits above require their own CI validation. A green build proves compilation/package correctness but does not prove runtime integration.

## Next phase — device validation after system-picker CI

1. Verify the CI run for the system-picker commits.
2. Install one exact green APK.
3. Test `ADD IMAGES` from both Reverse Image and Advanced.
4. Inside the picker, test both:
   - existing paged local gallery;
   - new `OPEN SYSTEM FILES / GALLERY` route.
5. Select images from Gallery and Files/folders and verify queue/import.
6. Test large multi-select without an app-defined 500/1000 cap.
7. Test shared indexing, rotation, backgrounding, screen-off, process recreation and resume.
8. Test repeated searches and Advanced `WHY THIS RESULT` explanations.
9. Run the full controlled accuracy/performance benchmark.

## Permanent constraints

- **999 is valid and intentional.**
- Never reduce Reverse Image 64 shortlist or 16 SIFT.
- Never weaken/remove Haar/pHash/dHash/HSV256/Sobel/AKAZE/RANSAC/SIFT.
- Never create a second launcher/application.
- Never make Advanced a hidden Reverse Image mode.
- Never require duplicate corpus import/fetch/decode.
- Never pass thousands of URIs through an Intent.
- Never let one bad URI abort a batch.
- Never let normal Activity destruction cancel durable indexing.
- Never call aggregate score Haar.
- Never call confidence a probability.
- Never claim accuracy/performance improvement without controlled measurements.
- Never add algorithms merely for novelty; each must have a role, representation, metric, cost, and benchmark value.
- Read `PROJECT_CONVERSATION_CONTEXT.md` and this file before architecture changes.
