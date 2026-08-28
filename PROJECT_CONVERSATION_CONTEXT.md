# Personal Memory AI — Persistent Conversation Context Ledger

## Purpose

Durable memory for the reverse-image/indexing/Advanced Visual Intelligence development conversation. Read this file and `PROJECT_PROGRESS.md` before modifying Reverse Image, shared corpus/indexing, or Advanced Visual Intelligence.

## Latest authoritative device-test checkpoint — 2026-08-28

The user installed the current build and performed a real Android 12 / SM-G981U test. This device evidence overrides assumptions based only on CI.

### A. Picker / corpus selection — exact user clarification

The user explicitly clarified that, in the latest installed build, pressing `OPEN SYSTEM FILES / GALLERY` did **not** take them to a Gallery/Studio screen. They never entered Gallery. The chooser exposed the system Files/document route. Therefore the earlier statement that the user had tested the Gallery's 500-item limit in this build was incorrect and must not be repeated.

The 500-item test happened through `Files -> Screenshots`:

- about 500 images selected successfully;
- more than 500 selected successfully;
- selecting all roughly 6,000–7,000 images from the same folder caused the app to exit immediately.

Separately, the in-app MediaStore browser displayed approximately `215,992` images and could enter `Select All` without immediately exiting; the user cancelled the eventual operation to avoid waiting. Device file/media inspection reported approximately `107,994` images. This strongly indicated duplicate enumeration in the old aggregate/per-volume implementation.

Required UX is now explicit:

- `OPEN SYSTEM FILES / GALLERY` must present a real source chooser containing a genuine Gallery/Studio route, a modern Photo Picker route, the existing Files document route, a scalable Folder route, and the in-app MediaStore browser.
- The existing Files/document route must remain available.
- The scalable route for thousands must not return thousands of URI values through one Activity result/Intent.

### B. Root cause model for the 6,000–7,000 Files crash

The crash is not explained by the in-app logical Select All alone. The existing `OpenMultipleDocuments()` route returns a large `List<Uri>`/ClipData through ActivityResult. Thousands of URI entries can overwhelm Activity/Binder result transport or related provider/result memory before the app can stream them to its queue. Therefore merely optimizing `writePreparedUriQueue()` cannot make 7,000 individual documents safe if the provider result itself is enormous.

The safe scalable design is:

`Files -> choose folder/tree -> stream child document URIs directly to private .uris queue -> WorkManager import`

Keep ordinary multi-document selection as a convenience path for moderate selections, but do not advertise it as the large-corpus mechanism.

### C. In-app Select All and count correctness

The in-app picker must use logical `allSelected` state and must never materialize the full media corpus into a Kotlin URI list.

The previous `prepareVolumes()` implementation combined `MediaStore.VOLUME_EXTERNAL` with individual external volume names. That produced near-2x counts. The implementation is now changed to use the aggregate external collection only.

Regression invariant:

- `215,992`-style duplicate counts must not recur because aggregate + per-volume enumeration are mixed.
- Select All must write the queue by streaming MediaStore IDs.

### D. Mixed-provider/import diagnostics

The user observed partial improvement: mixed folders no longer abort the entire import, and invalid items fail individually.

A later run reported:

- `total=860`
- `added=571`
- `failed=289`
- `skipped=0`

The failures included ordinary JPEG/JPEG-family document-provider URIs and TIFF files from `com.android.externalstorage.documents`. This means the importer needs provider-tolerant read behavior, not only image-format handling.

`ImageCorpusImportWorker` now attempts, in order:

1. `ContentResolver.openInputStream()`;
2. `ContentResolver.openFileDescriptor()` through `ParcelFileDescriptor.AutoCloseInputStream`;
3. direct primary external-storage path fallback for `com.android.externalstorage.documents` document URIs.

Per-item failure isolation remains mandatory.

### E. Shared indexing observations

The user observed roughly a 4x improvement versus the old indexing implementation. This is an on-device observation, not a controlled benchmark claim.

