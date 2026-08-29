# Conversation Turn Ledger — Personal Memory AI

## Recording rule

For every subsequent project-related user turn and assistant turn, record a durable entry here or update the persistent context files. Each entry must preserve the user requirement, observed evidence, decisions, implementation actions, unresolved issues, and the next gate. This ledger complements `PROJECT_CONVERSATION_CONTEXT.md`, `PROJECT_PLAN.md`, and `PROJECT_PROGRESS.md`.

## 2026-08-28 — Picker clarification + large-selection/provider hardening

The user clarified that Gallery/Studio had not actually been tested in the affected build: `OPEN SYSTEM FILES / GALLERY` exposed only the system-file path. The 500+ tests were through `Files -> Screenshots`; approximately 500 and more than 500 worked, while selecting all roughly 6,000–7,000 images caused the app to exit. The in-app MediaStore browser separately showed about 215,992 images while the device's file view showed about 107,994; Select All in the in-app browser remained stable during the test.

Implemented and recorded:
- real Gallery/Studio route plus Photo Picker, system Files, Folder/Large Corpus, and in-app MediaStore source choices;
- disk-backed URI queues and logical Select All instead of giant in-memory URI lists;
- external MediaStore enumeration without aggregate+individual-volume duplication;
- thumbnail-based in-app browsing and DATE_MODIFIED ordering;
- provider read fallbacks and per-item import failure isolation;
- folder/tree streaming as the safe path for thousands of images because very large multi-select ClipData can fail before the ActivityResult callback reaches the app.

## 2026-08-29 — Build repair and Advanced Visual validation pass

### User turn / device evidence

The user supplied CI logs showing a build failure in `BulkImagePickerActivity.kt:503`: `Returns are not allowed for functions with expression body`. This was caused by the newly introduced `stageUri` expression-body implementation. After correction, Android Build run 182 succeeded and produced debug artifact ID `9715543420`.

The user then requested that remaining previously observed problems be addressed without losing context:
- Gallery/Studio must be a genuine source option, not assumed to have been tested;
- Files moderate multi-select must remain available;
- thousands-of-images selection must have a safe folder/streaming route;
- the in-app image count must not repeat the near-2x duplication;
- Advanced Visual results should not silently reuse Local Reverse Image Search as their ranking engine;
- the displayed Advanced version must not falsely imply that the Advanced feature extractor itself is V4 when its feature extractor is still V2;
- explainability must expose actual numeric reasons rather than opaque label names;
- ranking should prefer coherent cross-signal evidence and penalize isolated color/texture coincidences.

### Engineering findings

1. `AdvancedVisualIntelligenceService` was calling `ReverseImageSearchService.search(...)` and then blending classical reverse-search scores into Advanced ranking. That made the two UI sections materially coupled and could explain nearly identical results.
2. The actual feature extractor declares `AdvancedVisualFingerprintEngine.ENGINE_VERSION = ADVANCED-VISUAL-CLASSICAL-V2`. The correct representation is therefore `Advanced Features V2 + Fusion V4`, not a false extractor V4 label.
3. `AdvancedVisualResultAdapter` already renders thumbnails directly, but its WHY panel primarily exposed opaque reason tokens and also displayed classical fields inherited from the old coupled pipeline.
4. `AdvancedRegionConsistencyVerifier` and `AdvancedStructuralConsensusEngine` already provide independent spatial and multiscale verification signals and can be used without the classical reverse-search ranking path.

### Implementation completed in this turn

- Rewrote `AdvancedVisualIntelligenceService` as an independent Advanced-only search pipeline. It now scans the Advanced fingerprint corpus across the 7 query variants and performs spatial-region plus multiscale structural verification without calling `ReverseImageSearchService` for ranking.
- Added explicit `FUSION_VERSION = ADVANCED-VISUAL-FUSION-V4` and exposed the distinction in the UI as `ADVANCED FEATURES V2 / FUSION V4`.
- Added a Fusion V4 scoring layer using the existing Advanced similarity plus harmonic cross-signal agreement, signal coherence, region consistency, and multiscale structural consensus. Contradictory/isolated evidence is penalized; coherent high-quality agreement receives a small bounded boost.
- Replaced opaque reason tokens with numeric evidence strings such as multi-scale structure, spatial color, LBP texture, gradient, layout, illumination, regional consistency, stable-region coverage, structural consensus, and signal coherence.
- Kept the existing `Evidence` data shape for compatibility, with legacy classical fields zeroed for Advanced-only results rather than pretending classical signals participate in the Advanced ranking.
- Corrected `stageUri` in `BulkImagePickerActivity` to a block-body function and restored the standard read-only Android CI workflow.

