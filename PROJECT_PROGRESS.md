# Personal Memory AI — Project Progress Log

## 2026-08-28 — Picker clarification, large-selection diagnosis, provider-read hardening

A second real Android 12 / SM-G981U test clarified the selection failures and corrected an earlier misunderstanding.

### Exact user test distinction

The latest installed build did **not** expose the Samsung Gallery/Studio as a choice when `OPEN SYSTEM FILES / GALLERY` was pressed. The user therefore did not test a Gallery 500-image limit in this build.

The 500+ test was performed through:

`Files -> Screenshots`

Results:

- ~500 selected successfully;
- >500 selected successfully;
- selecting all ~6,000–7,000 images from the same folder caused the application to exit immediately.

Separately, the in-app MediaStore browser showed about `215,992` images and remained stable when `SELECT ALL` was activated; the user cancelled the eventual import to avoid a long wait. Device storage/media inspection showed about `107,994` images. The near-2x difference was consistent with the previous aggregate+per-volume duplication bug.

### Diagnosis of the 6,000–7,000 Files crash

The important distinction is between streaming after the Activity result and transporting the Activity result itself.

`OpenMultipleDocuments()` returns many selected document URIs through the activity result/ClipData. With several thousand selections, the large result payload can overwhelm Binder/Activity result transport or provider-side/result-memory handling before our queue writer gets a chance to stream it. Therefore optimizing only the queue writer cannot make a 7,000-item multi-document result intrinsically scalable.

The durable solution is to provide a folder/tree route for large collections:

`Files -> choose folder -> recursively enumerate child documents -> append URI directly to private .uris queue -> WorkManager`

The ordinary multi-document Files route remains for moderate selections.

### Picker implementation committed

`BulkImagePickerActivity` was updated to provide five source options:

1. Gallery/Studio via real media `ACTION_PICK`, then `ACTION_GET_CONTENT` fallback.
2. Modern `PickMultipleVisualMedia` Photo Picker.
3. Existing system Files multi-document selection.
4. Explicit folder/tree selection for scalable thousands-of-images ingestion.
5. Existing in-app MediaStore browser.

The MediaStore browser now uses only `MediaStore.VOLUME_EXTERNAL`; it no longer combines the aggregate collection with individual external volume names.

Logical Select All is preserved. Full MediaStore URI enumeration is streamed directly into the private `.uris` queue on submission.

Selected URI preparation is also streamed one-by-one rather than creating a second in-memory prepared URI list.

### Provider import hardening

`ImageCorpusImportWorker` was hardened because real diagnostics showed 289 failures among 860 selected items, including ordinary JPEG/JPEG-family document URIs and TIFF files under `com.android.externalstorage.documents`.

The importer now attempts:

- `ContentResolver.openInputStream()`;
- `ContentResolver.openFileDescriptor()` using `ParcelFileDescriptor.AutoCloseInputStream`;
- a direct primary-storage path fallback for primary external-storage document URIs.

One bad item remains isolated from the rest of a batch.

### Current quality/architecture state

- Reverse Image Classical remains V4 and full strength.
- Advanced remains honestly labeled `ADVANCED-VISUAL-CLASSICAL-V2`.
- Advanced and Reverse retain separate UI sections and independent feature stores.
- Both share one durable image corpus and one ingestion/decode pipeline.
- Reverse remains locked at full corpus -> 64 -> AKAZE/RANSAC all 64 -> SIFT/RANSAC all 16.
- No candidate reduction is allowed to gain speed.
- Advanced ranking still needs measured normalization, correlation control, geometric authority, classification, rejection and reconstructible explainability.

## 2026-08-28 — Prior device checkpoint retained

A real Android 12 / SM-G981U installation showed roughly 4x observed indexing improvement versus the older implementation, batching with batch size 16/32 and parallelism 4, and successful skip of already indexed items. This remains an observed device result, not a controlled benchmark claim.

Advanced and Reverse often appeared to return similar results. Weak/unrelated images sometimes ranked above true variants. The current Advanced result explanation remains insufficient as a causal ranking explanation.

## Permanent regression requirements

- Never double MediaStore counts by enumerating aggregate plus per-volume collections together.
- Never materialize the complete corpus URI set merely to represent Select All.
- Never depend on a single OEM Gallery Activity being present.
- Keep Gallery/Photo Picker, Files and folder selection as distinct acquisition paths.
- Do not pass thousands of URI entries through an Activity Intent when a tree/streaming path can be used.
- Never let one unreadable URI abort a large import.
- Never call Advanced V4 until a real V4 engine is implemented and integrated.
- Never reduce Reverse 64/16 recall stages.
- Never claim performance/accuracy gains without controlled measurement.
