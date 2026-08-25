# Personal Memory AI — Project Plan

> Persistent execution reference. Read this document before making project changes. Update it whenever the architecture, roadmap, or implementation status changes.

## 1. Mission

Build a local-first Android Personal Memory / Evidence Intelligence system that indexes images on-device and lets the user retrieve them using OCR, metadata, objects, faces, visual embeddings, and dedicated reverse-image search. No cloud is required for the core pipeline.

## 2. Current architecture

- `database/`: Room persistence for images, objects, faces, persons, and ML embeddings.
- `indexing/`: image ingestion and analysis orchestration; OCR, Arabic OCR, YOLO objects, face indexing, and visual embedding indexing.
- `semantic/`: MobileCLIP-S2 image embeddings and cosine-similarity search. TextEncoder is reserved and not yet implemented as a working text model.
- `vision/`: face detection/landmarks/quality/pose/embeddings/matching/clustering.
- `intelligence/`: evidence and multi-signal analysis components.
- `diagnostics/`: stage health and model/pipeline diagnostics.
- `ui/`: Android screens for data, evidence, faces, objects, OCR, intelligence, image viewing, and reverse-image search.
- `reverseimage/`: standalone classical visual-search subsystem. It intentionally does not depend on MobileCLIP, OCR, face AI, or the legacy `images` search path.

## 3. Important truth about the current system

The codebase contains a complete MobileCLIP-S2 integration path, but the actual MobileCLIP-S2 FP16 TFLite model is intentionally not committed to Git. The user must import the validated model locally and build the visual index before semantic image search can return results. Do not claim that MobileCLIP search is working unless model readiness and persisted compatible image embeddings have been verified.

## 4. Standalone local reverse-image search

This feature is intentionally **separate from the existing semantic search system**, but it remains a first-class screen inside the existing application shell and command center.

### User experience

1. Open `LOCAL REVERSE IMAGE SEARCH` from the existing Intelligence Command Center.
2. Add local images to the dedicated reverse-image corpus.
3. Build/rebuild the dedicated fingerprint index.
4. Pick a query image from the device.
5. Compute its classical visual fingerprints locally.
6. Search only the dedicated fingerprint index.
7. Return ranked local matches with a transparent score breakdown.

### Classical algorithm stack

The reverse-image engine is a deliberately non-neural computer-vision subsystem:

- **DigiKam-style Haar/Wavelet fingerprint**: 128x128, RGB->YIQ, separable 2-D Haar, 40 strongest signed coefficients per channel, stored Y/I/Q averages, `WeightBin`, and best/worst score normalization.
- **pHash**: explicit low-frequency DCT with the DC term excluded from the hash comparison.
- **dHash**: full 64-bit horizontal luminance difference hash over a 9x8 sample grid.
- **Color fingerprint**: HSV distribution histogram with L1/total normalization, preserving distribution instead of peak-bin dominance.
- **Shape/edge fingerprint**: spatial gradient magnitude/direction signature with L1 normalization.
- **AKAZE local features**: classical binary local descriptors, persisted for each corpus image when OpenCV is available.
- **Mutual local matching**: ratio-tested AKAZE matches must agree in both query->target and target->query directions.
- **RANSAC geometric verification**: mutual matches are checked through homography estimation; local evidence is valid only when geometric inliers exist.

OpenCV is used only for classical local-feature operations; no neural network inference is introduced by this subsystem. The current Maven artifact is `org.opencv:opencv:4.13.0` (Apache-2.0). Do not confuse this dependency with the existing TFLite/ML systems.

The current classical engine version is `CLASSICAL-PHASH-DHASH-HSV-SOBEL-AKAZE-V3`. The BLOB schema is unchanged; the version marker deliberately invalidates older classical fingerprints and causes re-indexing.

### Search architecture

Reverse-image search is now explicitly staged:

1. Build multi-region query fingerprints (original plus centered 0.92/0.82/0.72 crops) for both Haar and classical global signals.
2. Run cheap global retrieval across the entire corpus using Haar + pHash/dHash/color/edge.
3. Keep a bounded high-recall shortlist (currently 96–192 candidates depending on requested result count).
4. Run expensive AKAZE mutual matching and RANSAC geometry only on the shortlist.
5. Rerank using local geometric evidence while retaining transparent component telemetry.

This architecture is intended to preserve recall while preventing `N × local-feature matching` from becoming the normal path. The exact shortlist bounds remain tunable after device benchmarking.

### Search fusion policy

- Haar remains the primary anchor because it has been directly validated by the user for exact/near visual matches.
- pHash/dHash/color/edge provide independent global evidence.
- AKAZE/RANSAC provides local structural evidence when available.
- Current top-level fusion is explicit and non-learned: Haar 55% + classical composite 45%.
- These weights are provisional and must be adjusted only after measured benchmark results.
- Never hide the component scores; the UI should expose enough telemetry to understand why a result ranked highly.

### Engineering constraints

- Keep the reverse-image feature in its own package/service and dedicated Room tables/DAOs.
- Do not replace or modify the existing MobileCLIP search path in this phase.
- Do not make main text search depend on reverse-image search.
- Keep the UI inside the existing application shell but visually consistent with the Intelligence Command Center.
- Index incrementally using item ID plus engine-version/configuration markers.
- Keep diagnostics for index build, OpenCV/local-feature availability, invalid images, query failures, result counts, shortlist size, and local verification coverage.
- Keep durable private image copies independent of transient `DocumentsProvider` permissions.
- The fingerprint BLOB format is app-owned. Do not claim byte-for-byte compatibility with digiKam's `ImageHaarMatrix` without direct binary conformance testing.

## 5. Future evolution of the classical subsystem

- **First priority: benchmark V3 on the existing 999-image corpus** before changing top-level weights.
- Add stronger **multi-region local verification** for screenshots and large crops if the benchmark shows global recall is insufficient.
- Consider multiple local crops/tiling or adaptive region proposals rather than simply increasing descriptor counts globally.
- Add face/person search as a separate identity-evidence mode using the existing face subsystem; do not force Haar to solve identity recognition.
- Consider additional classical descriptors only when a benchmark demonstrates a specific gap: multi-scale local regions, line/contour signatures, improved spatial-color descriptors, or stronger geometric verification.
- Only after the classical baseline is measured should we revisit MobileCLIP or stronger neural encoders.

## 6. Verification policy

A feature is not considered complete merely because code compiles. For every visual-search engine, verify:

- identical file/content
- resized image
- recompressed image
- screenshot with UI/borders
- mild crop
- larger crop
- brightness/color change
- burst/near-duplicate
- unrelated image
- same object/scene from a different viewpoint
- perspective change
- screenshot containing the source image as a region

Record actual top-10 rankings and component scores in `PROJECT_PROGRESS.md` before claiming accuracy. The next algorithmic weight changes must be evidence-driven from this benchmark.

## 7. Persistent context rule

- Read this file and `PROJECT_PROGRESS.md` before continuing work.
- Update `PROJECT_PROGRESS.md` after meaningful implementation or verification.
- Keep architectural decisions and constraints in this file.
- Keep chronological implementation/testing facts in `PROJECT_PROGRESS.md`.
- Never infer runtime quality from source existence or build success alone.
