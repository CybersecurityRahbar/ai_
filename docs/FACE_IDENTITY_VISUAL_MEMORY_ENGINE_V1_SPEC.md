# Face Identity & Visual Memory Engine V1 — Architecture Specification

## Purpose

This feature is intentionally separate from:

- Local Reverse Image Search
- Advanced Visual Intelligence
- general image similarity

Its unit of recognition is the **face occurrence and person-level visual identity**, not the whole image.

The current application already has real face analysis infrastructure: FaceEntity, FaceAnalysisService, FaceSearchService, FaceMatchingEngine, FaceNet512ModelManager, MobileFaceNet/TFLite embedding support, FaceCropper, FacePoseEstimator, FaceQualityAnalyzer, FaceShapeEncoder, and MediaPipe analysis. The next generation should compose these real capabilities into a person-centric memory instead of replacing them with another global-image fingerprint.

## Why this architecture is different

The existing Advanced Visual pipeline compares image-wide representations. It can improve score calibration but still treats the complete image as the main unit. Device testing showed that this is insufficient for:

- horizontally mirrored faces;
- faces appearing in different image layouts;
- the same person under different pose/illumination/crop;
- multi-person images;
- precision when many globally similar frames exist.

Human face-perception research supports combining whole-face, feature-level, and configural information rather than reducing a face to isolated pixels. Deep metric-learning systems provide an appropriate engineering abstraction: map normalized face observations to an embedding space in which distance supports verification and clustering.

## Pipeline

### Stage A — Face discovery

For every image:

1. detect every face;
2. capture normalized bounding box;
3. collect landmarks when available;
4. estimate head pose;
5. estimate occlusion;
6. estimate blur/resolution/illumination quality;
7. reject only unusable observations, never whole images because one face is bad.

### Stage B — Canonical face formation

Create a deterministic representation from landmarks:

- original face crop;
- aligned upright crop;
- controlled horizontal-flip query variant for identity verification;
- optional pose-conditioned crop variants;
- preserved original metadata for traceability.

The system must not overwrite the original crop. Alignment is a comparison view, not a replacement for evidence.

### Stage C — Identity embeddings

Run all genuinely installed identity models whose contracts have been validated.

Current real models:

- MobileFaceNet, existing application model;
- optional user-imported FaceNet-512, tensor-validated by FaceNet512ModelManager.

Each stored observation must retain:

- model name/version;
- preprocessing contract;
- embedding dimension;
- normalized embedding;
- source face ID;
- quality score;
- pose bucket;
- timestamp.

Future ArcFace support is model-pluggable but must not be faked by renaming an existing model. A new model becomes active only after its actual TFLite asset is present, tensor shape/type validation succeeds, and a health-check inference succeeds.

### Stage D — Local facial-part evidence

Extract and compare robust local/configural evidence for stable regions, especially:

- periocular/eye region;
- mid-face/nose region;
- mouth/lower-face region;
- jaw/face outline when visible;
- normalized landmark geometry and inter-feature relationships.

The result is a vector of supporting signals, not a second identity claim.

### Stage E — Multi-prototype person memory

Do not store exactly one vector per person.

Each logical person cluster maintains a **prototype bank** containing several diverse, high-quality observations. Diversity dimensions include:

- frontal / left / right / up / down pose;
- illumination buckets;
- glasses/occlusion state;
- image quality;
- expression/viewpoint;
- time separated observations.

When a new high-quality observation arrives:

1. test it against existing prototypes;
2. if it is close to an existing prototype, update support statistics rather than endlessly duplicating it;
3. if it contributes a new viewpoint, preserve it as a distinct prototype;
4. cap the prototype count with deterministic diversity selection.

Never replace the entire person memory with a running mean. Averaging can blur useful viewpoint-specific structure.

### Stage F — Robust person-level scoring

For a query face:

1. compute model-wise cosine similarities against compatible prototypes;
2. choose the best compatible prototype per model while retaining the second-best support for stability checks;
3. compute local-part similarity;
4. compute configuration similarity;
5. compute pose compatibility;
6. incorporate query/stored quality;
7. measure cross-model agreement;
8. penalize contradictions;
9. aggregate evidence at the **person** level, not image level.

Conceptual score:

`identity = robust_global + local_parts + configuration + model_agreement + pose_compatibility + quality`

The exact coefficients must be calibrated empirically on a validation set; they must not be chosen merely to make percentages look better.

