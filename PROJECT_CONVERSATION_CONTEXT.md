# Personal Memory AI — Persistent Conversation Context Ledger

## Purpose

This file is a durable project-memory ledger for the development conversation. It records the user's decisions/requirements, implementation decisions, observed failures, and the current execution order so later work can resume without losing architectural context.

> Rule: read this file and `PROJECT_PROGRESS.md` before changing the reverse-image/indexing architecture.

## 2026-08-26 — Current conversation state

### User requirements

- The existing Reverse Image Search section must remain unchanged as a distinct top-level feature and screen.
- The new Advanced Visual Intelligence must be a **separate top-level section** in the existing `IntelligenceHomeActivity`, with its own Activity/screen.
- Advanced Visual Intelligence must use the **same visual language** as the main intelligence system: same colors, panels, buttons, spacing, and overall styling.
- Advanced Visual Intelligence must not become a hidden mode inside Reverse Image Search and must not create another launcher/application.
- Both sections use the **same shared image corpus/ingestion layer** so the user does not have to fetch/import the same images twice.
- Each section retains its **own feature/index storage** and can maintain its own engine versions/rebuild state.
- Existing Reverse Image algorithms remain intact: DigiKam-style Haar, Classical V4, pHash, dHash, HSV256, spatial edge/shape, persisted AKAZE, mutual matching, RANSAC, SIFT, and rotation/crop variants.
- Existing Reverse Image search strength is protected: do not reduce the full 64 shortlist or 16 SIFT stage to gain speed.
- MobileCLIP and the previous neural/semantic stack are explicitly out of scope for this phase.
- Advanced Visual Intelligence will later contain additional classical/analytical engines chosen for measurable value, not random algorithm accumulation.
- Advanced results must explain **why** an image was returned and **how** its percentage was formed. The explanation must be based on real stored evidence, not post-hoc text.
- The user wants large-scale selection/import, including roughly 5,000–6,000 images in a single selection workflow.
- The user wants import/fingerprinting to continue when the Activity is recreated, the phone is rotated, the app is backgrounded, and the screen is turned off, subject to Android execution rules.
- The user does not accept speedups that sacrifice recall or algorithmic strength.

### User device observations

- Android 12 / SM-G981U.
- The user tested the existing classical reverse-image stack and found it useful/good for visual similarity.
- The user reported weak/unrelated images in results; this is a discrimination/ranking problem, not a reason to reduce recall.
- The user confirmed the test corpus of 999 images was intentional and correct.
- The user later observed runtime corpora such as 1120 items.
- Image selection itself is reasonably fast; the main bottleneck is `BUILD HAAR INDEX` / fingerprinting.
- Observed indexing can take about 3–5 seconds per image on some runs.
- Observed search latency improved materially after the staged local verification/performance work.
- The user specifically rejected reducing 64→40 and 16→8 as a performance solution.

### Reverse Image debugging history that must not be repeated

1. A previous integration accidentally exposed Reverse Image as a second launcher/application. This was fixed by making `IntelligenceHomeActivity` the sole `MAIN/LAUNCHER` and Reverse Image an internal activity.
2. Results initially crashed because the adapter directly used transient `content://...` URIs. The solution was durable app-private copies and display from local files.
3. A second-search crash happened because stale/transient URI handling broke RecyclerView binding. Results are now cleared before a new search and local durable paths are preferred.
4. The original large-selection flow passed large URI lists directly through Activity state/Intent. This was replaced by a paged in-app picker that writes a queue file.
5. The first queue worker still loaded the full URI queue into memory. It has since been changed to streaming queue-line processing and uses `BitmapFactory.Options.inJustDecodeBounds` for inexpensive dimension validation during ingestion.
6. Earlier CI failures included Kotlin `List`/`Array` mismatch in AKAZE, suspend DAO invocation outside coroutine context, expression-body return errors, missing queue result constant, and incorrect `MediaStore` query constant usage. These have been corrected in later builds.

### Current Reverse Image strength invariant

Current search strategy remains:

`full corpus global retrieval -> 64 shortlist -> AKAZE/mutual/RANSAC over all 64 -> 16 SIFT/RANSAC -> final ranking`

No future performance change may reduce this coverage unless the user explicitly changes the requirement.

## Advanced Visual Intelligence architecture

### Current top-level structure

`IntelligenceHomeActivity`

