# Personal Memory AI — Project Progress Log

## 2026-08-26 — Scale, durability, indexing performance, and Advanced Visual Intelligence roadmap

### Current device verification

The user installed and tested the current full-strength reverse-image build on Android 12 (`SM-G981U`). Search is now materially faster than the earlier >10-minute behavior, and the user reports generally good visual-similarity retrieval. The user also reports some weak/unrelated results that now need stronger discrimination and explainable evidence.

The original **999-image corpus is intentional and correct**, not an indexing error. Recent diagnostics also show a corpus of **1120 items**.

### Hard invariants — MUST NOT be weakened

- Keep the existing complete reverse-image stack: `DIGIKAM-HAAR-128-40-YIQ-V2 + CLASSICAL-PHASH-DHASH-HSV256-SOBEL-AKAZE-V4 + AKAZE mutual/RANSAC + SIFT mutual/RANSAC + rotation/crop variants`.
- Keep **64 global shortlist candidates**.
- Keep AKAZE/RANSAC verification over the full 64 shortlist.
- Keep **16 SIFT/RANSAC candidates**.
- Never reduce shortlist size, verifier count, descriptor strength, or accuracy-bearing stages to obtain speed.
- Performance must come from shared decoding/normalization, I/O reduction, caching, batching, controlled parallelism, native allocation reduction, and better scheduling.
- The current classical system is the baseline to improve, **not replace**.
- MobileCLIP/neural semantic search remains separate from this classical phase and must not replace or weaken it.

### Current indexing bottleneck

The user measured `BUILD HAAR INDEX` / multi-fingerprint indexing as the main bottleneck, with roughly **3–5 seconds per image** in observed runs. Diagnostics support this: examples include 159 images in 118,488 ms, 337 in 381,849 ms, and 141 in 86,305 ms.

Review of `ReverseImageSearchService` shows that indexing currently does, per image, private-copy resolution, bitmap decode, Haar fingerprint, complete Classical V4 fingerprint including persisted AKAZE descriptors, separate Room reads for existing Haar/classical rows, separate Room writes, then bitmap recycling. The loop is largely sequential. The next optimization must preserve identical fingerprint/descriptor outputs while removing redundant work and improving scheduling.

### Scale/ingestion defects reported by the user

1. Reverse-image corpus selection currently caps the picker at **1000 images**. This is too low. The system must support thousands, including **5,000–6,000+** selections.
2. Selecting very large batches can cause the application to exit/crash. Selection and ingestion must become chunked/streaming and memory-bounded rather than retaining a huge workload in memory.
3. A MediaDocumentsProvider URI such as `content://com.android.providers.media.documents/document/image%3A1000679119` produced `تعذر فك ترميز الصورة`. URI handling must be ContentResolver/stream robust and must not assume that a content URI is a filesystem path. Each item failure must be isolated so one bad URI does not abort the whole batch.
4. Durable app-private copies must remain the canonical analysis/search source.

### Rotation/background/screen-off defects

Observed: rotating during indexing causes the operation to disappear/stop. Leaving the application briefly may allow progress, but remaining away longer causes the indexing operation to disappear.

Required architecture:

- Indexing must not be owned by an Activity `lifecycleScope` job.
- Use durable Android background work with persisted operation state and resumability.
- Persist operation ID, corpus snapshot, next item/chunk, counts, engine versions, timestamps, and terminal state.
- Activity recreation/rotation must reconnect to the existing operation and continue displaying it.
- Process recreation must resume from committed progress.
- Screen-off/backgrounding must not cancel indexing; cancellation must be explicit.
- Progress must survive Activity destruction and be restored when the UI returns.

### Zero-recall-loss indexing performance strategy

Target: **much faster indexing without any reduction in algorithmic strength**.

Implement and measure:

1. One image decode per indexing pass, shared by all reverse-image engines.
2. Shared normalized/luminance/color/scale representations where mathematically compatible.
3. Bounded multi-image CPU workers with backpressure so thousands of images do not exhaust RAM.
4. Batched Room transactions rather than one persistence round-trip per feature.
5. Reduced/reused native OpenCV allocations with deterministic cleanup.
6. Version/cache checks before expensive decode/feature work.
7. One durable private copy per source image; never copy/decode twice for two engine families.
8. Chunked, resumable work units for large corpora.
9. Stage-level diagnostics separating copy, decode, Haar, classical-global, AKAZE extraction, DB write, and total item latency.

Invariant: **same fingerprints, same descriptor counts, same candidate coverage, same verification stages; only redundant work and scheduling overhead are reduced.**

### NEW: Advanced Visual Intelligence section

The user explicitly requested a **separate new engine section**, not a replacement for the current reverse-image system.

Architecture:

- Existing Haar/classical engines retain their own fingerprints and index tables.
- A new `Advanced Visual Intelligence` engine family is added beside them.
- Both families consume **one shared ingestion/decode/normalization pass**.
- Images are never fetched/copied/decoded twice merely because both engine families need them.
- Each family keeps its own versioned persistent features and can be rebuilt independently.
- Search may fuse the complete current classical evidence with advanced evidence while preserving provenance for each signal.

Candidate advanced signals to design, implement, and benchmark rather than add blindly:

- multi-scale perceptual structure;
- richer spatial color distributions;
- illumination/contrast-normalized signatures;
- rotation/scale/crop-aware structural evidence;
- contour/shape and gradient-orientation evidence;
- robust local-feature consensus and geometric consistency;
- duplicate/near-duplicate fingerprints;
- texture/region statistics;
- layout/composition evidence;
- later, an explicitly versioned learned visual representation if/when permitted by the project phase.

