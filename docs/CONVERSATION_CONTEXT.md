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
2. The earlier 500-image test was performed through `Files -> Screenshots`, not Gallery. Selecting ~500 and then more than 500 images worked. Selecting all ~7,000 images from that folder made the app immediately exit/crash. This remains a real unresolved large-selection regression.
3. The in-app integrated media picker previously showed `VISIBLE 100 SELECTED 215992 ALL MEDIA SELECTED`. Selecting all ~215,992 did NOT immediately crash; the user cancelled the long-running operation manually. This proves the integrated MediaStore selector can hold a huge selection, so the 7,000-file system picker crash is likely specific to the handoff/import representation or post-result processing rather than simply selection count.
4. The in-app MediaStore count is wrong: it reports ~215,992 while the phone's file view reports ~107,994 images. Current code includes `MediaStore.VOLUME_EXTERNAL` and then also iterates external volume names, likely double-counting because `VOLUME_EXTERNAL` is an aggregate view. This must be corrected.
5. The custom integrated picker is currently poor UX: only 100 visible at once, no true image thumbnails, no useful sort/filter by modification date, and weak practical selection/navigation. It should become a real image browser with thumbnail loading, stable paging, sort controls, and large safe selection, while preserving system picker choices.

### Import failure findings
The latest runtime logs show two recurring import failures in `ImageCorpusImportWorker`:
- `تعذر نسخ الصورة محليًا` for ordinary JPEG/JPEG files from `content://com.android.externalstorage.documents/...`.
- `تعذر فك ترميز الصورة` for TIFF files, e.g. `.tif` / `.TIF`.
The failure path currently uses `ContentResolver.openInputStream`, copies every source into the private library, then requires `BitmapFactory.decodeFile` dimensions > 0. This conflates source-copy failures with unsupported image formats. The import path must:
- distinguish inaccessible source/provider failures from unsupported decoder formats;
- preserve successful imports even when individual files are unsupported;
- avoid crashing/aborting the whole queue;
- record precise failure reasons and counts;
- support common image containers robustly where feasible, and explicitly classify unsupported formats such as TIFF instead of reporting a generic decode failure.

### Indexing/background findings
- Shared indexing now uses a WorkManager `CoroutineWorker`, foreground notification, batch persistence, four-way parallel extraction, and durable operation checkpoints.
- Current logs show a completed 1083-item shared index and separate batches. Example: 523 indexed, 560 skipped, 0 failed, extraction ~612s accumulated, persistence <1s. This indicates the persistent DB writes are no longer the primary bottleneck; image/fingerprint extraction is.
- Search can still take ~16–20 seconds over ~1083 candidates and recent runs showed one ~71.6s run. Do not reduce shortlist size or SIFT verification counts as a speed shortcut. The user explicitly rejects recall/accuracy reductions. Future optimization must preserve full candidate recall and full algorithm strength.

### Advanced Visual findings
- The current source shows `AdvancedVisualIntelligenceService` using `AdvancedVisualFingerprintEngine.ENGINE_VERSION` and logs/display strings still say `Advanced V2` (`تحضير 7 نسخ استعلام Advanced V2`, `Advanced V2 variant ...`). This is a likely stale label and possibly indicates the runtime engine is still V2. The system must not claim V4 unless the actual engine and persisted schema version are V4 and connected end-to-end.
- User reports Advanced Visual search and Local Reverse Image Search currently produce essentially the same result ordering, with some weakly related images appearing too high. The advanced scorer needs stronger calibrated reranking and meaningful rejection/low-confidence bands rather than arbitrary percentages.
- Explainability must answer why an image ranked where it did using actual independent evidence (global appearance, local geometry, regional consistency, texture, gradient, structure, illumination, consensus, etc.), not generic labels.
- Advanced Visual result cards should display image thumbnails directly, matching the Local Reverse Image Search UX. Tapping a result should open the full image and show detailed evidence below/alongside it.

### User's non-negotiable architecture requirements
- Keep Local Reverse Image Search unchanged as its own section and retain Haar -> Classical V4 -> AKAZE -> RANSAC and its full-strength retrieval path.
- Advanced Visual Intelligence is a completely separate main-screen section/activity with the same overall color/style system.
- Both sections must share one indexing pass/decode where possible, but each keeps its own persisted fingerprint/index tables.
- Do NOT reduce recall by lowering shortlist/SIFT candidate counts. Optimize by caching, shared decode, parallelism, batched persistence, indexing structures, pruning only by mathematically safe bounds, and efficient storage/querying.
- Long imports/indexing must survive screen rotation, backgrounding, long periods outside the app, and screen-off using durable WorkManager/foreground execution and resumable checkpoints.
- User wants the original ability to browse device Files and Gallery, plus the in-app picker as an additional option.

## Immediate engineering task for this turn
1. Add explicit Gallery/Photo Picker alongside Files in the shared corpus picker without removing existing system-files support.
2. Eliminate MediaStore double-counting and make integrated picker counts authoritative.
3. Prevent large system-picker selections (e.g. ~7k) from crashing the app during result handoff; keep URI queues disk-backed and streaming.
4. Harden `ImageCorpusImportWorker` provider-copy/decoder handling so ordinary JPEG sources are copied reliably and unsupported TIFFs are classified without invalidating the entire import.
5. Preserve/extend the existing background/resumable behavior.
6. Audit runtime labels/version claims in Advanced Visual so they match the actual engine/schema and never advertise an unconnected version.
7. Update this context file again with the concrete changes and validation status after the code changes.
