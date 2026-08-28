# Advanced Visual Intelligence — Device Test Findings

Date: 2026-08-28
Device: Android 12 / SM-G981U (Android API 31)

## Purpose

This document records the first substantial on-device test of the shared visual corpus, the Advanced Visual Intelligence section, and the image-selection pipeline. It is a factual checkpoint for the next engineering phase. The findings below must be treated as requirements and regression cases, not as optional observations.

## 1. What was tested

The user installed the current green build and tested:

- `ADVANCED VISUAL INTELLIGENCE`
- `LOCAL REVERSE IMAGE SEARCH`
- `ADD IMAGES TO SHARED VISUAL CORPUS`
- `OPEN SYSTEM FILES / GALLERY`
- bulk selection from a filesystem folder
- large selection (`500+`, and `6,000+` intended/attempted)
- shared indexing
- Reverse Image search and repeated comparison of the two search sections
- Advanced query preparation and result ranking
- device diagnostics logs

Observed corpus size during search: `1083` indexed items.

## 2. Critical image-selection findings

### 2.1 Native system picker does not reliably expose Gallery

The current `OPEN SYSTEM FILES / GALLERY` implementation uses `ActivityResultContracts.OpenMultipleDocuments()` with MIME type `image/*`.

On the test device this opened document/file providers, but did not present the expected Samsung Gallery experience.

Important distinction:

- `OpenMultipleDocuments()` is a DocumentsUI/document-provider route.
- It does not guarantee that the OEM Gallery application appears as a selectable provider.
- Therefore the button label `OPEN SYSTEM FILES / GALLERY` over-promises the actual UI on some devices.

Required next change:

Provide a distinct gallery-oriented route in addition to the existing document route, with robust fallback behavior. Candidate implementations to evaluate on this device are:

1. Android photo picker / `PickMultipleVisualMedia` on supported Android versions.
2. An `ACTION_PICK` / media-oriented fallback with `EXTRA_ALLOW_MULTIPLE` where an OEM gallery handles it.
3. Keep `OpenMultipleDocuments()` as the explicit Files/folder route.

The app must not rely on the presence of Samsung Gallery as a universal Activity. The implementation must query/resolve the intent and fall back safely.

### 2.2 Selecting all 6,000+ items can terminate the app

The current local bulk picker has a structural memory problem.

`selectAllMedia()` calls `queryAllUris()` and receives a complete `List<String>` containing every URI. It then calls `selected.addAll(allUris)`. This defeats the intended memory-bounded architecture for very large selections.

This is a confirmed design defect, not merely a UI preference.

Required architecture:

- Represent "all media selected" as a logical selection mode, not as 100,000+ URI strings in memory.
- Keep only visible-page checkbox state in memory.
- On `ADD SELECTED`, stream matching MediaStore IDs/URIs directly into the existing app-private `.uris` queue file.
- The queue file remains the contract with `ImageCorpusImportWorker`.
- Use selection-exception structures only when necessary (for example, deselected visible rows), and keep them bounded.
- Never materialize the entire corpus as a Kotlin `List<String>` merely to express Select All.

### 2.3 Local media count is approximately doubled

The current code initializes `volumes` with:

- `MediaStore.VOLUME_EXTERNAL`
- plus every `MediaStore.getExternalVolumeNames()` volume except `VOLUME_EXTERNAL`.

`VOLUME_EXTERNAL` is an aggregate over external storage volumes. Adding individual external volumes afterward can cause the same images to be enumerated twice.

The observed UI count was:

`215,992`

while the phone's file/media inspection showed approximately:

`107,994`

This near-exact 2x relationship strongly matches the aggregate-volume duplication in the current `prepareVolumes()`/`queryAllUris()` logic.

Required next change:

- Choose either aggregate external volume OR individual external volumes, never both in the same enumeration.
- Deduplicate using stable MediaStore identity if multiple volumes must ever be traversed.
- Add an automated regression test for expected unique-count semantics.

### 2.4 Folder import has improved but mixed/invalid media still fails per item

The current behavior is materially improved compared with the previous all-or-nothing failure. Invalid/unreadable items are isolated and the rest can continue.

This behavior must be preserved.

Future improvement:

- distinguish unsupported format, missing permission, revoked URI, corrupt image, and decode failure in diagnostics;
- keep the failed item out of the successful corpus count;
- never abort the whole import for one bad document.

## 3. Shared indexing findings

### 3.1 Performance improved substantially