Logs show durable batching, shared decode and parallel processing. Example healthy skip case: `items=1083`, `indexed=0`, `skipped=1083`, `failed=0`.

### F. Advanced engine identity

Advanced is currently **V2**, not V4.

`AdvancedVisualFingerprintEngine.ENGINE_VERSION = "ADVANCED-VISUAL-CLASSICAL-V2"`.

Seven query variants are therefore correctly reported as `Advanced V2`.

The `CLASSICAL-...-V4` label belongs to the existing Classical Reverse Image engine:

`CLASSICAL-PHASH-DHASH-HSV256-SOBEL-AKAZE-V4`.

Do not claim Advanced V4 until a real versioned V4 implementation exists with new fingerprint fields, persistence, migration, indexing, search and tests.

### G. Advanced vs Reverse Image quality

The user observed little visible difference between Advanced and Local Reverse Image. This does not prove identical implementations. The Advanced service currently retrieves 64 candidates from the full-strength Reverse Image engine, computes Advanced scores over its own corpus, unions IDs and fuses them. Classical evidence can therefore dominate the visible result set.

Current ranking architecture target remains:

`broad retrieval -> score normalization -> correlated-signal control -> independent evidence gates -> geometric authority -> match-type classification -> false-positive suppression -> final ranking`

Never reduce the existing Reverse Image pipeline to improve speed.

### H. Explainability requirement

The existing `WHY THIS RESULT` panel is still considered insufficient because it is an evidence dump rather than a reconstructible ranking explanation.

Required decision record:

- match type;
- final score and acceptance band;
- ranking margin;
- independent evidence count;
- geometric support;
- regional support;
- structural support;
- color/texture support;
- contradictions/negative evidence;
- exact weighted independent contributions;
- `WHY INCLUDED`;
- `WHY THIS RANK`;
- `WHY NOT REJECTED`.

`confidencePercent` is an evidence-strength heuristic, not a probability.

### I. Current implementation decisions from this turn

1. `BulkImagePickerActivity` now exposes five source paths: Gallery, Photo Picker, Files, Folder/streaming, and in-app MediaStore browser.
2. The native Gallery path tries `ACTION_PICK` first and `ACTION_GET_CONTENT` second, with Photo Picker fallback rather than assuming Samsung Gallery exists.
3. The in-app MediaStore browser uses only `MediaStore.VOLUME_EXTERNAL` to avoid aggregate/per-volume duplication.
4. Logical Select All remains in memory as state only; full corpus URIs are streamed to `.uris` on submission.
5. Selected URI preparation is streamed one item at a time rather than building a second prepared-URI list.
6. `ImageCorpusImportWorker` now includes a file-descriptor fallback for document providers, preserving per-item isolation.
7. The scalable folder route remains the recommended path for thousands of images because it avoids a giant ActivityResult/ClipData payload.

### J. Permanent architecture / accuracy invariants

- `IntelligenceHomeActivity` remains the single launcher.
- `LOCAL REVERSE IMAGE SEARCH` remains its own top-level screen.
- `ADVANCED VISUAL INTELLIGENCE` remains its own top-level screen in the same design language.
- Both consume one shared durable local corpus/import/decode path.
- Reverse retains its own Haar/Classical/AKAZE/SIFT stores.
- Advanced retains an independent Advanced V2 store until a real V4 exists.
- Reverse recall pipeline is locked at full corpus -> 64 -> AKAZE/RANSAC all 64 -> SIFT/RANSAC all 16 -> final ranking.
- Do not duplicate corpus import/decode.
- Do not pass thousands of URIs through an Intent.
- Do not reduce candidate coverage to gain speed.
- Do not claim confidence is probability.
- Do not claim performance or accuracy gains without measurement.
- Every new algorithm must have a defined purpose, representation, metric, cost, failure modes and benchmark value.

## Documentation files

- `PROJECT_PLAN.md` — architecture and roadmap.
- `PROJECT_PROGRESS.md` — chronological engineering progress.
- `docs/ADVANCED_DEVICE_TEST_FINDINGS_2026-08-28.md` — authoritative device findings and regression requirements.
