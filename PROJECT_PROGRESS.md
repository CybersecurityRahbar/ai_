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

### CI correction log

- GitHub Actions run `#19` / commit `8fc7972d...` failed at Kotlin compilation.
- Exact compiler error: `ClassicalVisualFingerprintEngine.kt:263:23 Type mismatch: inferred type is List<KeyPoint!> but Array<KeyPoint> was expected`.
- Root cause: the AKAZE implementation selected top keypoints as a Kotlin `List` while `LocalData` required an `Array`, and the original version also risked losing descriptor-to-keypoint row alignment.
- Fixed in commit `6db7515be442336b6886eba4310bb818e9ec8997` by selecting the original keypoint indices and copying the corresponding descriptor rows into the selected descriptor matrix. Engine version is now `CLASSICAL-PHASH-DHASH-HSV-SOBEL-AKAZE-V2`.
- GitHub Actions run `#20` was automatically started for that fix and is currently in progress. It has not yet been declared successful.

### Verification status

- The earlier debug APK was successfully built and tested by the user for the original Haar implementation.
- The expanded classical stack is not yet build-verified until Actions run `#20` completes successfully.
- The new APK has not yet been runtime-tested on the phone.
- Do not claim that the classical stack improves retrieval until actual device measurements are recorded.

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