The user reports roughly a 4x improvement in indexing speed versus the previous implementation. This is a positive observed result, but it has not yet been benchmarked under a controlled protocol and therefore must not be treated as a statistically validated 4x gain.

The logs show:

- shared decode: `true`
- batch size: `16`
- parallelism: `4`
- batch persistence events
- independent Haar/Classical/Advanced outputs from the shared indexing pipeline

### 3.2 Current skip behavior is healthy but needs clearer reporting

Example:

`items=1083, indexed=0, skipped=1083, failed=0`

This means the corpus was already indexed and the operation avoided recomputation.

The UI should distinguish:

- already indexed / up-to-date;
- newly indexed;
- changed source requiring reindex;
- failed;
- skipped because of unsupported/invalid media.

### 3.3 Current index is not Advanced V4

This is a critical factual correction.

`AdvancedVisualFingerprintEngine.ENGINE_VERSION` is currently:

`ADVANCED-VISUAL-CLASSICAL-V2`

and the search UI/logging reports preparation of `Advanced V2` variants.

The `V4` label in the Reverse Image logs belongs to the Classical engine:

`CLASSICAL-PHASH-DHASH-HSV256-SOBEL-AKAZE-V4`

Therefore the project currently has:

- Reverse Image Classical V4;
- Advanced Visual Classical V2.

There is no Advanced V4 engine active in the current codebase.

Do not describe Advanced as V4 until a real V4 implementation exists, is indexed, queried, persisted, and tested.

## 4. Advanced vs Reverse Image result-quality findings

### 4.1 User observes little practical difference between the two sections

Across tests, Advanced and Reverse Image often produced essentially the same visible results.

This is not sufficient evidence that Advanced is useless; it indicates that the current fusion/retrieval design does not yet create a large enough ranking separation for the selected test set.

The current Advanced service explicitly calls the existing `ReverseImageSearchService`, requests 64 base results, computes Advanced full-corpus evidence separately, unions the candidate IDs, and then fuses scores.

The current fusion is approximately:

- base Classical result contribution when available;
- Advanced V2 score;
- regional consistency;
- structural consensus;
- a set of heuristic penalties/bonuses.

This means Advanced currently extends the classical path rather than operating as an entirely independent semantic universe. That is intentional, but it can make both screens converge on similar candidates when the existing classical engine is already strong.

### 4.2 Weak/unrelated images sometimes receive medium/high rank

The user observed cases where several true variants of the queried image rank highly, but one or two related images can fall lower than visually weak/unrelated candidates.

This is a ranking-calibration problem, not only a feature-extraction problem.

Likely contributors that must be investigated with measurements:

1. Global similarity is too permissive for generic color/texture agreement.
2. Candidate union allows strong classical candidates and strong Advanced candidates to compete without a calibrated common scale.
3. `minimumSimilarity` is currently permissive on the classical call from Advanced (`0.0f`), so many low-strength classical candidates can enter the union before final fusion.
4. Current penalties are heuristic and do not establish a statistically calibrated acceptance threshold.
5. The system needs explicit match classes, not only one continuous percentage.
6. A visually similar-but-generic image can accumulate several correlated signals without actually being the same visual instance.
7. Spatial/geometric evidence is currently strongest for local transformations and should have a clearer authority over generic global similarity when available.

Required future ranking architecture:

`retrieve broadly → normalize signal scales → detect correlated evidence → apply independence-aware gates → classify match type → rank → reject weak/generic candidates`

Do not solve this by simply lowering/raising one global threshold.

## 5. Advanced explainability findings

### 5.1 Current `WHY THIS RESULT` is not sufficiently useful

The current panel reports component percentages and reason codes, but the user correctly reports that this does not answer the practical question:

**Why did this image receive this exact ranking?**

The current UI is an evidence dump rather than a causal ranking explanation.

### 5.2 Required next-generation explanation

For every returned result, the system should expose a structured decision record such as:

- `MATCH TYPE`: exact / near-duplicate / transformed / partial / same-layout / weak visual resemblance / rejected;
- `FINAL SCORE`;
- `ACCEPTANCE BAND`;
- `RANKING MARGIN` versus the next best result;
- `INDEPENDENT EVIDENCE COUNT`;
- `GEOMETRIC SUPPORT` when available;
- `REGIONAL SUPPORT`;
- `STRUCTURAL SUPPORT`;
- `COLOR SUPPORT`;
- `TEXTURE SUPPORT`;
- `CONTRADICTIONS`;
- `PENALTIES APPLIED`;
- `WHY INCLUDED`;
- `WHY NOT HIGHER`;
- `WHY NOT REJECTED`;
- exact weighted contribution of each independent evidence family to the final score.