### Current CI validation state

- Android Build run 182: SUCCESS on the repaired picker source; debug APK artifact ID `9715543420`.
- Android Build run 183: started for the Advanced Fusion V4 service change.
- Android Build run 184: started for the subsequent Advanced activity/UI labeling change.
- No runtime success is claimed from CI alone; the next device gate must validate actual behavior on the user's Android 12 Samsung device.

### Device validation checklist for the next build

Picker:
- `OPEN SYSTEM FILES / GALLERY` must show Gallery/Studio, Photo Picker, Files, Folder/Large Corpus, and in-app browser.
- Gallery/Studio must actually open an OEM/system media UI where one is available.
- Files moderate multi-select should continue working.
- For the known 6,000–7,000-image folder, use Folder/Large Corpus and verify enumeration/import does not exit; the system multi-select path cannot guarantee safety against huge provider ClipData because the large payload may fail before app code executes.
- In-app browser must report the true image count without aggregate-volume double enumeration.
- In-app Select All must remain logical/disk-backed and stable.
- Mixed image sources must keep valid files and isolate unsupported/corrupt/provider-inaccessible files.

Advanced Visual:
- Search status must identify `ADVANCED-VISUAL-FUSION-V4` while separately identifying feature extraction as V2.
- Advanced search results should differ from Local Reverse Image Search when the feature evidence differs; the Advanced service must not route ranking through `ReverseImageSearchService`.
- Direct result thumbnails must remain visible without opening the image.
- WHY details should provide numeric evidence, confidence, and the actual factors that raised or lowered the score.
- Exact/strong matches should remain grouped near the top; visually weak candidates should be suppressed when their agreement is dominated by isolated color/texture similarity.

### Unresolved / intentionally not claimed

- Gallery OEM behavior and its exact multi-selection limit remain device/provider dependent until re-tested on the target phone.
- The Android framework itself can reject extremely large `ClipData` selections before the application receives them; the robust solution for 6,000–10,000+ images is folder/tree streaming, not an invented in-app workaround.
- The Advanced feature extractor itself is still V2. Fusion V4 is the updated ranking/fusion layer; a future extractor V4 would require an actual feature-schema/algorithm change and reindex.

## 2026-08-29 — Device test: Advanced V2/Fusion V4 versus the user's actual semantic expectation

### User's new test findings

The user deliberately tested with a horizontally mirrored face/image query. The existing Advanced Visual section did not promote the genuinely corresponding face/image strongly enough; the Local Reverse Image Search section was better at surfacing the expected visual counterpart. Advanced therefore has improved score calibration in some cases (true similar images can reach very high/100-like percentages), but it has not yet solved robust identity/part-level matching under reflection, pose, crop, or multi-person scenes.

The user also tested a cluster of seven sequential screenshots from the same video/image. Both existing sections can successfully surface all seven related frames, which confirms that broad image-to-image resemblance works. However, both systems also return roughly fifty candidates, many of which have only weak or incidental resemblance. The next target is therefore precision/selectivity, not merely adding another global image fingerprint.

### Architectural requirement established

The user wants a new, separate intelligence section built around a fundamentally different unit of recognition:
- detect individual faces inside each image rather than treating the whole image as one object;
- generate a durable, high-dimensional identity representation for each detected face;
- retain multiple prototypes for the same person across different views, expressions, lighting, crops, rotations, and occlusions rather than a single template;
- normalize/alignment using facial landmarks and head pose;
- compare whole-face identity plus local facial regions (especially periocular, nose/midface, mouth/jaw/configural geometry) so that a mirrored or differently posed face can still match;
- support one-to-many scenes: each image may contain several faces, and each face should be searched independently;
- use model agreement and quality-aware calibration rather than letting one embedding or one color/texture signal dominate;
- cluster repeated observations to discover likely same-person groups, while keeping identity and mere visual resemblance separate;
- support open-set behavior: low-confidence candidates must remain "unknown" rather than being forced into an identity;
- provide interpretable evidence explaining which identity/part/pose signals support or contradict a match;
- allow a future user-confirmed person profile to accumulate several high-quality reference embeddings over time.

