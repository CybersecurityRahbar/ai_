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
- `ui/`: Android screens for data, evidence, faces, objects, OCR, intelligence, and image viewing.

## 3. Important truth about the current system

The codebase contains a complete MobileCLIP-S2 integration path, but the actual MobileCLIP-S2 FP16 TFLite model is intentionally not committed to Git. The user must import the validated model locally and build the visual index before semantic image search can return results. Do not claim that MobileCLIP search is working unless model readiness and persisted compatible image embeddings have been verified.

## 4. New feature: standalone local reverse-image search

This feature is intentionally **separate from the existing semantic search system**.

### User experience

1. Open a dedicated Reverse Image Search screen.
2. Build/rebuild a dedicated local visual fingerprint index for selected/all indexed images.
3. Pick or share/drop a query image into the screen.
4. Compute a query fingerprint locally.
5. Search only the dedicated fingerprint index.
6. Return exact/nearly identical and visually similar local images ranked by similarity.
7. Show thumbnail, file name, path/URI, date, and similarity score.

### Algorithm target

Implement the digiKam-style Wavelets/Haar approach as documented by digiKam:

- Each image receives a persistent visual fingerprint/signature.
- The fingerprint is based on Haar wavelet processing derived from Jacobs, Finkelstein and Salesin, `Fast Multi-Resolution Image Querying` (SIGGRAPH 1995).
- The classic method resizes to a fixed square representation, converts color channels, applies Haar wavelet decomposition, keeps the strongest coefficients and quantizes/signs them for efficient comparison.
- digiKam stores the fingerprint separately from the main image database and uses it for similar-image, duplicate, and sketch searches.
- Do not mix this fingerprint with MobileCLIP embeddings. They are independent engines.

### Engineering constraints

- Keep the reverse-image feature in its own package/service and its own Room table/DAO.
- Do not replace the existing MobileCLIP system.
- Do not make the main text-search code depend on this feature.
- Keep the UI as a dedicated screen.
- Index incrementally by image ID plus a source/version/configuration marker so the index can be rebuilt safely.
- Treat the implementation as digiKam-inspired/compatible unless exact digiKam source behavior has been verified; never claim byte-for-byte compatibility without evidence.
- Keep diagnostics for index build, invalid images, query failures, and result counts.

## 5. Planned evolution after the standalone Haar engine

- Add robust perceptual hashes as a second exact/fuzzy duplicate layer.
- Improve local vector retrieval for MobileCLIP at large scale instead of scanning every embedding.
- Add stronger on-device image encoders after the current baseline is validated.
- Implement a production text encoder only after the model and retrieval contract are verified.
- Optionally fuse independent signals only in a later ranking layer; the standalone reverse-image screen must remain understandable and independently testable.

## 6. Verification policy

A feature is not considered complete merely because code compiles. For every visual-search engine, verify:

- identical file/content
- resized image
- recompressed image
- mild crop
- mild brightness/color change
- unrelated image
- several near-duplicate burst images

Record the observed ranking/threshold behavior in the progress document.

## 7. Do not forget

- Read this file and `PROJECT_PROGRESS.md` before continuing work.
- Update `PROJECT_PROGRESS.md` after meaningful implementation or verification.
- Keep architectural decisions in this file.
- Keep chronological implementation/testing facts in `PROJECT_PROGRESS.md`.
