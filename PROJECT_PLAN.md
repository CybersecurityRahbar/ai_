# Personal Memory AI — Project Plan

> Persistent execution reference. Read this document before making project changes. Update it whenever the architecture, roadmap, or implementation status changes.

## 1. Mission

Build a local-first Android Personal Memory / Evidence Intelligence system that indexes images on-device and lets the user retrieve them using OCR, metadata, objects, faces, visual embeddings, and dedicated visual-search engines. No cloud is required for the core pipeline.

## 2. Current application architecture

- `database/`: Room persistence for images, objects, faces, persons, embeddings, reverse-image fingerprints, and Advanced Visual fingerprints.
- `indexing/`: ingestion and durable indexing orchestration. This now includes the shared `UnifiedVisualIndexWorker` and scheduler.
- `semantic/`: MobileCLIP-S2 image embeddings and cosine-similarity search. Intentionally untouched during the current classical/advanced visual phase.
- `vision/`: face detection/landmarks/quality/pose/embeddings/matching/clustering.
- `intelligence/`: evidence and multi-signal analysis components.
- `diagnostics/`: stage health and execution telemetry.
- `ui/`: Android screens for data, evidence, faces, objects, OCR, intelligence, image viewing, Reverse Image Search, Advanced Visual Intelligence, and scalable local image selection.

### Top-level visual search sections

The central `IntelligenceHomeActivity` is the single application shell and sole `MAIN/LAUNCHER` activity.

It contains two separate visual-search sections:

1. **LOCAL REVERSE IMAGE SEARCH**
   - Existing classical DigiKam-style system.
   - Own Activity and own existing UI.
   - Existing algorithms and search behavior remain intact.

2. **ADVANCED VISUAL INTELLIGENCE**
   - New independent Activity and independent UI section.
   - Same PMAI colors/panels/buttons/layout language.
   - Appears as a distinct action in the central Command Center.
   - Has its own feature table, engine version, result model, and explainability UI.
   - It is NOT a hidden mode inside Reverse Image Search and is NOT a second Android application.

## 3. Shared corpus / independent feature indices

The two visual-search sections share one durable local image corpus so the same image is not fetched/copied/decoded twice merely because two engines need it.

Conceptual pipeline:

`Shared image corpus -> one durable local copy -> one decode/normalization pass -> independent feature families`

Existing Reverse Image index tables remain separate from the Advanced table.

### Reverse Image persistent stack

- DigiKam-style Haar/Wavelet fingerprint: 128x128, RGB->YIQ, separable 2-D Haar, 40 strongest signed coefficients per channel, Y/I/Q averages, digiKam WeightBin/weights, best/worst normalization.
- 64-bit pHash with DC excluded.
- 64-bit dHash using complete 9x8 luminance comparison grid.
- 256-bin L1-normalized HSV histogram: 16 Hue × 4 Saturation × 4 Value.
- 128-bin spatial Sobel gradient/edge signature over 4x4 spatial regions.
- Persisted AKAZE binary descriptors and keypoints.
- Shortlist AKAZE mutual matching + ratio test + homography RANSAC.
- Shortlist SIFT verification with BFMatcher L2, mutual consistency, ratio test, homography RANSAC.
- Query original + centered crops + 90°/180°/270° rotations.

### Advanced Visual persistent stack V1

`AdvancedVisualFingerprintEngine` produces deterministic non-neural fingerprints:

- 16x16 multi-scale grayscale structural map.
- RGB/color moments and saturation statistics.
- 256-bin Local Binary Pattern texture histogram.
- 24-bin gradient-orientation histogram weighted by magnitude.
- 8x8 spatial layout/edge signature.
- normalized grayscale entropy.
- aspect-ratio consistency.

Every feature family has a version. Advanced V1 is stored in `advanced_visual_fingerprints` and can be rebuilt independently from later versions.

## 4. Shared indexing contract

`ReverseImageSearchService.buildIndex()` is currently the shared producer of the reverse-image and Advanced V1 features. For each image it performs one bitmap decode and computes:

`Haar + Classical V4 + Advanced V1`

then persists each family separately.

This is an intentional shared-ingestion optimization. The UI sections stay separate; the computation path is shared.

### Non-negotiable accuracy invariant

The existing Reverse Image path must never be weakened to make the shared pipeline faster.

- Full corpus global retrieval.
- 64 shortlist candidates.
- AKAZE/RANSAC across all 64.
- 16 SIFT/RANSAC candidates.
- No removal/reduction of existing descriptor stages, query variants, or geometric validation.

Performance optimizations must target decode reuse, normalized buffers, batching, memory control, native allocation reuse, I/O, and CPU scheduling.

## 5. Durable large-scale indexing

Long-running indexing is user-initiated work and must not be tied to an Activity coroutine.