### Current code finding relevant to this requirement

The repository already contains a real face-analysis stack, including `FaceEntity`, `FaceSearchService`, `FaceMatchingEngine`, `FaceNet512ModelManager`, `FaceCropper`, `FacePoseEstimator`, `FaceQualityAnalyzer`, `FaceShapeEncoder`, MediaPipe face analysis, MobileFaceNet, and optional FaceNet-512. `FaceEntity` deliberately represents an occurrence of a face and does not directly claim real-world identity. The current `FaceSearchService`, however, still ranks mostly by a single stored face embedding per model plus shape/pose/quality. It groups results by face ID rather than maintaining a true person-level multi-prototype memory. This is the key opportunity for the next generation architecture.

### Scientific direction recorded

Human face perception research distinguishes holistic/whole-face processing from local feature processing and configural relationships among facial parts. Human recognition is strongly affected by inversion and facial configuration, which supports a design that preserves both whole-face structure and relational geometry rather than relying only on low-level pixel resemblance. Deep metric-learning systems such as FaceNet map faces to an embedding space where distance can be used for verification, recognition, and clustering. ArcFace provides a well-established angular-margin formulation for highly discriminative face embeddings. These ideas are design references, not claims that the current application already implements ArcFace.

### Next-generation proposal: Face Identity & Visual Memory Engine

This must be a separate feature family, not a replacement for Local Reverse Image Search or Advanced Visual Intelligence. The intended pipeline is:

1. Face discovery: detector + landmarks + pose + occlusion + blur/resolution quality.
2. Canonical alignment: geometric normalization from landmarks; generate original/aligned and controlled flip/pose variants without destroying evidence of original orientation.
3. Identity representation: run every genuinely installed identity model (current MobileFaceNet and optional FaceNet-512 first); record model version, embedding dimension, quality, pose and preprocessing contract.
4. Local-part representation: encode stable facial regions and geometric/configural relations; compare them separately from the global embedding.
5. Multi-view prototype memory: for each logical person cluster, retain several diverse high-quality prototype embeddings instead of averaging everything into one vector. Select prototypes by pose/lighting/quality/viewpoint diversity.
6. Robust scoring: compare a query against the best compatible prototype per model, then fuse global identity, local-part agreement, configuration, pose compatibility and quality. Penalize contradictory strong evidence and repeated weak near-neighbors.
7. Person-level aggregation: several images containing the same person become supporting observations of one identity instead of seven independent near-duplicate image results.
8. Open-set gate: separate "same person", "likely same", "visually similar", and "unknown"; never convert a weak nearest neighbor into a false identity.
9. Explainability: show evidence such as `global identity 94%`, `periocular 91%`, `configuration 88%`, `pose compatibility 83%`, `model agreement 96%`, `quality 79%`, and explicit contradictions. Do not claim a real identity from these numbers alone.
10. Benchmark suite: mirrored face, left/right profile, up/down tilt, partial crop, glasses/occlusion, low light, multiple people, repeated video frames, same person across distant timestamps, and visually similar different people.

### Important implementation constraint

Do NOT add a fake ArcFace engine simply by renaming FaceNet or inventing a model file. ArcFace/other new model support is only considered implemented when the actual model is present, its tensor contract is validated, and inference is exercised. Until then, use the currently installed real models and make the architecture model-pluggable.

### Immediate engineering gate

Before changing scoring or UI, inspect the existing face detector/alignment/embedding/database/UI code end-to-end and define the smallest compatible schema extension for multi-prototype person memory. Build the new family independently, then compile and benchmark it before claiming runtime success.

### Sources consulted for design

- Human face holistic/configural processing reviews: NIH/PMC review literature.
- FaceNet: Schroff, Kalenichenko, Philbin, embedding-space recognition and clustering.
- ArcFace: Deng et al., CVPR 2019, additive angular margin for discriminative face embeddings.

No runtime success is claimed yet for this new architecture. The user's device results are the baseline that the next implementation must beat.
