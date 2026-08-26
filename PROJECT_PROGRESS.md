# Personal Memory AI — Project Progress Log

## 2026-08-26 — Advanced Visual Intelligence analytical layer and shared indexing foundation

### Architecture locked by user

`IntelligenceHomeActivity` remains the sole Android launcher and central shell.

Top-level visual sections:

- `LOCAL REVERSE IMAGE SEARCH` → `ReverseImageSearchActivity` — existing full-strength classical search.
- `ADVANCED VISUAL INTELLIGENCE` → `AdvancedVisualIntelligenceActivity` — independent screen in the same PMAI design language.

Advanced is NOT a hidden mode inside Reverse Image and NOT a second application.

### Shared corpus / independent feature stores

`source URI → one durable local copy → one decode/normalization pass → independent engine outputs`.

Reverse Image retains its existing versioned Haar/Classical/AKAZE/SIFT feature stores. Advanced retains the independent `advanced_visual_fingerprints` store. A user imports the corpus once for both.

### Reverse Image strength invariant — MUST NOT be weakened

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
- Full corpus global retrieval.
- 64 global shortlist.
- AKAZE/RANSAC on all 64.
- SIFT/RANSAC on all 16 final candidates.

Never reduce these to improve speed. Performance optimization must remove redundant work, I/O, allocations, and scheduling overhead.

MobileCLIP/neural semantic search remains postponed.

### Device facts

Target: Android 12 / SM-G981U.

The original 999-image corpus is intentional and correct. The user has also worked with corpora over 1000 images. The user reports materially improved search versus the original implementation, but some irrelevant/weak results remain. Indexing remains more expensive than desired.

### Previously fixed defects

- Duplicate launcher removed.
- Transient DocumentsProvider result-display crash removed through durable local copies.
- Repeated-search crash fixed.
- Large URI lists removed from Intents.
- Paged/queue-based image ingestion added.
- Streaming import worker and per-item failure isolation added.
- Rotation/background/screen-off work moved toward WorkManager + durable state.
- Shared decode and batch visual indexing added.
- Batch Room persistence added.
- Persistent visual-index operation/checkpoint records added.
- V2 schema/constructor/diagnostics compile regressions were fixed through CI iterations.

### Scale ingestion/indexing foundation

Components:

- `BulkImagePickerActivity`
- `ImageCorpusImportWorker`
- `ImageCorpusImportScheduler`
- `UnifiedVisualIndexWorker`
- `OptimizedUnifiedVisualIndexService`
- `VisualIndexBatchDao`
- `VisualIndexOperationEntity/Dao`

Ingestion strategy:

`paged metadata → queue file → streaming import worker → bounded URI copy/validation → batched corpus insert`.

Visual indexing strategy:

`batch 16 → up to 4 CPU workers → one decode per image → Haar + Classical V4 + Advanced V2 → batch Room transaction → durable checkpoint`.

The architecture is intended to survive Activity rotation and normal background/screen-off conditions. Process-death resume still requires device validation and any further hardening that tests reveal.

### Indexing performance rules

Observed historical throughput has been roughly 3–5 seconds/image in some on-device runs. Do not reduce feature strength to improve this. Optimize:

- shared decode/normalization;
- batched writes;
- bounded CPU concurrency;
- native/OpenCV allocations;
- redundant I/O;
- cache/version checks;
- durable chunking/checkpointing;
- stage-level timing.

### Advanced Visual Intelligence V2

Engine version:

`ADVANCED-VISUAL-CLASSICAL-V2`

Persistent signals:

- 16×16 multi-scale grayscale structure;
- global RGB/saturation moments;
- 4×4 spatial RGB/saturation distributions;
- full 256-bin LBP texture histogram;
- 4×4 spatial LBP-transition texture signature;
- 24-bin gradient-orientation histogram;
- independent gradient-magnitude histogram;
- 8×8 spatial edge/layout signature;
- 16×16 illumination-robust local contrast/variance signature;
- entropy;
- aspect ratio.

