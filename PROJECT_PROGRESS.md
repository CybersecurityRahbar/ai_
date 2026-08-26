# Personal Memory AI — Project Progress Log

## 2026-08-26 — Advanced Visual Intelligence V2 + durable shared indexing foundation

### Architecture locked by user

`IntelligenceHomeActivity` remains the sole Android launcher and central shell.

Top-level visual sections:

- `LOCAL REVERSE IMAGE SEARCH` → `ReverseImageSearchActivity` — existing full-strength classical search.
- `ADVANCED VISUAL INTELLIGENCE` → `AdvancedVisualIntelligenceActivity` — independent screen in the same PMAI design language.

Advanced is NOT a hidden mode inside Reverse Image and NOT a second application.

### Shared corpus / independent feature stores

Locked design:

`source URI → one durable local copy → one decode/normalization pass → independent engine outputs`

Reverse Image retains Haar/Classical/AKAZE/SIFT evidence. Advanced retains its own versioned `advanced_visual_fingerprints` index. One user import/fetch is enough for both sections.

### Existing Reverse Image stack — MUST NOT be weakened

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
- 64 global shortlist candidates.
- AKAZE/RANSAC across all 64.
- SIFT/RANSAC across all 16 final candidates.

Never reduce these as a performance shortcut. Performance must come from execution/I/O/allocation/scheduling optimization.

MobileCLIP/neural semantic search remains postponed and untouched.

### Device validation facts

Target device: Android 12 / SM-G981U.

The user's 999-image test corpus is intentional and correct. Larger corpora including 1120 images have also been observed.

User reports current classical search is materially better than the original version and usually finds visually similar images, but some weak/unrelated results remain. Search time is now much better than the earlier >10-minute behavior. The main remaining bottleneck is large-scale indexing.

### Previous defects already fixed

- Duplicate launcher removed.
- Transient content URI result-display crash removed through durable local copies.
- Repeated search crash fixed.
- Large URI lists no longer travel through intents.
- Paged `BulkImagePickerActivity` added.
- Queue ingestion made streaming/chunked and foreground WorkManager based.
- MediaDocumentsProvider failures isolated per item.
- Shared worker now performs Haar + Classical + Advanced indexing from the same decoded bitmap.
- Batch Room persistence for the three feature stores added.
- Persistent visual-index operation records added.

### Large-batch/durable indexing foundation

Implemented components:

- `BulkImagePickerActivity`
- `ImageCorpusImportWorker`
- `ImageCorpusImportScheduler`
- `UnifiedVisualIndexWorker`
- `OptimizedUnifiedVisualIndexService`
- `VisualIndexBatchDao`
- `VisualIndexOperationEntity/Dao`

Current ingestion strategy:

`paged MediaStore metadata → queue file → streaming import worker → bounded concurrent copy/validation → batched corpus insert`

Current visual-index strategy:

`bounded batch of 16 → up to 4 CPU workers → one bitmap decode per image → Haar + Classical V4 + Advanced → one Room transaction per feature batch → durable checkpoint`

The user explicitly wants background/screen-off/rotation/process-recreation durability. WorkManager provides the execution substrate; the Room operation record provides durable state/telemetry.

### Performance observations and rules

Observed historical indexing rates include roughly 3–5 seconds/image on some on-device runs. Diagnostics included 159 images in 118.5 s, 337 in 381.8 s, and 141 in 86.3 s. These observations drive optimization.

Do NOT improve throughput by reducing algorithm strength. Instead optimize:

- shared decode/normalization;
- batched DB writes;
- bounded CPU parallelism;
- OpenCV/native allocation reuse;
- redundant I/O elimination;
- cache/version checks;
- durable chunking/checkpointing;
- stage-level instrumentation.

### Advanced Visual Intelligence V2 — implemented

The Advanced section is now a separate deterministic classical analytical engine with version:

`ADVANCED-VISUAL-CLASSICAL-V2`

V2 extends V1 with independently stored evidence:

