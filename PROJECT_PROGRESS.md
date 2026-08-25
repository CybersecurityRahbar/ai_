# Personal Memory AI — Project Progress Log

## 2026-08-25 — Classical reverse-image stack expansion

### Verified repository state

- Repository: `CybersecurityRahbar/ai_`.
- Existing application stack remains intact: OCR/Arabic OCR, YOLO objects, face analysis/embeddings/clustering, diagnostics, Room, and a MobileCLIP-S2 path.
- MobileCLIP is intentionally out of scope for this phase and has not been modified.
- GitHub Actions builds `:app:assembleDebug` and uploads the debug APK artifact.

### Reverse-image architecture

The reverse-image feature remains a separate search/indexing capability inside the existing application shell. It is reachable from `IntelligenceHomeActivity` and uses the modern command-center visual language. Its corpus and fingerprint tables are separate from the legacy `images`/semantic pipeline.

### Classical visual technologies added

1. **DigiKam-style Haar/Wavelet fingerprint**
   - 128x128 fixed representation.
   - Exact digiKam-style RGB -> YIQ conversion.
   - Separable 2-D Haar transform.
   - 40 strongest signed coefficients per channel.
   - Y/I/Q average values retained.
   - digiKam weight rows and `WeightBin` position weighting.
   - digiKam-style best/worst normalization for similarity.
   - Engine version: `DIGIKAM-HAAR-128-40-YIQ-V2`.

2. **Perceptual hashes**
   - 64-bit pHash using an explicit low-frequency DCT.
   - 64-bit dHash based on luminance gradients.

3. **Color fingerprint**
   - HSV-derived color distribution stored as a compact histogram.
   - Used as independent evidence, not as a replacement for Haar.

4. **Shape / edge fingerprint**
   - Sobel-derived gradient magnitude and orientation statistics over a spatial grid.
   - Used to capture structural changes that color-only evidence misses.

5. **AKAZE local features**
   - OpenCV Android AAR added for classical local feature extraction only.
   - No neural model is involved.
   - Keypoints and binary descriptors are persisted in the reverse-image database.

6. **RANSAC geometric verification**
   - AKAZE matches use a ratio test.
   - Good matches are passed to homography estimation with RANSAC.
   - Inlier count is retained as geometric evidence, helping distinguish real local correspondence from accidental descriptor matches.

### New persistence

- `ClassicalVisualFingerprintEntity.kt`
- `ClassicalVisualFingerprintDao.kt`
- Room database version 9.
- `MIGRATION_8_9` creates `classical_visual_fingerprints` with pHash, dHash, color/edge histograms, and optional AKAZE keypoints/descriptors.

### Search fusion

`ReverseImageSearchService.kt` now builds and queries both fingerprint families.

The ranking uses:

- Haar as the primary anchor signal.
- Classical pHash/dHash/color/edge evidence as global refinement.
- AKAZE + RANSAC as local geometric evidence when available.
- Results expose a total similarity plus per-signal telemetry so accuracy can be inspected instead of hiding everything behind one opaque score.

The current fusion is intentionally conservative: Haar contributes 55% and the classical composite contributes 45%. This weighting must be validated experimentally; it is not presented as an optimal learned weight.

### UI

- Reverse Image Search remains reachable from the existing `IntelligenceHomeActivity`.
- The result card exposes Haar coefficient agreement, pHash, dHash, color, shape, AKAZE similarity, local-match count, and RANSAC inliers.
- Index status reports both Haar and classical fingerprint coverage.

## 2026-08-25 — Reverse-image integration repair pass

### Confirmed user test size

- The user indexed **999 images** in the reverse-image corpus. `999` is intentional and is not considered an indexing defect.

### Integration fixes applied

- Removed the independent `ReverseImageSearchLauncher` `MAIN/LAUNCHER` entry from `AndroidManifest.xml`. `IntelligenceHomeActivity` is now the single application launcher, so Android should no longer expose a second `Reverse Image Search` application icon/name.
- Reverse-image corpus selection now uses `GetMultipleContents`, matching the existing main image-ingestion picker family instead of `OpenMultipleDocuments`.
- Reverse-image corpus images are copied into app-private durable storage under `filesDir/reverse_image/library` at ingestion time.
- `ReverseImageItemEntity.filePath` now points to the durable local image copy while `uri` remains the original source/provenance URI.
- Existing legacy reverse-image rows are migrated lazily at build/search time by recreating a private local copy when `filePath` is not an existing file.
- Search result rendering now prefers durable local files and therefore no longer depends on `MediaDocumentsProvider` URI grants after the picker closes.
- Reverse-image result adapter now has an explicit `clear()` operation.
- Starting a new query clears the previous result list and cancels the previous search job before executing the next search.
- Added a dedicated visible indexing telemetry panel showing percentage, `processed/total`, processing rate, ETA, success count, skipped count, local-feature count, and failure count.
- The same progress panel is reused for image-copy preparation and query execution state.

### CI verification and exact failure record

- GitHub Actions `Android Build` run #28 (`32887831504`) failed during `:app:compileDebugKotlin`.
- Android SDK setup, resource processing, Room/KAPT, dex/native packaging, and OpenCV native library packaging all completed successfully before the Kotlin compiler failure.
- Exact compiler error: `ReverseImageSearchService.kt:330:17 Suspend function 'upsert' should be called only from a coroutine or another suspend function`.
- Root cause: the lazy durable-copy helper `ensurePrivateCopy()` called the Room DAO `upsert()` from a non-suspend function.
- Fixed in commit `92af21f802f064ba9772b6b0bac0646e1254457c` by making `ensurePrivateCopy()` suspend. Its callers are already inside suspend/coroutine contexts, so the DAO write is now correctly awaited without `runBlocking` or blocking the thread.
- The next GitHub Actions run is expected to validate this exact correction. No new APK is claimed until that run succeeds.

### Runtime bug being addressed

The user-observed crash was traced to `ReverseImageResultAdapter` calling `ImageView.setImageURI()` with a `content://com.android.providers.media.MediaDocumentsProvider/...` URI after its permission was no longer valid. The repair removes that long-lived dependency by displaying durable private files instead.

### Verification status

- The expanded classical stack previously built successfully and the user tested the earlier Haar implementation on 999 images.
- The current integration-repair branch is not yet device-verified after the durable-copy changes.
- Accuracy weights remain provisional until the controlled benchmark in the next section is completed.

### Required device benchmark

Use a controlled corpus containing:

1. exact same image
2. JPEG recompression
3. resize
4. screenshot with borders/UI chrome
5. mild crop
6. larger crop
7. brightness/color change
8. burst/near-duplicate
9. unrelated image
10. same subject/object from a different viewpoint
11. perspective change
12. screenshot containing the source image as a region

For every case record the top-10 rankings and the component scores. The next optimization decisions must be based on these measured results, not on visual inspection alone.