V2 applies multi-signal consensus and contradiction penalties. Strong color or texture alone cannot freely inflate a result when structural evidence disagrees.

### Advanced candidate recall/fusion

Advanced search evaluates its own top 64 and unions those IDs with the existing Reverse Image top 64 before final fusion. This preserves recall across the two engine families. Existing Reverse Image retrieval coverage remains intact.

### Advanced query variants

The Advanced query can use:

- original;
- 90°;
- 180°;
- 270°;
- center crop 92%;
- center crop 82%;
- center crop 72%.

The winning variant is stored in the evidence and shown to the user.

### Advanced region-consistency layer — NEW

`AdvancedRegionConsistencyVerifier.kt` is an independent deterministic verifier using already stored V2 data; it adds no new image fetch/decode and no new persistent index.

It checks corresponding spatial regions using:

- down-pooled 8×8 structural agreement derived from the 16×16 grayscale signature;
- 4×4 spatial-color agreement;
- 4×4 spatial-texture agreement;
- 8×8 layout agreement;
- stable-region ratio;
- cross-signal disagreement.

This is specifically intended to suppress false positives where global color/texture is similar but spatial arrangement is not.

### Advanced multi-scale structural consensus — NEW

`AdvancedStructuralConsensusEngine.kt` adds a model-free coarse/fine structural agreement check and layout agreement. It reuses the existing 16×16 grayscale and 8×8 layout signatures; it does not add a second image analysis pass or a second index.

The service now uses:

`Advanced V2 score + Regional Consistency + Multi-scale Structural Consensus + existing Classical/Haar evidence`

with explicit penalties for weak region alignment, strong coarse but weak fine structure, and spatial-signal disagreement.

### Explainability / confidence

Advanced result evidence now includes:

- final similarity;
- separate confidence percentage;
- Classical/Haar evidence;
- Advanced V2 evidence;
- regional consistency;
- stable-region coverage;
- spatial disagreement;
- structural consensus;
- coarse and fine structure;
- best query variant;
- reason codes.

`similarity` and `confidence` are intentionally separate. Confidence is a heuristic evidence-strength indicator, NOT statistical calibration. No statistical accuracy claim is allowed until controlled device benchmark data exists.

### Database

Database version 12 contains Advanced V2 fields:

- `spatialColor`
- `spatialLbp`
- `gradientMagnitude`
- `illuminationRobustStructure`

Migration `11 → 12` handles the new fields. Existing corpus remains shared.

### Current build status

CI #128 succeeded after the previous Advanced V2 compile fixes. The later region/structural consensus commits have triggered fresh CI and must be verified before device use.

## Immediate next sequence

1. Verify fresh CI for the Advanced region/structural commits.
2. Complete the Advanced explainability UX with expandable evidence details if required by the screen layout.
3. Harden large-batch/background/rotation behavior based on actual device testing.
4. Benchmark indexing at 1k → 5k → 6k without lowering algorithmic strength.
5. Benchmark Advanced against exact, recompressed, resized, screenshot, crop, rotation, lighting/color changes, burst near-duplicates, unrelated images, viewpoint/perspective, and image-inside-screenshot cases.
6. Only after measurements, tune score calibration/confidence bands.

### Permanent user constraints

- **999 is valid and intentional.**
- Never reduce Reverse Image `64` shortlist or `16` SIFT.
- Never remove or weaken Haar/pHash/dHash/HSV256/Sobel/AKAZE/RANSAC/SIFT.
- Never create a second launcher/application.
- Never make Advanced a hidden Reverse Image mode.
- Never require the user to import/fetch/decode the corpus twice.
- Never pass thousands of URIs through an Intent.
- Never allow one bad media item to abort a large batch.
- Never let Activity destruction cancel durable indexing.
- Never call an aggregate score Haar.
- Never claim accuracy or performance improvement without measurement.
- Never add algorithms only for novelty; each needs a defined role, representation, metric, cost, and benchmark value.
- Read `PROJECT_CONVERSATION_CONTEXT.md` and this file before architecture changes.
