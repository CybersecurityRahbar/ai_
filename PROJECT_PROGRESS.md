# Personal Memory AI — Project Progress Log

## 2026-08-25 — Reverse-image classical stack v4: SIFT + rotation + HSV256

### Latest algorithmic additions

- `SiftLocalVerifier.kt` added as a shortlist-only classical verifier using OpenCV 4.13 SIFT, BFMatcher L2, mutual matching, ratio filtering, and homography RANSAC.
- SIFT is not persisted for every corpus image; it is intentionally executed only on the 48–192 high-recall shortlist candidates to gain scale/rotation robustness without a large floating-point descriptor database.
- Global query retrieval now includes 0°, 90°, 180°, and 270° variants for Haar and classical global fingerprints, plus centered crop variants.
- `ClassicalVisualFingerprintEngine.kt` upgraded from V3 to V4 with a full **16×4×4 HSV histogram = 256 bins**, including Value as an explicit dimension. Histograms remain L1 normalized.
- The V4 fingerprint-version change intentionally makes existing classical fingerprints stale and requires a full reverse-image index rebuild before accuracy measurements.

### Current classical stack

`Haar + pHash + dHash + HSV256 + spatial Sobel + persisted AKAZE/RANSAC + shortlist SIFT/RANSAC`

MobileCLIP and the prior neural semantic path remain untouched.

### Verification status

- User confirmed the previous GitHub Actions build (#37) succeeded for the all-variant AKAZE geometric reranking version.
- The SIFT/rotation and HSV256 commits have triggered new GitHub Actions builds; these must finish successfully before a new APK is considered build-verified.
- User intentionally has not installed the current APK yet; device verification is deferred until the classical algorithmic development pass is complete.
- No accuracy percentage is claimed until the required controlled benchmark is executed on-device.

### Required benchmark

After the current CI chain succeeds, rebuild the 999-image corpus and measure:

1. exact same image
2. JPEG recompression
3. resize
4. screenshot with borders/UI chrome
5. mild crop
6. large crop
7. brightness/color change
8. burst/near-duplicate
9. unrelated image
10. same object/scene from a different viewpoint
11. perspective change
12. 90°/180°/270° rotation
13. source image embedded inside a screenshot
14. source image embedded inside a larger unrelated image

For every test record Top-10 ranking, overall similarity, Haar agreement, pHash, dHash, color, edge, AKAZE matches/inliers, and SIFT matches/inliers.

## 2026-08-25 — Reverse-image classical stack v3 and integration history

- DigiKam-style Haar/Wavelet fingerprint is the primary anchor and was validated by the user on a 999-image corpus.
- Classical V3 corrections fixed dHash coverage, pHash DC exclusion, histogram normalization, AKAZE mutual matching, and RANSAC gating.
- Search was changed to global retrieval followed by shortlist local verification to avoid running expensive local matching over the entire corpus.
- Reverse-image remains inside `IntelligenceHomeActivity`; it is not a second Android application.
- Durable app-private copies eliminate transient `MediaDocumentsProvider` result/display failures.
- Index UI includes processed/total, percent, rate, ETA, indexed/skipped/failed/local-feature counts.
- Earlier CI run #28 failed from a suspend DAO call and was fixed; run #30 succeeded and produced a debug APK.
- The user's corpus size of 999 images is intentional and is not an indexing defect.