The score explanation should be reconstructible from stored evidence. It must not fabricate natural-language reasons after the fact.

`confidencePercent` remains a heuristic evidence-strength indicator, not a probability.

## 6. Query-variant findings

Current Advanced variants are:

- original;
- 90°;
- 180°;
- 270°;
- center crop 92%;
- center crop 82%;
- center crop 72%.

The current code reports `Advanced V2` because the engine version is V2.

Future work should expand the transformation bank only where the transformation corresponds to a realistic retrieval failure mode. Do not add variants just to increase the count.

Potential future transformations to evaluate experimentally:

- horizontal/vertical mirroring;
- mild perspective warp;
- controlled scale changes;
- illumination-normalized variants;
- border/letterbox removal;
- screenshot-window/border suppression.

Each must have recall/precision and runtime measurements before adoption.

## 7. Accuracy strategy for the next phase

The next improvement must NOT replace the existing Reverse Image engine and must NOT weaken it.

The existing engine remains locked at:

`full corpus → 64 shortlist → AKAZE/RANSAC over all 64 → SIFT/RANSAC over all 16 → final ranking`

Advanced should become a stronger second evidence family on the shared corpus.

The target architecture is:

`Shared index`

→ Haar fingerprint
→ Classical V4 fingerprints
→ Advanced V4+ fingerprints when implemented
→ existing local geometric descriptors
→ regional/structural evidence

Search:

`query preprocessing`
→ broad retrieval from each family
→ candidate union without losing recall
→ score normalization
→ correlated-signal handling
→ geometric authority when supported
→ regional/structural gates
→ false-positive suppression
→ match-type classification
→ explainable final ranking

No accuracy-bearing candidate stage should be reduced merely to improve latency.

## 8. What must be measured next

Before claiming Advanced is better than Reverse Image, create a controlled on-device benchmark containing:

- exact duplicate;
- resized copy;
- JPEG recompression;
- screenshot of image;
- crop 90/80/70/60%;
- rotation 90/180/270;
- mild perspective change;
- brightness/contrast changes;
- color changes;
- same scene from a different shot;
- same person with different pose/background where relevant;
- visually similar but unrelated distractors;
- texture/color distractors;
- unrelated random images.

Record at least:

- Recall@1;
- Recall@5;
- Recall@10;
- false-positive rate at selected acceptance bands;
- ranking position of the true match;
- query latency;
- indexing throughput;
- memory peak;
- failure count;
- evidence/explanation correctness.

The same corpus and queries must be run through:

1. Reverse Image only;
2. Advanced only;
3. fused mode.

Do not declare one engine superior based on a few visual impressions.

## 9. Immediate implementation priorities

Priority 0 — Prevent data-selection failures:

1. Fix aggregate-volume duplication.
2. Replace `Select All` URI materialization with logical selection + streaming queue generation.
3. Add explicit Gallery/Photo Picker route while retaining Files/document picker.
4. Preserve per-item import failure isolation.

Priority 1 — Correct naming/engine identity:

5. Keep Advanced labeled V2 until a real V4 engine exists.
6. If Advanced V4 is desired, implement it as a real versioned engine with new fingerprint fields, storage, migration, indexing, query, and tests.

Priority 2 — Improve ranking quality without weakening Reverse:

7. Normalize evidence families before fusion.
8. Add match-type classification.
9. Add independence-aware consensus and correlated-signal suppression.
10. Give local geometric evidence clear authority when strong.
11. Add negative evidence and reject bands for generic similarity.
12. Add ranking-margin and cluster coherence checks.

Priority 3 — Replace the current explainability dump:

13. Store a reconstructible decision ledger for every candidate.
14. Show why included, why this rank, and why not rejected.
15. Show independent evidence contribution rather than only raw percentages.

## 10. Permanent regression requirements from this test

- The image count must never double because aggregate and per-volume MediaStore collections are both enumerated.
- Selecting all media must not materialize the entire corpus into an in-memory URI list.
- The app must provide a genuine gallery-oriented path as well as a document/files path.
- One bad document must never abort a large import.
- Advanced must not be labeled V4 while its engine version is V2.
- Advanced and Reverse must be benchmarked separately and together.
- A percentage alone is not a sufficient explanation of why a result ranked where it did.
- No recall reduction is allowed in the existing Reverse Image 64/16 pipeline.