Every new signal must have a defined fingerprint, storage schema, comparison metric, cost, failure modes, and benchmark purpose.

### NEW: Explainable similarity/evidence system

The Advanced Visual Intelligence section must explain **why every returned image was selected and why it received its percentage**.

Future result evidence must expose, where applicable:

- final similarity percentage;
- Haar score/contribution;
- pHash and dHash distances/scores;
- HSV/color score;
- spatial edge/shape score;
- AKAZE matches and RANSAC inliers;
- SIFT matches and RANSAC inliers;
- advanced-engine signal scores;
- best query variant (original/rotation/crop);
- geometric consistency/confidence;
- contradiction/weak-evidence penalties;
- explicit reason codes such as `strong_global_agreement`, `geometric_match`, `near_duplicate`, `color_only_match`, `weak_structure`, `insufficient_evidence`.

The displayed percentage must be reproducible from stored components. The UI must be able to answer: **“Why was this image returned?”** and **“Why approximately 40% instead of 80%?”** without inventing an explanation after the search.

### Current search diagnostics

Recent successful measurements include:

- 1120 items with `indexed=0, skipped=1120` on an unchanged incremental pass.
- 1095 indexed candidates with approximately 15.3 s search, `shortlist=64`, `siftVerified=16`.
- 308 indexed candidates with approximately 14–18 s search, `shortlist=64`, `siftVerified=16`.

These are runtime observations, not accuracy claims.

### Execution order from this point

**Phase A — Scale and durability first**
1. Remove the 1000-image picker cap via scalable/chunked ingestion.
2. Fix MediaDocumentsProvider/content-URI decoding robustly.
3. Make 5,000–6,000+ selection memory-bounded.
4. Move reverse-image indexing to durable background work with persisted resume state.
5. Make rotation, process recreation, backgrounding, and screen-off safe.
6. Restore live progress after Activity recreation.

**Phase B — Accelerate indexing without recall loss**
1. Profile every indexing stage.
2. Implement shared decode/normalization.
3. Add bounded multi-image CPU workers.
4. Batch DB writes.
5. Reduce native allocation/I/O overhead.
6. Verify optimized outputs against the current engine outputs for equivalence.

**Phase C — Advanced Visual Intelligence**
1. Define new engine interfaces and persistent schema.
2. Add measured structural/color/texture/geometric signals.
3. Share ingestion/decode with the existing Haar/classical stack.
4. Add independent engine versioning/rebuild controls.
5. Add evidence/provenance and score decomposition.
6. Fuse advanced evidence with the existing full-strength 64/16 pipeline without reducing coverage.

**Phase D — Controlled benchmark**
Test exact match, recompression, resize, screenshot/UI borders, mild/large crop, brightness/color changes, burst/near-duplicate, unrelated images, viewpoint change, perspective change, rotations, and source-inside-screenshot cases. Record Top-10 rank and every signal component. Do not claim an accuracy percentage without measured on-device data.

### Readiness rule

A successful CI build proves compilation/package validity only. The system is not considered production-ready until device testing validates large-batch selection, URI decoding, rotation safety, background/screen-off persistence, indexing throughput, exact/near/weak-match behavior, and explainable result evidence.

## 2026-08-26 — Full-strength reverse-image search performance pass

### User requirement

The search must become much faster **without reducing retrieval coverage, shortlist strength, or any verification algorithm**. Reducing the 64-candidate shortlist or the 16-candidate SIFT stage is explicitly rejected.

### Implemented

- Restored the full `GLOBAL_SHORTLIST_MAX = 64`.
- Restored the full `SIFT_RERANK_LIMIT = 16`.
- Global retrieval still evaluates the complete indexed corpus.
- AKAZE + mutual matching + RANSAC still runs on all 64 shortlist candidates.
- SIFT + mutual matching + RANSAC still runs on all 16 final candidates.
- Added bounded parallel execution with a maximum of four concurrent CPU tasks.
- Search progress reports full stage coverage.
- Added end-to-end timing/parallelism diagnostics.
- Query variants are computed once and reused.

### Design principle

Never solve performance by reducing candidate population or removing a verifier. Optimize repeated computation, native/OpenCV object creation, disk I/O, and CPU scheduling first.

## 2026-08-25 — Reverse-image classical stack v4: SIFT + rotation + HSV256

- Added `SiftLocalVerifier.kt` using OpenCV SIFT, BFMatcher L2, mutual matching, ratio filtering, and homography RANSAC.
- Global query retrieval includes 0°, 90°, 180°, 270° and centered-crop variants.
- `ClassicalVisualFingerprintEngine.kt` V4 uses full 16×4×4 HSV = 256 bins including Value.
- V4 invalidates stale classical fingerprints and requires rebuild.

## 2026-08-25 — Reverse-image classical stack v3 and integration history

- DigiKam-style Haar/Wavelet fingerprint is the primary anchor and was validated by the user on a 999-image corpus.
- Classical corrections fixed dHash coverage, pHash DC exclusion, histogram normalization, AKAZE mutual matching, and RANSAC gating.
- Search uses global retrieval followed by shortlist local verification.
- Reverse-image remains inside the existing application shell, not a second Android application.
- Durable app-private copies address transient DocumentsProvider permissions.
- Index UI includes processed/total, percentage, rate, ETA, indexed/skipped/failed/local-feature counts.
- Earlier CI run #28 failed from a suspend DAO call and was fixed; later builds succeeded.
- The user's 999-image corpus is intentional and is not an indexing defect.
