# Advanced Visual Intelligence — Device Test Findings

Date: 2026-08-28
Device: Android 12 / SM-G981U (Android API 31)

## Purpose

This document records real on-device findings for the shared visual corpus, Advanced Visual Intelligence, Local Reverse Image Search, image selection, import and indexing. It is a regression reference for subsequent engineering work.

## 1. Exact picker test clarification

The latest installed build did **not** expose Samsung Gallery/Studio when the user pressed `OPEN SYSTEM FILES / GALLERY`. The user therefore did not enter Gallery and did not test a Gallery 500-item ceiling in that build.

The 500+ tests were performed via:

`Files -> Screenshots`

Observed:

- ~500 images: successful;
- >500 images: successful;
- selecting all roughly 6,000–7,000 images from the folder: application exited immediately.

A separate in-app MediaStore browser displayed approximately `215,992` media images and remained stable when `SELECT ALL` was activated; the user cancelled the subsequent long operation. Device file/media inspection showed approximately `107,994` images.

## 2. Selection architecture findings

### 2.1 Gallery was not actually available in the tested build

The previous `OPEN SYSTEM FILES / GALLERY` label represented a document-provider flow based on `OpenMultipleDocuments(image/*)`. It did not guarantee an OEM Gallery Activity.

Required UI contract:

`OPEN SYSTEM FILES / GALLERY` -> source chooser containing:

- Gallery/Studio route;
- Photo Picker route;
- Files multi-document route;
- Folder/tree route for scalable bulk import;
- in-app MediaStore browser.

Gallery presence must be resolved at runtime. Never assume Samsung Gallery or any OEM package is installed.

### 2.2 Why 6,000–7,000 system-file selection can crash

The previous system-file route asks the OS to return thousands of selected document URIs in one ActivityResult/ClipData payload. This is fundamentally different from streaming those URIs after they have already crossed the Activity/Binder boundary.

Therefore:

- streaming the queue writer alone is not enough to guarantee safety for a 7,000-item `OpenMultipleDocuments()` result;
- the scalable standard Android path is a `DocumentTree` selection followed by in-app streaming enumeration of child documents;
- ordinary `OpenMultipleDocuments()` remains useful for moderate selections.

The application must not encourage passing thousands of URI entries through one Intent/result.

### 2.3 In-app media count discrepancy

The old implementation combined:

- `MediaStore.VOLUME_EXTERNAL` aggregate volume;
- individual `getExternalVolumeNames()` collections.

This can enumerate the same media twice.

The observed values were approximately:

`215,992` in-app vs `107,994` device inspection.

The current implementation has been changed to use only the aggregate external collection, avoiding aggregate+per-volume duplication.

Select All is logical state only and full IDs are streamed to the private `.uris` queue.

## 3. Mixed/invalid document import findings

A real run reported:

`total=860, added=571, failed=289, skipped=0`

The failures were not limited to TIFF. The diagnostic sample included normal JPEG/JPEG-family document-provider URIs such as files under `Download`, as well as `.tif`/`.TIF` images.

This demonstrates a provider-access problem in addition to format support.

The importer remains per-item isolated and was hardened to try:

1. `ContentResolver.openInputStream()`;
2. `ContentResolver.openFileDescriptor()` via `ParcelFileDescriptor.AutoCloseInputStream`;
3. direct primary external-storage fallback for `com.android.externalstorage.documents` document URIs.

Future diagnostics should distinguish:

- permission/revoked URI;
- provider open failure;
- copy failure;
- unsupported format;
- corrupt image;
- decode failure;
- missing file.

## 4. Shared indexing findings

The user observed roughly a 4x speed improvement versus the previous implementation. This is a useful on-device observation but is not a controlled benchmark.

Logs show:

- shared decode;
- durable batch commits;
- parallelism 4;
- batch size 16 in the shared visual worker;
- successful up-to-date skipping.

Example:

`items=1083, indexed=0, skipped=1083, failed=0`

means the stored fingerprints were already current for that operation and recomputation was skipped.

## 5. Advanced version identity

Advanced is currently V2:

`ADVANCED-VISUAL-CLASSICAL-V2`

The seven query variants are therefore correctly reported as Advanced V2.

Classical Reverse Image is V4:

`CLASSICAL-PHASH-DHASH-HSV256-SOBEL-AKAZE-V4`

Do not relabel Advanced as V4 without a real versioned fingerprint contract, storage/migration, index, query path and tests.

## 6. Advanced vs Reverse result-quality findings

The user reports little visible difference between the two sections. Current architecture explains why they can converge:

- Advanced retrieves the 64 base candidates through the full-strength Reverse Image service;
- Advanced independently scores its own corpus;
- candidate IDs are unioned;
- final fusion combines the evidence.

This is not proof that the algorithms are identical, but it does show insufficient ranking separation in the tested corpus.

Observed ranking defect:

- true variants of a queried image can rank high;
- one or two weaker/unrelated images can appear above genuine variants;
- a continuous percentage alone does not adequately distinguish true transformed matches from generic color/texture resemblance.

Target ranking pipeline:

`broad retrieval -> score normalization -> correlated-signal control -> independent evidence gates -> geometric authority -> match-type classification -> false-positive suppression -> final ranking`

Never reduce the Reverse 64/16 pipeline to fix this.

## 7. Explainability findings

The current `WHY THIS RESULT` presentation remains an evidence dump rather than a causal ranking explanation.

Required decision record:

- match type;
- final score and acceptance band;
- ranking margin;
- independent evidence count;
- geometric support;
- regional support;
- structural support;
- color support;
- texture support;
- contradictions and negative evidence;
- weighted independent contributions;
- WHY INCLUDED;
- WHY THIS RANK;
- WHY NOT REJECTED.

The record must be reconstructible from actual stored evidence. `confidencePercent` is evidence strength, not a probability.

## 8. Current code changes from the 2026-08-28 clarification

`BulkImagePickerActivity` now has distinct source paths for Gallery, Photo Picker, Files, Folder and in-app MediaStore.

MediaStore enumeration uses only `VOLUME_EXTERNAL`.

Logical Select All streams IDs directly into `.uris` rather than building the entire URI corpus in memory.

The Files multi-document route remains available for moderate selections, while the folder route is the scalable mechanism for 5,000–10,000+ images.

`ImageCorpusImportWorker` now has provider-tolerant descriptor reading and preserves per-item failure isolation.

## 9. Regression requirements

- Gallery must be a genuine runtime-resolved path, not merely a label.
- Files path must remain available.
- Folder/tree import must handle thousands without an enormous ActivityResult payload.
- In-app Select All must remain memory-bounded.
- MediaStore count must not double due to aggregate+per-volume enumeration.
- One bad URI must never abort a large batch.
- Reverse Image must remain full-strength: full corpus -> 64 -> AKAZE/RANSAC all 64 -> SIFT/RANSAC all 16.
- Advanced must remain honestly versioned until a real V4 is implemented.
- Accuracy and performance improvements must be benchmarked rather than inferred from build success.
