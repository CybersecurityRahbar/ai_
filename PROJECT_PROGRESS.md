# Personal Memory AI — Project Progress Log

## 2026-08-26 — Durable streaming corpus import implemented; batch indexing next

### Architecture locked by user

`IntelligenceHomeActivity` remains the sole launcher and central shell.

Top-level visual sections:

- `LOCAL REVERSE IMAGE SEARCH` → `ReverseImageSearchActivity` — existing classical engine stack.
- `ADVANCED VISUAL INTELLIGENCE` → `AdvancedVisualIntelligenceActivity` — separate screen, same PMAI design language.

Advanced Visual Intelligence is NOT a mode inside Reverse Image Search and NOT a second application.

### Shared corpus / independent indices

User requirement: images must not be fetched/imported/decoded twice merely because two visual sections need them.

Locked architecture:

`shared corpus → one durable local copy → one decode/normalization pass → independent feature indices`

Reverse Image keeps its own Haar/Classical/AKAZE data. Advanced keeps `advanced_visual_fingerprints` with its own engine version.

The shared indexing pass currently computes Haar + Classical V4 + Advanced V1 from the same Bitmap.

### Existing classical Reverse Image stack — MUST NOT weaken

- DigiKam-style Haar/Wavelet `DIGIKAM-HAAR-128-40-YIQ-V2`.
- pHash.
- dHash.
- HSV256.
- Spatial Sobel/shape.
- Persisted AKAZE descriptors.
- AKAZE mutual matching + RANSAC.
- SIFT local verification + RANSAC.
- Rotation query variants.
- Crop query variants.
- Full-corpus global retrieval.
- 64 global shortlist candidates.
- AKAZE/RANSAC verification across all 64.
- SIFT/RANSAC verification across all 16 final candidates.

**Hard user rule:** never reduce shortlist size, verifier count, descriptor strength, or algorithmic coverage as a speed shortcut. Speed must come from execution optimization, not recall loss.

MobileCLIP/neural semantic search is explicitly postponed and untouched in this phase.

### User device validation

Target: Android 12 / SM-G981U.

User confirmed:

- 999-image corpus test was intentional/correct.
- 1120-item corpora have also been observed.
- Current classical search quality is good for visual similarity and has improved.
- Search still returns some weak/unrelated images; stronger discrimination and explainability are required.
- Current search became much faster than the earlier >10 minute implementation.
- Main remaining performance bottleneck is indexing (`BUILD HAAR INDEX` / multi-fingerprint generation), not image selection.
- Observed indexing has been approximately 3–5 seconds/image on some runs.

### Previously fixed defects

- Duplicate Reverse Image launcher removed; `IntelligenceHomeActivity` is sole launcher.
- Results `content://` crash fixed by durable app-private copies and local display paths.
- Repeated-search crash fixed by clearing old results and robust local result paths.
- Large selection now uses in-app paged `BulkImagePickerActivity` instead of relying on a system multi-select cap.
- Large URI lists no longer travel through `Intent`; picker writes a queue file and returns only the file path.
- Previous Kotlin compile errors in AKAZE/coroutine/queue integration were fixed and subsequent CI builds passed.

### Advanced Visual Intelligence V1 currently implemented

New independent engine produces deterministic, non-neural signals:

- 16×16 multi-scale grayscale structure.
- RGB/color moments and saturation statistics.
- 256-bin LBP texture histogram.
- 24-bin gradient-orientation histogram weighted by magnitude.
- 8×8 spatial layout/edge signature.
- normalized grayscale entropy.
- aspect-ratio consistency.

Advanced V1 exposes component scores and human-readable reason codes.

### Explainable results

Advanced result model separates the true Haar component from the overall score and preserves:

- final score;
- existing classical score;
- true Haar score;
- pHash/dHash;
- color/edge/local evidence;
- RANSAC inliers;
- Advanced score;
- structure/color/texture/gradient/layout components;
- reason codes.

The displayed percentage must be reconstructible from stored evidence. No post-hoc fabricated explanation.

### Durable shared indexing

Implemented:

- `UnifiedVisualIndexWorker` — foreground WorkManager task with persisted WorkManager progress.
- `VisualIndexWorkScheduler` — unique shared indexing operation.
- Both Reverse Image and Advanced screens observe the same operation.
- Activity recreation does not own the indexing operation.

The current worker runs shared Haar + Classical + Advanced generation.