### Stage G — Open-set decision

The engine must explicitly distinguish:

- `SAME_PERSON_HIGH_CONFIDENCE`
- `SAME_PERSON_LIKELY`
- `VISUALLY_SIMILAR_NOT_CONFIRMED`
- `UNKNOWN`

A nearest neighbor is not automatically an identity.

The system should use a score threshold plus a **margin-to-runner-up** criterion. A candidate that scores 0.86 while the next unrelated person scores 0.85 is not equivalent to a candidate at 0.86 with the runner-up at 0.61.

### Stage H — Multi-person image reasoning

For a query image containing several faces:

- detect all query faces;
- search each independently;
- show the matched face crop and its location in the source image;
- aggregate results only after face-level scoring.

An image can therefore produce several independent person matches.

### Stage I — Explainability

The engine must explain **why a person/face match was promoted**.

Example evidence model:

- Global identity: 94%
- Periocular region: 91%
- Mid-face region: 88%
- Configuration: 90%
- Pose compatibility: 83%
- Model agreement: 96%
- Query quality: 79%
- Prototype support: 6 independent observations
- Runner-up margin: +18 percentage points

And explicit contradiction messages:

- low-quality query reduced confidence;
- mouth region is occluded;
- one model disagrees with the other;
- pose is outside the strongest stored viewpoint;
- global image resemblance is high but face identity evidence is weak.

The UI should never expose unexplained raw internal tokens as the primary explanation.

## Mirror and pose robustness

Horizontal reflection must be treated as a verification condition, not as evidence that the person is different. A query can be scored in both original and controlled horizontal-flip form; the system should retain whichever produces the stronger **face-identity-consistent** evidence while recording the winning variant.

Pose robustness should come from alignment + multi-prototype viewpoint memory. It must not rely on inventing a single generic correction for every head pose.

## Person clustering and resemblance

Clustering has two separate purposes:

1. **same-person clustering:** repeated face observations likely belong to one identity cluster;
2. **visual resemblance retrieval:** different people may look similar.

The UI must never label visual resemblance as identity. A resemblance result can be shown as `LOOKS SIMILAR`, while identity results require the stricter open-set gate.

## Relationship to existing search systems

### Local Reverse Image Search

Remains optimized for whole-image duplicate/near-duplicate visual retrieval using its existing Haar / pHash / dHash / color / shape / AKAZE / SIFT / RANSAC family.

### Advanced Visual Intelligence

Remains optimized for whole-image multi-scale structural evidence, region consistency, structural consensus and Fusion V4.

### Face Identity & Visual Memory

Is a third independent family optimized for face/person identity and person-level memory.

The three systems may optionally cross-reference results, but none should silently replace another as its ranking engine.

## Required benchmark set

Before declaring the new family complete, test at least:

1. same face, original orientation;
2. horizontally mirrored face;
3. 90-degree image rotation where the face remains recoverable;
4. frontal vs left profile;
5. frontal vs right profile;
6. up/down head tilt;
7. low light;
8. glasses/partial occlusion;
9. crop with only part of the face visible;
10. multiple people in one image;
11. seven sequential frames from one video;
12. same person across different days/scenes;
13. visually similar but different people;
14. no-match image.

Primary metrics:

- true-match recall;
- false-match rate;
- top-1 identification accuracy;
- top-5 retrieval recall;
- open-set false accept rate;
- separation margin between true identity and runner-up;
- latency and memory on Android 12.

## Non-negotiable engineering rules

- Do not claim a feature is implemented when it is only represented by UI text.
- Do not claim ArcFace unless a validated ArcFace model actually runs.
- Do not equate image similarity with person identity.
- Do not force every query into a known person cluster.
- Do not remove or merge the existing Reverse/Advanced sections.
- Do not store an unbounded number of face prototypes.
- Do not retain full-resolution face crops unnecessarily when embeddings and derived metadata are sufficient for the intended local search behavior.
- Keep all processing local unless a future feature explicitly changes the privacy model.

## Source references

- FaceNet: Schroff, Kalenichenko, Philbin — embedding-space face verification, recognition and clustering.
- ArcFace: Deng et al., CVPR 2019 — additive angular margin for discriminative face embeddings.
- Human face perception reviews: holistic, featural and configural contributions to face recognition.

These sources are architecture references. They do not imply that the current application already contains every technique described above.
