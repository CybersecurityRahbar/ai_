# Face Identity & Visual Memory — Device Benchmark / Acceptance Gate

Date: 2026-08-29

## Baseline observed by user

- Mirrored-face query: Local Reverse Image Search surfaced the expected visual counterpart better than Advanced Visual Intelligence.
- Advanced Visual improved score calibration and can give genuinely related images very high scores, but its ordering remains close to whole-image similarity.
- A set of seven sequential screenshots from one video can be retrieved by both existing sections, but both also return roughly fifty candidates, many of which have weak relevance.
- The user wants a new identity-centric engine that treats each face as an independent observation and remembers people across different views rather than treating each whole image as the identity.

## Required benchmark cases

### A. Exact same face
Expected: true matches near the top; no unrelated face should outrank a high-quality exact match.

### B. Horizontal mirror
Expected: original and mirrored query should resolve to the same person/face when the facial evidence is otherwise equivalent. Winning orientation must be recorded as evidence, not hidden.

### C. Left/right profile
Expected: the person can still match when the stored prototype and query have different head directions, provided enough stable face evidence exists.

### D. Up/down tilt
Expected: pose-conditioned prototypes should prevent a single frontal template from dominating all viewpoints.

### E. Lighting variation
Expected: identity embedding and local/configural evidence should remain meaningful under brightness/color changes; whole-image color coincidence must not dominate identity.

### F. Partial occlusion
Expected: reduced confidence when critical regions are blocked, while remaining visible regions can still support a likely match if evidence is strong enough.

### G. Crop / zoom
Expected: face-local identity matching must remain possible when the same person's face occupies materially different fractions of the image.

### H. Multi-person scene
Expected: each face is searched independently; one image can yield multiple person candidates. A background person's face must not contaminate the main person's identity score.

### I. Repeated video frames
Expected: seven near-identical frames containing the same person should consolidate into one person-level result with supporting observations rather than seven independent identity claims. Whole-image reverse search may still display the seven frames separately in its own section.

### J. Similar-looking different people
Expected: visually similar faces must remain separate when identity evidence does not clear the open-set threshold and runner-up margin.

### K. No-match image
Expected: `UNKNOWN` is a valid outcome. The engine must not force a nearest known person.

## Required metrics

- Top-1 same-person retrieval.
- Top-5 same-person retrieval.
- False accept rate on unrelated but similar-looking faces.
- Open-set rejection rate.
- Margin between the best candidate and runner-up.
- Performance broken down by pose and occlusion bucket.
- Number of independent supporting prototypes.
- Mean and tail inference latency on Android 12.

## Required explanation contract

Every displayed identity result must expose:

- global identity similarity per model;
- local facial-part support;
- landmark/configuration support;
- pose compatibility;
- quality contribution;
- model agreement;
- prototype support count;
- runner-up margin;
- contradictions or missing evidence;
- final decision band.

Avoid a single opaque 'similarity' percentage without the underlying evidence.

## Anti-regression rules

- Local Reverse Image Search remains a whole-image visual retrieval system.
- Advanced Visual Intelligence remains a whole-image advanced structural retrieval system.
- Face Identity & Visual Memory remains face/person-centric.
- No section may silently call another section's ranking engine.
- New models must be validated before activation.
- New database data must be versioned and migrated safely.
- No runtime success is claimed from compilation alone.