### NEW — Durable large-batch image ingestion

Implemented:

- `BulkImagePickerActivity`: paged local gallery metadata browsing and selection.
- Queue-file transport: selected URIs are written to app-private storage before returning to caller.
- `ImageCorpusImportWorker`: durable foreground WorkManager worker for queue ingestion.
- `ImageCorpusImportScheduler`: schedules unique import work.

The import worker now **streams queue lines** and does not load thousands of URI strings into a memory list. It performs one URI at a time with per-item failure isolation. It copies each accepted source to the durable local library and uses `BitmapFactory.Options.inJustDecodeBounds` to validate dimensions without fully decoding pixels during import.

Both `ReverseImageSearchActivity` and `AdvancedVisualIntelligenceActivity` now schedule the durable import worker instead of reading the entire queue and calling `addImages(List<Uri>)` from an Activity coroutine.

This is intended to support 5,000–6,000+ image selections without a giant Intent/list/bitmap workload.

### IMPORTANT remaining scale work

The following is intentionally NOT marked complete yet:

1. Persist explicit import operation checkpoints/history beyond WorkManager progress so process death can resume from a precise queue position without restarting from the beginning.
2. Replace per-item `findByUri` + insert with true batched corpus persistence where safe.
3. Replace per-image fingerprint DB writes with **actual batch Room transactions** used by the worker/service (unused DAO methods do not count).
4. Make indexing restart/resume checkpoint durable and user-visible.
5. Add stage timings for copy, bounds decode, full decode, Haar, Classical V4, AKAZE extraction, Advanced V1, DB write, and total item time.
6. Add memory boundedness/CPU concurrency measurement on large corpora.
7. Dedicated ContentResolver provider/corrupt-image recovery tests.
8. Device testing for rotation, backgrounding, screen-off, process recreation, and long multi-thousand-image operations.

### Performance rule for the next implementation

The current shared index already uses bounded parallel CPU processing and preloads existing fingerprint maps. The next speed work must preserve identical fingerprint outputs and descriptor counts while reducing DB round-trips and allocation overhead.

Target indexing architecture:

`queue/chunk → bounded workers → one decode per image → Haar + Classical V4 + Advanced V1 → batch transaction → durable checkpoint → next chunk`

### Next engineering phase — do this before adding many more Advanced engines

**Phase A: finish scale/durability**

- Durable queue checkpoint/resume.
- Batched corpus writes.
- Batched feature persistence.
- Rotation/process-death/background/screen-off verification.

**Phase B: index performance**

- Shared decode/normalization audit.
- Bounded parallel workers tuned to memory.
- Native OpenCV allocation reuse.
- Stage-level telemetry.
- Equivalence checks against current V4 fingerprints.

**Phase C: Advanced Visual Intelligence expansion**

Only after A/B stabilize:

- richer multi-scale structure;
- improved spatial color/layout;
- robust texture and gradient statistics;
- stronger local geometric consensus;
- additional classical duplicate/near-duplicate signatures;
- explicit evidence fusion and contradiction penalties.

Do NOT add random algorithms. Each engine needs a defined purpose, fingerprint/storage schema, comparison metric, cost, failure modes, and benchmark value.

### Explainability target

For each Advanced result, the UI must eventually answer:

- Why did this image appear?
- Which signals agreed?
- Which signals disagreed?
- Why is the score 40% rather than 80%?
- Is the match global, local, geometric, color-only, structural, or mixed?

The system should expose actual metrics such as matched descriptors, RANSAC inliers, spatial coverage, color distance, structure similarity, and contradiction penalties.

### CI status/history

Recent builds successfully produced debug APKs after the V4/full-strength performance changes. The latest durable-import commits are being validated by GitHub Actions; no version is considered device-ready until its own CI run succeeds.

### Permanent constraints

- Never create a second launcher for Reverse Image or Advanced Visual Intelligence.
- Never make Advanced a hidden sub-mode of Reverse Image.
- Never make the user import the same corpus twice.
- Never weaken the current 64/16 classical search to obtain speed.
- Never call the overall score “Haar”.
- Never claim accuracy improvement without measured on-device evidence.
- Never claim background durability is complete until rotation, screen-off, backgrounding, and process recreation are tested.
- Read `PROJECT_CONVERSATION_CONTEXT.md` and this file before continuing reverse-image/indexing work.
