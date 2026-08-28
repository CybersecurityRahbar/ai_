# Conversation context checkpoint — 2026-08-28

## User request
The user corrected the picker interpretation and asked for immediate engineering fixes. Important clarification: `OPEN SYSTEM FILES / GALLERY` previously never showed Gallery at all. The 500-image experiment was performed through `Files -> Screenshots`; ~500 and >500 worked, but selecting all ~7,000 caused the app to exit. Separately, the in-app MediaStore browser could represent `SELECTED 215992` without immediate crash, while the phone reported about 107,994 images. The user requires the old Files path to remain, wants a real Gallery/Studio option added, wants a deterministic path for thousands of images, and requires durable context updates every development turn.

## Runtime evidence
Latest logs: import total 860, added 571, failed 289. Ordinary JPEG provider URIs from `com.android.externalstorage.documents` repeatedly failed with generic copy errors. TIFF files failed at BitmapFactory decode. Shared index: 1083 items, 523 indexed, 560 skipped, 0 failed; extraction dominates persistence. Reverse search normally takes ~16–20s, with an observed ~71.6s outlier. Accuracy/recall must not be reduced for speed.

## Engineering findings
- The integrated MediaStore count was double-counted because previous code combined the aggregate `VOLUME_EXTERNAL` view with individual external volumes.
- Native `OpenMultipleDocuments` can return a large `ClipData`; an OS/Binder transport failure may occur before the application receives the callback, so app-side exception handling cannot guarantee arbitrary thousands-item native multi-select.
- A deterministic `OpenDocumentTree` folder traversal can enumerate children and stream URIs to disk without a huge Activity-result transaction.
- Non-persistable provider URI grants must be staged while the UI activity still owns the transient read permission.
- Provider-copy failure and unsupported/corrupt image decoding are distinct failure classes and must be logged separately.

## Implemented code changes pushed to main
- `BulkImagePickerActivity.kt`: explicit source chooser with native Gallery/Studio, existing System Files, Folder/Tree import for thousands, and existing in-app MediaStore browser; one-pass-per-volume counting; page size 200; modified/added/name sorting; real thumbnails; logical Select All (`allSelected + excludedFromAll`); disk-backed queue generation.
- `activity_bulk_image_picker.xml`: source hint and sort control.
- `item_bulk_image_picker.xml`: thumbnail ImageView.
- `ImageCorpusImportWorker.kt`: provider read retries; `file://` staging support; primary external-storage document fallback; distinct COPY_FAILED / UNSUPPORTED_OR_CORRUPT_* / IMPORT_FAILED diagnostics; batch continuity.
- `OptimizedUnifiedVisualIndexService.kt`: staged/private file URI support and primary-storage fallback.
- `AdvancedVisualIntelligenceService.kt`: progress labels now derive from `AdvancedVisualFingerprintEngine.ENGINE_VERSION` instead of hard-coded wording.

## Commit sequence
- 1be8d0ab37aad4cb37c9a4158598689559888604
- 553dd85babfe277a3bfc039fbd3f7f9bc727f4a4
- 0666e601ef30bebaedab19a90494056d74e09e88
- 02ba5e405e9112ff4106a1b8ab1588050d66829a
- 44c7866a45be792d9795d72b90ccd87c7fb50dcf
- 09c9becf6575e7faa519eee3924600747299dab1

## Required validation
1. Open Advanced Visual -> Add Images -> `OPEN SYSTEM FILES / GALLERY`; verify Gallery, Files, Folder, and in-app browser are all present.
2. Files -> Screenshots: verify 500 and >500; use Folder/Tree for a ~7,000 image test and confirm no app exit.
3. Gallery: verify multi-select returns a queue and does not crash.
4. In-app browser: verify unique count is no longer approximately doubled, thumbnails render, sorting changes order, and Select All remains memory-safe.
5. Import: verify ordinary JPEG provider sources copy; non-persistable sources stage; TIFF is classified instead of killing the batch.
6. Shared indexing remains Haar + Classical V4 + Advanced in one decode/extraction pass with full-strength retrieval; no shortlist/SIFT recall reduction.

## User constraint on context persistence
The user explicitly wants every question/request and every assistant development response represented in GitHub context so future work cannot lose architectural decisions, regressions, test observations, or corrections. This checkpoint exists because the primary context file update path was not safely writable atomically during this turn; the checkpoint preserves the same information without deleting the prior durable context.