- `LOCAL REVERSE IMAGE SEARCH` → `ReverseImageSearchActivity`
- `ADVANCED VISUAL INTELLIGENCE` → `AdvancedVisualIntelligenceActivity`

`AndroidManifest.xml` must contain only one launcher entry: `IntelligenceHomeActivity`.

### Shared corpus principle

`source image -> one durable local copy -> one decode/normalization pass`

The outputs then branch into independent stores:

- Reverse Image index: Haar + Classical V4 + local descriptors.
- Advanced index: `advanced_visual_fingerprints` with engine version `ADVANCED-VISUAL-CLASSICAL-V1`.

The branches are architecturally independent even though they consume the same image pass.

### Advanced V1 signals currently implemented

- 16×16 multi-scale grayscale structure map.
- RGB/color moments + saturation statistics.
- 256-bin LBP texture histogram.
- 24-bin gradient orientation histogram weighted by gradient magnitude.
- 8×8 spatial layout/edge signature.
- grayscale entropy.
- aspect ratio.

Advanced V1 exposes separate structure/color/texture/gradient/layout/entropy/aspect evidence and reason codes.

### Advanced explainability requirement

Each result must be able to display:

- final percentage;
- true Haar score;
- pHash/dHash scores;
- color/edge/local evidence;
- AKAZE/RANSAC evidence;
- SIFT/RANSAC evidence;
- Advanced V1 component scores;
- best query variant;
- geometric consistency;
- negative/contradictory evidence penalties;
- explicit reason codes.

The overall percentage must be mathematically reconstructible from stored components.

## Scale/durability implementation in progress

### Current implemented pieces

- `BulkImagePickerActivity` supports paged local gallery browsing/selection.
- Queue file transport avoids placing thousands of URIs inside an Intent.
- `ImageCorpusImportWorker` was added for durable foreground/background import.
- `ImageCorpusImportScheduler` schedules the importer as unique WorkManager work.
- Reverse Image and Advanced activities now schedule import work instead of directly materializing and decoding the full URI list in the Activity.
- Import worker now streams queue lines and does not load the full queue into memory.
- Import worker validates image decodability with `inJustDecodeBounds` and creates one durable app-private copy.
- Shared visual indexing uses WorkManager/foreground execution and is observed from both screens.

### Remaining work before scale architecture is considered complete

1. Add explicit persisted import-operation history/checkpoints so a process death can resume a partially consumed queue without restarting from line 1.
2. Replace the current per-item `findByUri`/insert persistence path with batched/transactional corpus writes where appropriate.
3. Replace the current per-image Room fingerprint writes with real batched feature persistence. Adding unused `insertAll` APIs does not count; the worker must actually use them.
4. Ensure indexing progress survives Activity recreation and process restart with durable operation state.
5. Add speed instrumentation for copy, bounds decode, feature generation, and database persistence separately.
6. Tune worker concurrency based on device memory/CPU while preserving exact fingerprint output.
7. Verify that background/screen-off behavior survives realistic Android lifecycle conditions on the target device.

## 2026-08-26 — Immediate development order

**A. Finish durable ingestion and scale:**
- streaming queue ✅
- large selection architecture ✅
- durable import worker ✅
- persisted import checkpoint/history ⏳
- batch corpus DB persistence ⏳

**B. Finish indexing performance:**
- shared decode already implemented ✅
- bounded parallel work already implemented ✅
- batched fingerprint DB persistence ⏳
- stage-level timing ⏳
- optimized cache/version checks ⏳

**C. Advanced Visual Intelligence expansion:**
- standalone screen ✅
- independent index ✅
- first classical V1 signals ✅
- evidence model ✅
- add additional measured classical engines only after scale foundation is stable ⏳

**D. Benchmark:**
- exact duplicate
- recompression
- resize
- screenshot
- mild/large crop
- brightness/color change
- near-duplicate burst
- unrelated image
- rotation
- perspective/viewpoint change
- source-inside-screenshot

## Permanent constraints

- Never silently weaken algorithms to improve speed.
- Never create a second launcher for a feature screen.
- Never require duplicate user import/fetching for separate visual engines.
- Never call a percentage an evidence score unless its source is actually that component.
- Never claim accuracy improvement without measured on-device benchmark evidence.
- Read this file and `PROJECT_PROGRESS.md` before subsequent reverse-image/indexing work.