- 16×16 multi-scale grayscale structure;
- global RGB + saturation moments;
- 4×4 spatial RGB + saturation distributions;
- full 256-bin LBP texture histogram;
- 4×4 spatial LBP-transition texture signature;
- 24-bin gradient-orientation histogram;
- independent gradient-magnitude histogram;
- 8×8 spatial edge/layout signature;
- 16×16 illumination-robust local contrast/variance signature;
- grayscale entropy;
- aspect ratio.

The Advanced V2 score uses cross-signal consensus and explicit contradiction penalties. Color/texture alone cannot freely inflate a result when structural evidence disagrees.

### Advanced recall architecture

Advanced search now unions the top classical Reverse Image candidates with the top Advanced V2 candidates before final fusion. This prevents a strong Haar/AKAZE result from disappearing simply because Advanced's global score ranked it lower, and prevents a strong Advanced candidate from disappearing solely because the existing classical engine ranked it lower.

Final fusion retains provenance for both families and records actual component evidence.

### Explainability implementation

Every Advanced result now exposes:

- final score;
- existing classical score;
- actual Haar score;
- pHash/dHash;
- existing color/edge/local/RANSAC evidence;
- Advanced total;
- structure;
- global/spatial color;
- global/spatial texture;
- gradient direction/magnitude;
- layout;
- illumination robustness;
- entropy;
- aspect ratio;
- reason codes including agreement and contradiction conditions.

The UI no longer labels the aggregate score as Haar. The score components correspond to their actual sources.

### Database migration

Advanced V2 added persistent columns for:

- `spatialColor`
- `spatialLbp`
- `gradientMagnitude`
- `illuminationRobustStructure`

Database version is now 12 with migration `11 → 12`. The migration rebuilds only the Advanced feature table because the fingerprint schema changed; existing shared corpus items remain intact. A rebuild of Advanced fingerprints is therefore required before measuring V2 quality.

### CI history

Run #111 succeeded with the durable checkpoint/indexing foundation and produced a debug APK. Subsequent Advanced V2 commits trigger newer Android Build runs; a version is not device-ready until its own CI run succeeds.

### Immediate next steps

**1. CI verification**
- Verify the build after Advanced V2 and all related entity/migration/index-pipeline changes.
- Do not declare V2 device-ready before its own CI run succeeds.

**2. Advanced V2 further analytical layer**
- Add a measured geometric/region-consistency verifier where it materially helps.
- Add robust near-duplicate/duplicate evidence without duplicating the existing 64/16 Reverse Image pipeline.
- Add score calibration/quality bands only after controlled device measurements.

**3. Explainability UX**
- Add expandable evidence details and a clear textual reason for inclusion.
- Show contradiction penalties and confidence bands without inventing causes.

**4. Final benchmark before user installation**
- Exact duplicate.
- JPEG recompression.
- Resize.
- Screenshot/UI frame.
- Small and large crop.
- Brightness/contrast/color changes.
- Near-duplicate burst.
- Unrelated image.
- Rotation 90/180/270.
- Perspective/viewpoint change.
- Image-inside-screenshot.
- Large 5,000–6,000 image corpus performance.

### Permanent constraints

- **999 is valid and intentional.**
- Never reduce 64 shortlist or 16 SIFT.
- Never remove or weaken Haar/pHash/dHash/HSV256/Sobel/AKAZE/RANSAC/SIFT in the existing Reverse Image engine.
- Never create a second launcher/application for Advanced.
- Never require duplicate user import for separate visual engines.
- Never pass thousands of URIs through an Intent.
- Never let one bad media URI abort a large batch.
- Never allow Activity destruction/rotation to cancel durable indexing.
- Never claim accuracy improvement without measured evidence.
- Never add algorithms merely for novelty; each needs purpose, stored representation, comparison metric, cost, and benchmark value.
- Read `PROJECT_CONVERSATION_CONTEXT.md` and this file before changing the architecture.
