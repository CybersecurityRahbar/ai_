# Advanced Visual Intelligence — Final Device Benchmark Plan

## Purpose

This is the single controlled test plan to be executed on the user's Android 12 / SM-G981U after Advanced Visual Intelligence development is frozen.

No score or confidence threshold is considered statistically calibrated before these tests are measured on-device.

## Corpus setup

Use the shared corpus once. Do not re-import the same image separately for Reverse Image and Advanced.

Baseline sizes:

- 999 images (intentional baseline).
- ~1,000 images.
- 5,000 images.
- 6,000 images when available.

Record valid, skipped, and failed counts separately.

## Search accuracy cases

For each query, record Top-1, Top-5, Top-10 and whether the expected source appears in the returned set.

1. Exact duplicate — byte-identical file.
2. JPEG recompression at several quality levels.
3. Resize up and down.
4. Metadata-only file change.
5. Screenshot of the same image.
6. Screenshot with UI/header/footer around the image.
7. Small crop.
8. Medium crop.
9. Large crop.
10. 90° / 180° / 270° rotation.
11. Brightness change.
12. Contrast change.
13. Saturation/color-temperature change.
14. Mild blur/noise.
15. Burst/near-duplicate sequence.
16. Same subject/object with framing changes.
17. Perspective/viewpoint change.
18. Image embedded inside another screenshot.
19. Visually unrelated image with similar dominant colors.
20. Visually unrelated image with similar texture.

## Required evidence capture

For returned candidates record:

- final similarity;
- confidence indicator;
- Haar;
- pHash;
- dHash;
- classical color/edge/local evidence;
- RANSAC inliers;
- Advanced total;
- structure;
- spatial color;
- texture/spatial texture;
- gradient/magnitude;
- layout;
- illumination robustness;
- entropy/aspect;
- regional consistency;
- stable-region coverage;
- spatial disagreement;
- structural consensus;
- coarse/fine structural scores;
- winning query variant;
- reason codes.

## False-positive analysis

For any irrelevant Top-10 result, inspect which signals raised it. Classify as:

- color-dominance;
- texture-dominance;
- weak structural agreement;
- spatial conflict;
- insufficient geometric evidence;
- expected false positive;
- implementation defect.

Do not change weights solely from one example. Collect repeated cases first.

## Performance measurements

### Import

Measure:

- picker time;
- queue creation time;
- URI copy time;
- bounds validation time;
- corpus insertion time;
- failures/skips.

### Shared visual indexing

Measure:

- decode time;
- Haar time;
- Classical V4 time;
- Advanced V2 time;
- Room batch write time;
- total batch time;
- images/sec;
- peak memory if observable.

Do not reduce Reverse Image 64/16 or remove any existing algorithm to improve throughput.

### Search

Measure:

- query decoding;
- query-variant generation;
- Advanced full-corpus retrieval;
- existing Reverse Image retrieval;
- fusion;
- final result rendering.

## Lifecycle tests

During import and shared indexing:

- rotate Activity;
- background the application;
- turn screen off;
- leave the application backgrounded for a long period;
- return after process recreation when Android permits;
- verify durable checkpoint and final counts.

Explicit cancellation must stop the operation cleanly; normal Activity destruction must not cancel durable indexing.

## Acceptance gates

A release candidate requires:

- CI green for the exact commit under test;
- no duplicate launcher/application;
- no known transient URI result-display crash;
- no repeated-search crash;
- no large-Intent URI list;
- no batch-wide failure caused by one bad URI;
- background/rotation behavior verified;
- Advanced evidence values internally consistent;
- no accuracy regression in the existing Reverse Image pipeline;
- measured performance reported rather than assumed.

## Accuracy interpretation

`finalPercent` is a similarity score produced by the deterministic fusion stack.

`confidencePercent` is an evidence-strength heuristic and must not be described as the probability that two images are the same.

Threshold changes are permitted only after a repeated benchmark set demonstrates a clear precision/recall trade-off.
