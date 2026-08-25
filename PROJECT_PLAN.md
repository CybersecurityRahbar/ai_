# Personal Memory AI — Project Plan

> Persistent execution reference. Read this document before making project changes. Update it whenever the architecture, roadmap, or implementation status changes.

## 1. Mission

Build a local-first Android Personal Memory / Evidence Intelligence system that indexes images on-device and lets the user retrieve them using OCR, metadata, objects, faces, visual embeddings, and dedicated reverse-image search. No cloud is required for the core pipeline.

## 2. Current architecture

- `database/`: Room persistence for images, objects, faces, persons, and ML embeddings.
- `indexing/`: image ingestion and analysis orchestration; OCR, Arabic OCR, YOLO objects, face indexing, and visual embedding indexing.
- `semantic/`: MobileCLIP-S2 image embeddings and cosine-similarity search. This remains intentionally out of scope for the current classical-search phase.
- `vision/`: face detection/landmarks/quality/pose/embeddings/matching/clustering.
- `intelligence/`: evidence and multi-signal analysis components.
- `diagnostics/`: stage health and model/pipeline diagnostics.
- `ui/`: Android screens for data, evidence, faces, objects, OCR, intelligence, image viewing, and reverse-image search.
- `reverseimage/`: independent classical visual-search subsystem inside the existing application shell. It does not depend on MobileCLIP, OCR, face AI, or the legacy `images` semantic-search path.

## 3. Classical reverse-image architecture

### Persistent/indexed features

- DigiKam-style Haar/Wavelet fingerprint: 128x128, RGB->YIQ, separable 2-D Haar, 40 strongest signed coefficients per channel, Y/I/Q averages, digiKam WeightBin and weights, best/worst normalization.
- 64-bit pHash based on low-frequency DCT coefficients with DC excluded.
- 64-bit dHash using a complete 9x8 luminance comparison grid.
- 256-bin L1-normalized HSV histogram: 16 Hue × 4 Saturation × 4 Value.
- 128-bin spatial Sobel gradient magnitude/direction signature over a 4x4 spatial grid.
- Persisted AKAZE binary local descriptors and keypoints for cheap shortlist verification.

### Shortlist-only verification

- AKAZE mutual matching + Lowe-style ratio test + homography RANSAC.
- SIFT verification using OpenCV 4.13 `features2d.SIFT`, BFMatcher L2, mutual consistency, ratio test, and homography RANSAC. SIFT is not persisted for every image; it runs only on the high-recall shortlist.
- Query global retrieval supports original, centered crop variants, and 90°/180°/270° rotations.

### Search architecture

1. Compute global fingerprints for the query.
2. Scan the complete corpus using cheap Haar + pHash/dHash/color/edge evidence.
3. Retain a high-recall shortlist, bounded to 48–192 candidates depending on result count.
4. Run persisted AKAZE geometric verification on the shortlist.
5. Run SIFT geometric verification on the same shortlist.
6. Fuse only geometrically validated local evidence into the global score.
7. Rank and return transparent component telemetry.

The explicit global fusion remains Haar 55% + classical 45% until controlled measurements justify another weight. SIFT is an additional local-evidence verifier; it is not a replacement for Haar.

## 4. Engineering constraints

- Keep reverse-image storage isolated in its own corpus/tables/DAOs.
- Keep the feature accessible through `IntelligenceHomeActivity`, not as a second Android application launcher.
- Preserve durable app-private image copies so search results never depend on transient DocumentsProvider permissions.
- Do not modify MobileCLIP during this phase.
- Do not claim byte-for-byte compatibility with digiKam's database blobs; only the source-level Haar calculation is being reproduced.
- Incremental index entries are versioned. Changing the classical fingerprint schema/version requires a full reverse-image index rebuild.

## 5. Accuracy roadmap

The classical engine should be improved by measured evidence, not by arbitrary weight tuning.

Priority order:

1. Verify current v4 on the 999-image corpus.
2. Benchmark exact/recompressed/resized/screenshot/crop/burst cases.
3. Benchmark rotation, perspective, and source-inside-screenshot cases.
4. Inspect per-component scores and Top-10 ranks.
5. Only add another descriptor or change weights if a benchmark demonstrates a reproducible gap.
6. Face/person identity search remains a separate future mode and should use the existing face subsystem rather than forcing Haar to solve identity.
7. Only after the classical baseline is measured should stronger neural image encoders be revisited.

## 6. Verification policy

A feature is not considered complete merely because code compiles. Record actual runtime results and top-10 rankings for:

- identical file/content
- resized image
- recompressed image
- screenshot with borders/UI chrome
- mild crop
- large crop
- brightness/color change
- burst/near-duplicate
- unrelated image
- same object/scene from a different viewpoint
- perspective change
- 90°/180°/270° rotation
- source image embedded inside a screenshot
- source image embedded inside a larger unrelated image

Do not claim an accuracy percentage without a measured benchmark.

## 7. Persistent context rule

- Read this file and `PROJECT_PROGRESS.md` before making project changes.
- Update `PROJECT_PROGRESS.md` after meaningful implementation or verification.
- Keep architectural constraints and roadmap here.
- Keep chronological implementation/testing facts in `PROJECT_PROGRESS.md`.
- Never infer runtime quality from source existence or build success alone.
