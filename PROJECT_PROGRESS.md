# Personal Memory AI — Project Progress Log

## 2026-08-25 — Reverse-image integration, user test, and digiKam algorithm correction

### Repository state verified

- Repository: `CybersecurityRahbar/ai_`.
- Existing stack includes OCR/Arabic OCR, YOLO objects, face analysis/embeddings/clustering, diagnostics, Room, and a MobileCLIP-S2 visual embedding path.
- MobileCLIP-S2 is intentionally imported locally as a TFLite model; the code requires a built visual index before semantic image search can work.
- GitHub Actions now builds `:app:assembleDebug`; the first CI build succeeded and produced the debug APK.

### User integration feedback

- The first installed build opened the modern `IntelligenceHomeActivity`, but the reverse-image screen was not reachable from that existing command center because no navigation action had been added there.
- Existing older activities were not deleted; they remain registered in the manifest. The fix is to integrate Reverse Image Search into the existing command-center navigation, not replace or isolate the application shell.
- The reverse-image screen is now styled with the same `bg_intelligence`, `panel_intelligence`, and `bg_intel_button` visual system used by the current command center.

### User test result of first reverse-image implementation

- Corpus import and fingerprint indexing worked.
- An exact duplicate/query image was found correctly.
- Screenshot/near-duplicate/other visually similar variants were not found reliably.

### Root cause found

The first Android Haar engine was only an approximation of digiKam, not a source-level implementation of its scoring method. It used 60 coefficients, normalized YIQ values, and a custom coefficient-count score, and it did not retain the Y/I/Q averages used by digiKam.

Direct digiKam source inspection confirmed the actual core:

- 128x128 image with aspect ratio ignored.
- RGB values in 0..255.
- RGB -> YIQ conversion with digiKam's exact coefficients.
- Separable 2-D Haar transform using the same 0.7071 scaling progression and DC adjustment.
- 40 strongest coefficients per channel, encoded as signed indices.
- `SignatureData` stores three Y/I/Q averages plus the 3x40 signed coefficient indices.
- Scoring first adds weighted absolute distance between the Y/I/Q averages, then subtracts the exact per-channel weight for every significant signed coefficient present in both query and target.
- `WeightBin` uses the coefficient's `(row,column)` position and `min(max(row,column), 5)` to select one of the six weight rows.
- The final similarity is derived from digiKam's best/worst possible score range.

These findings are directly supported by digiKam's `haar.cpp`, `haar.h`, and `haariface.cpp` sources in the KDE/digikam repository.

### Current implementation after correction

- `HaarFingerprintEngine.kt` now uses the digiKam 128/40/YIQ/Haar/sign/weight/scoring core and has engine version `DIGIKAM-HAAR-128-40-YIQ-V2`.
- The app-owned fingerprint BLOB is deliberately not claimed to be byte-for-byte compatible with digiKam's `ImageHaarMatrix` Qt serialization.
- `ReverseImageSearchService.kt` now evaluates the original query plus centered 92%, 82%, and 72% crop variants and keeps the best score. This multi-crop query robustness is an application extension; it is not claimed to be digiKam's core algorithm.
- Minimum reverse-search threshold is now 35% by default.
- `IntelligenceHomeActivity.kt` now exposes `LOCAL REVERSE IMAGE SEARCH` using the same command-center action styling and keeps all previous application screens reachable.
- `activity_reverse_image_search.xml` and `item_reverse_image_result.xml` were restyled to match the current application intelligence UI.

### Important capability boundary

Classic digiKam Haar similarity is not a guarantee of viewpoint-invariant object recognition. A substantially different camera angle, different scene composition, or a different photograph of the same object can still score poorly. The goal of this phase is to reproduce digiKam's actual classical similarity behavior and improve screenshot/crop robustness; viewpoint-invariant retrieval may require a separate local-feature stage later.

### Verification status

- Previous CI build succeeded before the current algorithm/UI correction.
- A new CI build is required after the correction commits.
- Device runtime retest is required after the new APK is available.
- Do not claim the new algorithm works better until the new APK is tested.

### Required next verification set

Test the corrected standalone engine with:

1. exact same image
2. JPEG recompression
3. resize
4. screenshot with borders/UI chrome
5. mild crop
6. centered crop
7. brightness/color change
8. burst/near-duplicate
9. unrelated image
10. same object/scene from a noticeably different camera angle

Record the actual similarity scores and rankings here before making any claim about accuracy.
