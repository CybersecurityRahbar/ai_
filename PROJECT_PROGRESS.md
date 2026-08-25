# Personal Memory AI — Project Progress Log

## 2026-08-25 — Reverse-image classical stack v4: SIFT verification + rotation-aware retrieval

### New classical verification layer

- Added `SiftLocalVerifier.kt`.
- Uses OpenCV 4.13 SIFT on the staged shortlist only; SIFT is not persisted per image, so the Room fingerprint database does not grow with a second large floating-point descriptor family.
- Uses BFMatcher with L2 distance, Lowe-style ratio filtering, mutual consistency, and homography RANSAC.
- SIFT evidence is accepted only when geometric inliers are present.
- This layer is complementary to persisted AKAZE: AKAZE supplies the cheap durable local evidence; SIFT supplies a stronger scale/rotation-sensitive verification pass on shortlisted candidates.
- OpenCV 4.13 provides the `org.opencv.features2d.SIFT` Java API and BFMatcher L2 is the documented matching norm for SIFT descriptors. citeturn486311search0turn486311search3

### Rotation-aware global retrieval

- Reverse-image query generation now includes 0°, 90°, 180°, and 270° Haar/classical global variants in addition to the original and center-crop variants.
- This makes the cheap global stage much less likely to discard a rotated copy before local geometric verification.
- SIFT/AKAZE remain responsible for fine local geometry; rotation variants are mainly a recall improvement for Haar/pHash/color/edge retrieval.

### Search pipeline now

1. Global Haar + pHash + dHash + color + edge retrieval over the complete corpus.
2. Query variants: original, three centered crop levels, and three right-angle rotations for the global stage.
3. High-recall shortlist capped at 48–192 candidates depending on requested result count.
4. Persisted AKAZE mutual matching + RANSAC on the shortlist.
5. SIFT + mutual L2 matching + RANSAC on the same shortlist.
6. Classical local evidence is fused conservatively with global evidence only when geometric verification succeeds.
7. Haar remains the 55% primary anchor and classical evidence remains 45%; no learned weight has been introduced.

### Important design decision

SIFT is intentionally **not indexed/persisted** yet. Persisting SIFT for every image would significantly expand storage because standard SIFT descriptors are floating-point vectors. The shortlist architecture gives us the accuracy benefit without paying that storage cost across the entire corpus. The trade-off is extra CPU on shortlisted candidates, which is acceptable at the current 999-image scale and will be benchmarked on-device.

### Verification status

- User confirmed GitHub Actions run #37 succeeded for the all-variant local geometric reranking version.
- User has intentionally not installed the current APK yet; device verification remains deferred until the current algorithmic pass is complete.
- The current SIFT/rotation changes require a new CI result before they are considered build-verified.
- No accuracy gain percentage is claimed until the controlled benchmark is run.

### Required benchmark

Test at minimum:

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
12. rotated image (90°, 180°, 270°)
13. source image embedded inside a screenshot
14. source image embedded inside a larger unrelated image

For every case record top-10 ranking, overall score, Haar agreement, pHash, dHash, color, edge, AKAZE local score/matches/inliers, and SIFT matches/inliers. Future tuning decisions should be based on these measurements.

## Earlier 2026-08-25 entries

The reverse-image feature remains a first-class screen inside the existing application shell, with its own corpus and Room fingerprint tables. MobileCLIP and the legacy semantic pipeline remain deliberately untouched during this classical-search phase. The previously confirmed 999-image test corpus is intentional and is not an indexing defect.
