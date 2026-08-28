# Advanced Visual Intelligence — Analysis Addendum (2026-08-28)

This addendum records two important conclusions found during review of the real device test and current source code.

## 1. Advanced is genuinely V2 today

Current source declares:

`AdvancedVisualFingerprintEngine.ENGINE_VERSION = ADVANCED-VISUAL-CLASSICAL-V2`

Therefore the UI message `Preparing 7 Advanced V2 query variants` is truthful. Earlier references in the development conversation to “Advanced V4” were incorrect terminology; `V4` belongs to the existing Classical Reverse Image engine.

No future commit may relabel the current V2 implementation as V4 without a real versioned implementation and storage contract.

## 2. Why Advanced often resembles Reverse Image

The Advanced service performs its own full-corpus V2 comparisons, but it also invokes the existing Reverse Image search with 64 candidates and then unions candidate IDs before fusion. This intentionally preserves recall but also means a strong classical result can remain dominant.

In addition, the Advanced-to-classical fusion uses heuristic weighted scores and penalties rather than calibrated score distributions. The current classical call inside Advanced uses `minimumSimilarity = 0.0f`, which maximizes recall but allows weak classical candidates into the fusion pool.

This should be improved by keeping broad recall while changing the later ranking layers:

- preserve the full 64 classical candidates;
- normalize each evidence family to a common empirical score space;
- collapse correlated evidence families into evidence groups;
- require independent support for high-confidence promotion;
- treat strong geometric support as a high-value evidence family;
- penalize generic color/texture matches that lack structural/local support;
- classify the match type;
- use ranking margin and candidate-cluster coherence;
- add an explicit reject/weak-resemblance band.

The solution is not to lower candidate count or weaken the existing Reverse pipeline.

## 3. Why current explanation is weak

Current `WHY THIS RESULT` displays raw evidence percentages and reason codes. The final score is produced by multiple weighted terms and heuristic penalties, but the UI does not show the actual decision ledger that explains how those values changed the final rank.

A useful explanation must be reconstructible:

`base evidence → normalized evidence → independent groups → penalties → bonuses → final score → acceptance band → rank/margin`

For each candidate, users should be able to see:

- match type;
- independent evidence groups that passed;
- strongest positive evidence;
- strongest negative evidence;
- exact penalty/bonus events;
- ranking margin;
- why the candidate was admitted to the result set;
- why it did not rank higher;
- why it was not rejected.

## 4. Face/person matching gap

Advanced V2 is a whole-image classical visual similarity system. It does not contain a dedicated face-identity descriptor or face-specific matching stage.

Therefore Advanced itself cannot currently be claimed to reliably:

- identify the same person across substantially different poses;
- ignore a changed background while matching the person;
- cluster many photos of the same person;
- remain identity-stable under large viewpoint/appearance changes.

The project has separate face/embedding infrastructure, but that is not currently fused into Advanced V2.

A future optional `Face Evidence` family may be introduced after the core visual ranking is corrected. It should be independently stored, independently scored, and contribute a separate explainable evidence family to fusion rather than silently replacing the classical image match.

## 5. Device-test interpretation

Observed:

- `1083` indexed candidates were available during search.
- Reverse Image repeatedly searched the full candidate set and used `64` results, with `16` SIFT verification candidates as designed.
- Search duration in the shown runs ranged approximately from `9.2s` to `21.0s`.
- Some runs had few local/geometric verifications despite many final results. This is expected to create opportunities for generic global evidence to influence rank and should be addressed through ranking gates, not by shrinking recall stages.
- Shared indexing recorded batch size `16`, parallelism `4`, and per-batch persistence. One run reported `523` newly indexed and `560` skipped, confirming incremental behavior.
- `extractionMs` is larger than wall-clock `durationMs` in a parallel run because it is an aggregate of worker time; it must not be interpreted as elapsed device time.

## 6. Mandatory next engineering gate

Before the next broad algorithm expansion, fix selection correctness and then redesign ranking/fusion. Do not add many new algorithms until the current evidence is calibrated enough to tell whether each addition improves retrieval or merely adds another correlated score.