Current implementation:

- `UnifiedVisualIndexWorker` (`CoroutineWorker`) executes the shared visual indexing pass.
- `VisualIndexWorkScheduler` uses a unique WorkManager work name to prevent duplicate simultaneous index operations.
- WorkManager progress is persisted (`processed`, `total`, `indexed`, `skipped`, `failed`, `localFeatures`, percent).
- A foreground notification is used for long-running local file processing.
- Manifest declares `FOREGROUND_SERVICE_DATA_SYNC` and merges the WorkManager `SystemForegroundService` as `dataSync`.

Android's current guidance supports WorkManager long-running workers and foreground execution for important long-running local processing; Android 12+ also restricts arbitrary background foreground-service launches. citeturn984758search2turn984758search1turn366781search0

Important remaining hardening:

- Add explicit chunk checkpoints and durable operation history.
- Add memory-bounded multi-image workers.
- Batch Room transactions.
- Persist stage-level latency.
- Ensure process-death resume from committed versions without restarting finished work.

## 6. Scalable local image picker

The system DocumentsUI multiple-selection path is not sufficient for the target corpus scale. The project now has `BulkImagePickerActivity`:

- loads image metadata in pages of 100;
- keeps only URI strings/selections, not decoded image bitmaps;
- supports select-all by querying MediaStore IDs across available volumes;
- returns a large URI list to the caller only after selection is finished;
- requests appropriate image-read permission for Android version.

This picker is used by both Reverse Image and Advanced Visual sections for corpus ingestion.

Remaining work:

- convert the add/import stage itself into chunked background ingestion;
- keep URI lists streamed into durable work rather than treating 5,000–6,000 entries as one large Activity transaction;
- add per-item import diagnostics and retry/quarantine for corrupt providers/files.

## 7. Explainable similarity system

Advanced Visual results must answer two questions:

1. **Why was this image returned?**
2. **Why did it receive approximately 40% instead of 80%?**

The result model must preserve component provenance instead of only displaying an opaque final number.

### Existing classical evidence to expose

- Haar score/contribution.
- pHash/dHash distances/scores.
- HSV/color score.
- edge/shape score.
- AKAZE good matches and RANSAC inliers.
- SIFT good matches and RANSAC inliers.

### Advanced evidence to expose

- overall Advanced score;
- multi-scale structure;
- color moments/distribution;
- LBP texture;
- gradient orientation;
- spatial layout;
- entropy/aspect consistency;
- explicit evidence reason codes.

The displayed final percentage must be reproducible from the stored components. Contradictory evidence and weak matches must be representable as penalties/reasons in later versions rather than hidden.

## 8. Advanced fusion policy

Advanced V1 currently fuses:

`existing full-strength classical evidence 65% + Advanced V1 evidence 35%`

This is an experimental baseline only. No accuracy claim is made from the weight. Controlled device benchmarks must justify later adjustments.

The Advanced section should preserve the existing classical result evidence rather than replace it. Advanced V1 is an additional analytical layer.

## 9. Accuracy roadmap

The classical stack remains the anchor because it has already shown useful results on-device.

Next measurement-driven work:

1. Benchmark exact same image/content.
2. JPEG recompression.
3. Resize.
4. Screenshot/UI chrome.
5. mild crop.
6. large crop.
7. brightness/color modification.
8. burst/near-duplicate.
9. unrelated images.
10. same object/scene from another viewpoint.
11. perspective transformation.
12. 90°/180°/270° rotation.
13. source image embedded inside screenshot.
14. source image embedded inside unrelated larger image.

For every test record Top-10 and every component score. Do not claim an accuracy percentage without measured on-device data.

## 10. Future Advanced engine roadmap

After V1 is benchmarked, new classical engines may be added only when they close a demonstrated failure mode. Candidate families include:

- multi-resolution gradient/structure pyramids;
- illumination-normalized color distributions;
- more robust texture descriptors;
- contour/shape descriptors;
- local-feature consensus improvements;
- region-level and object-layout fingerprints;
- duplicate/near-duplicate specialized hashes;
- stronger geometric verification;
- later, separately versioned learned visual representations if explicitly allowed by the project phase.

No algorithm is added purely for complexity. Each must define storage, cost, comparison, false-positive behavior, and benchmark purpose.

## 11. MobileCLIP and neural semantic path

Do not modify or replace MobileCLIP during the current phase. It remains a separate engine and future project phase.

## 12. Persistent context rule

- Read `PROJECT_PLAN.md` and `PROJECT_PROGRESS.md` before project changes.
- Update `PROJECT_PROGRESS.md` after meaningful implementation/testing.
- Keep architecture/roadmap here.
- Keep chronological facts in `PROJECT_PROGRESS.md`.
- Never infer runtime quality from source existence or build success alone.
