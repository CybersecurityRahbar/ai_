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
   - HSV-derived spatially/global color distribution stored as a compact histogram.
   - Used as independent evidence, not as a replacement for Haar.

4. **Shape / edge fingerprint**
   - Sobel-derived gradient magnitude and orientation statistics over a spatial grid.
   - Used to capture structural changes that color-only evidence misses.

5. **AKAZE local features**
   - OpenCV 4.13.0 Android AAR added for classical local feature extraction only.
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
- The result card now exposes Haar coefficient agreement, pHash, dHash, color, shape, AKAZE similarity, local-match count, and RANSAC inliers.
- Index status now reports both Haar and classical fingerprint coverage.

### Verification status

- The earlier debug APK was successfully built and tested by the user for the original Haar implementation.
- The expanded classical stack has triggered a new GitHub Actions build; the build must finish successfully before this phase can be considered build-verified.
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
