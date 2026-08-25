# Personal Memory AI — Project Progress Log

## 2026-08-25 — Persistent context + standalone local reverse-image search

### Repository state verified

- Repository: `CybersecurityRahbar/ai_`.
- Existing stack includes OCR/Arabic OCR, YOLO objects, face analysis/embeddings/clustering, diagnostics, Room, and a MobileCLIP-S2 visual embedding path.
- MobileCLIP-S2 is intentionally imported locally as a TFLite model; the code requires a built visual index before semantic image search can work.

### digiKam research

- digiKam Similarity View provides Find Duplicates, Find Similar Image, and Find by Sketch.
- Its fingerprint engine uses Wavelets/Haar based on Jacobs, Finkelstein and Salesin's `Fast Multi-Resolution Image Querying` (SIGGRAPH 1995).
- Fingerprints are calculated in the background and stored separately for similarity search.
- Historical digiKam developer material describes serialized `Haar::SignatureData` in `ImageHaarMatrix` and a search metric based on agreement of significant signed wavelet coefficients.
- Current 2026 digiKam reports still reference `similarity.db` and `ImageHaarMatrix`, so the Haar fingerprint architecture remains relevant.

### Architecture decision

The reverse-image feature is intentionally **independent from the previous indexing/semantic system**.

It now has:

- `reverseimage/HaarFingerprintEngine.kt`
- `reverseimage/ReverseImageSearchService.kt`
- `reverseimage/ReverseImageItemEntity.kt`
- `reverseimage/ReverseImageItemDao.kt`
- `reverseimage/HaarFingerprintEntity.kt`
- `reverseimage/HaarFingerprintDao.kt`
- dedicated Room tables for the reverse-image corpus and fingerprints
- dedicated `ReverseImageSearchActivity.kt`
- dedicated result adapter/layouts
- dedicated launcher alias (`Reverse Image Search`)

The feature does not depend on MobileCLIP, OCR, face indexing, or the legacy `images` table for its corpus/search.

### Current behavior implemented

1. User opens the separate Reverse Image Search entry.
2. User adds multiple local images to the feature's own corpus.
3. The feature builds/rebuilds persistent Haar/YIQ fingerprints.
4. User selects a separate query image.
5. The query gets a new fingerprint locally.
6. Stored fingerprints are compared and ranked by coefficient agreement.
7. Results show the image, name/path, similarity percentage, and matched coefficient count.
8. A similarity threshold can filter results.

### Algorithm implementation note

The Android implementation follows the documented digiKam-style/Fast-Multi-Resolution recipe: 128x128 representation, YIQ channels, standard 2-D Haar decomposition, strongest 60 signed coefficients per channel, sparse serialization, and coefficient-agreement ranking. The serialized fingerprint is an app-owned format; it is **not claimed to be byte-for-byte compatible with digiKam's `ImageHaarMatrix` blob** without direct source-level verification.

### Verification status

- Code and database integration committed to the default branch.
- Persistent project plan committed as `PROJECT_PLAN.md`.
- Persistent progress log committed as this file.
- Temporary test files created during editing were removed.
- **Android build/runtime verification has not yet been completed in this environment.** Do not claim the feature is production-ready until `assembleDebug`/release build and device tests pass.

### Required next verification set

Test the standalone engine with:

- identical image
- same image after JPEG recompression
- resize
- mild crop
- mild brightness/color change
- unrelated image
- burst/near-duplicate images

Record actual scores and ranking behavior here before changing thresholds or claiming accuracy.
