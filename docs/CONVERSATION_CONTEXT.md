# Secure/Personal Memory AI — Durable Conversation Context

## 2026-08-28 runtime regression report (user validation)

The user explicitly requires this file to be updated during every development turn with the current request/response context so the project retains durable context across chats.

### Current tested behavior
- The project builds successfully on GitHub Actions.
- Device test is Android 12 / API 31 / Samsung SM-G981U.
- Advanced Visual Intelligence and Local Reverse Image Search are separate, independent UI sections on the main intelligence screen. Advanced Visual must remain a separate section and must reuse the same visual language/theme, not replace or merge over Local Reverse Image Search.
- Shared indexing architecture is intended to extract Haar + Classical V4 + Advanced Visual fingerprints in one pass and persist each subsystem's own index, avoiding duplicate decode/index work.
- Current shared indexing telemetry shows ~4x improvement in indexing speed compared with the earlier implementation, with batch persistence and parallel extraction.

### Critical picker findings
The user corrected an important previous misunderstanding:
1. When pressing `OPEN SYSTEM FILES / GALLERY`, the user did NOT enter Gallery. The UI did not offer Gallery/Studio as a source at all. The current UI must therefore add a real source chooser with an explicit Gallery/Photo Picker option while retaining the existing System Files option. The existing file-picker path must not be removed.
2. The earlier 500-image test was performed through `Files -> Screenshots`, not Gallery. Selecting ~500 and then more than 500 images worked. Selecting all ~7,000 images from that folder made the app immediately exit/crash. This remains a real large-selection regression to address with a safe Folder path because huge SAF ClipData results can exceed Android Binder transaction capacity.
3. The in-app integrated media picker previously showed `VISIBLE 100 SELECTED 215992 ALL MEDIA SELECTED`. Selecting all ~215,992 did NOT immediately crash; the user cancelled the long-running operation manually. This proves the integrated MediaStore selector can hold a huge selection when the selected URI list itself is not materialized in memory.
4. The in-app MediaStore count was wrong: it reported ~215,992 while the phone's file view reported ~107,994 images. Current code included `MediaStore.VOLUME_EXTERNAL` and then also iterated external volume names, which double-counted media because the aggregate view overlaps concrete external volumes.
5. The custom integrated picker was poor UX: only 100 visible at once, no true image thumbnails, no useful sort/filter by modification date, and weak practical selection/navigation.

### Import failure findings
The runtime logs showed two recurring import failures in `ImageCorpusImportWorker`:
- `تعذر نسخ الصورة محليًا` for ordinary JPEG/JPEG files from `content://com.android.externalstorage.documents/...`.
- `تعذر فك ترميز الصورة` for TIFF files, e.g. `.tif` / `.TIF`.
The previous failure path swallowed copy exceptions and later emitted only a generic decode/copy error. The importer must distinguish provider/source access failures from unsupported decoders and continue the queue after individual failures.

### Indexing/background findings
- Shared indexing now uses WorkManager `CoroutineWorker`, foreground notification, batch persistence, four-way parallel extraction, and durable operation checkpoints.
- Current logs show a completed 1083-item shared index and separate batches. Example: 523 indexed, 560 skipped, 0 failed, extraction ~612s accumulated, persistence <1s. This indicates persistent DB writes are no longer the primary bottleneck; fingerprint extraction is.
- Search can still take ~16–20 seconds over ~1083 candidates and one recent run reached ~71.6s. Do not reduce shortlist size or SIFT verification counts as a speed shortcut. The user explicitly rejects recall/accuracy reductions. Future optimization must preserve full candidate recall and full algorithm strength.

### Advanced Visual findings
- The current source uses `AdvancedVisualFingerprintEngine.ENGINE_VERSION = ADVANCED-VISUAL-CLASSICAL-V2`; `AdvancedVisualIntelligenceService` still displays `Advanced V2` in progress text. Do not claim V4 unless the actual engine, persistence schema, and runtime path are upgraded to V4 end-to-end.
- User reports Advanced Visual and Local Reverse Image Search often produce essentially the same result ordering, with some weakly related images appearing too high. Advanced reranking needs calibrated independent evidence and rejection/low-confidence bands, not arbitrary scores.
- Explainability must show why a result ranked where it did using real evidence: global structure, local geometry, regional consistency, texture, gradient, illumination, consensus, etc.
- Advanced Visual result cards should show image thumbnails directly, matching Local Reverse Image Search; tapping opens full image + detailed evidence.

### User's non-negotiable architecture requirements
- Keep Local Reverse Image Search as its own section and retain Haar -> Classical V4 -> AKAZE -> RANSAC and full-strength retrieval.
- Keep Advanced Visual Intelligence as a completely separate main-screen section/activity with the same overall theme.
- Both sections should share one indexing/decode pass where possible, while each keeps its own persisted fingerprint/index tables.
- Do NOT reduce recall by lowering shortlist/SIFT candidate counts. Optimize with caching, shared decode, parallelism, batched persistence, indexing structures, mathematically safe pruning, and efficient storage/querying.
- Long imports/indexing must survive rotation, backgrounding, long periods outside the app, and screen-off through durable WorkManager/foreground execution and resumable checkpoints.
- User wants original device Files and Gallery selection plus the in-app picker as additional choices.

## Current engineering checkpoint — this turn
- Created draft PR #2: `fix(picker): add Gallery source, safe folder ingestion, and harden large imports`.
- Added explicit source chooser with three real paths: `OPEN GALLERY / STUDIO`, `OPEN SYSTEM FILES`, and `SELECT FOLDER / LARGE CORPUS`.
- Gallery path uses explicit `ACTION_PICK` multi-select when a handler exists, with `GetMultipleContents` fallback. Existing Files path remains available.
- Folder path uses `ACTION_OPEN_DOCUMENT_TREE` and enumerates image children into a disk-backed `.uris` queue, avoiding thousands of URIs in an Activity result/Binder transaction. It supports nested directories.
- Integrated MediaStore browser now enumerates concrete external volumes only (avoids aggregate-volume double counting), sorts pages by `DATE_MODIFIED`, and `SELECT ALL` stores only a count until the queue is written to disk.
- Integrated browser now renders actual thumbnails with `ContentResolver.loadThumbnail` on supported Android versions.
- `ImageCorpusImportWorker` now retries source reads up to three times and records precise `SOURCE_ACCESS`, `UNSUPPORTED_FORMAT`, or generic item-import diagnostics instead of swallowing provider exceptions.
- Individual bad/unsupported files still do not abort the complete queue.
- Existing background WorkManager architecture was preserved; no removal of local indexing algorithms or recall reduction was made.

### Important validation limitation
- The GitHub Actions workflow is configured for pushes/PRs to `main`, but the connector currently reports no status checks for the new head commit yet. Therefore the above code changes are committed to the draft PR but are NOT being falsely declared build-verified until GitHub reports a run.
- Runtime verification of Gallery and 7k Folder import still requires a new APK test on the user's Android 12 device.

### Assistant response record for this turn
- Confirmed the distinction between Gallery-not-present, Files 7k crash, and in-app 215,992 Select All behavior.
- Identified MediaStore aggregate-volume duplication and the likely SAF/Binder limitation for giant multi-select result payloads.
- Implemented the source chooser, safe Folder queue, MediaStore counting/selection fixes, thumbnails, and importer retry/error classification in PR #2.
