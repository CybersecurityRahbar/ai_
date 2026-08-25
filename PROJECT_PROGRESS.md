# Personal Memory AI — Project Progress Log

## 2026-08-26 — Full-strength reverse-image search performance pass

### User requirement

The search must become much faster **without reducing retrieval coverage, shortlist strength, or any verification algorithm**. Reducing the 64-candidate shortlist or the 16-candidate SIFT stage is explicitly rejected.

### Implemented

- Restored the full `GLOBAL_SHORTLIST_MAX = 64`.
- Restored the full `SIFT_RERANK_LIMIT = 16`.
- The global stage still evaluates the complete indexed corpus (including the current intentional 999-image corpus).
- AKAZE + mutual matching + RANSAC still runs on all 64 shortlist candidates.
- SIFT + mutual matching + RANSAC still runs on all 16 final candidates.
- Added bounded parallel execution with a maximum of four concurrent CPU tasks for global retrieval, AKAZE/RANSAC reranking, and SIFT/RANSAC reranking. The algorithms and thresholds are unchanged; only execution scheduling changed.
- Search progress now explicitly reports `64/64` and `16/16`, making it visible that no accuracy-saving shortcut is being taken.
- Added end-to-end `durationMs` and `parallelism` diagnostics so on-device timing can be measured by stage rather than guessed.
- The previous expensive repeated `buildClassicalQueryVariants(queryBitmap)` inside each shortlist candidate was already removed; query variants are computed once and reused.

### Design principle

Never solve performance by reducing the candidate population or removing a verifier. First optimize repeated computation, native/OpenCV object creation, disk I/O, and CPU scheduling. Accuracy coverage remains the invariant.

### CI status

- Commit: `116f56554627912bdd354d1cc17ef6f72947b9a3`
- GitHub Actions build was triggered automatically from `main`; APK validity must be confirmed by the resulting CI run before device installation.
- The previous successful build #47 remains valid as the last known build, but it contains the rejected reduced 40/8 search configuration and is therefore not the target test version.

## 2026-08-25 — Reverse-image classical stack v4: SIFT + rotation + HSV256

### Latest algorithmic additions

- `SiftLocalVerifier.kt` added as a shortlist-only classical verifier using OpenCV SIFT, BFMatcher L2, mutual matching, ratio filtering, and homography RANSAC.
- SIFT is not persisted for every corpus image; it is intentionally executed only on the high-recall shortlist candidates to gain scale/rotation robustness without a large floating-point descriptor database.
- Global query retrieval includes 0°, 90°, 180°, and 270° variants for Haar and classical global fingerprints, plus centered crop variants.
- `ClassicalVisualFingerprintEngine.kt` upgraded to V4 with a full **16×4×4 HSV histogram = 256 bins**, including Value as an explicit dimension. Histograms remain L1 normalized.
- The V4 fingerprint-version change intentionally makes existing classical fingerprints stale and requires a full reverse-image index rebuild before accuracy measurements.

### Current classical stack

`Haar + pHash + dHash + HSV256 + spatial Sobel + persisted AKAZE/RANSAC + shortlist SIFT/RANSAC`

MobileCLIP and the prior neural semantic path remain untouched.

### Verification status

- User confirmed previous GitHub Actions builds succeeded for the all-variant AKAZE geometric reranking versions.
- Device verification is intentionally deferred until the full-strength performance pass is build-verified.
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
- Earlier CI run #28 failed from a suspend DAO call and was fixed; subsequent builds succeeded and produced debug APKs.
- The user's corpus size of 999 images is intentional and is not an indexing defect.
