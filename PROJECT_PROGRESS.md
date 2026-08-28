# Personal Memory AI — Project Progress Log

## 2026-08-28 — First substantial on-device Advanced/Shared Corpus findings

A real Android 12 / SM-G981U installation exposed several important issues that CI cannot prove. Full findings are recorded in `docs/ADVANCED_DEVICE_TEST_FINDINGS_2026-08-28.md` and must be treated as regression requirements.

### Selection/import findings

- `OPEN SYSTEM FILES / GALLERY` currently uses `OpenMultipleDocuments(image/*)`. On the test device it exposed filesystem/document providers but did **not** present the expected Samsung Gallery experience. This route must not be described as guaranteed Gallery. Add a genuine gallery/photo-picker route while retaining the Files/document route.
- Selecting roughly 500+ images through the system document route worked.
- Selecting all 6,000+ images from the current in-app MediaStore picker terminates the app. Root cause is confirmed in code: `selectAllMedia()` materializes every URI into a `List<String>` and then copies the full list into `selected`.
- Required fix: logical `select-all` state plus streaming MediaStore enumeration directly into the existing private `.uris` queue. Do not hold the whole corpus URI set in memory.
- Mixed/invalid folder imports are improved: bad images can fail individually while valid selected images continue. Preserve this isolation and improve diagnostics by failure type.
- The in-app media count `215,992` versus approximately `107,994` observed in device storage is explained by the current `prepareVolumes()` design adding the aggregate `MediaStore.VOLUME_EXTERNAL` and individual external volume names. Those collections can enumerate the same media twice. Required fix: use aggregate or per-volume enumeration, never both together; add unique-identity regression coverage.

### Shared indexing findings

- User observed roughly 4x faster indexing than the older implementation. This is an observed device result only; controlled throughput benchmarking is still required before claiming a validated 4x improvement.
- Shared indexing is demonstrably batching and skipping up-to-date items. Example log: `items=1083`, `indexed=0`, `skipped=1083`, `failed=0`.
- Shared index logs show `sharedDecode=true`, `batchSize=16`, `parallelism=4` and successful batch commits.

### Critical version identity correction

Current Advanced engine is **not V4**.

`AdvancedVisualFingerprintEngine.ENGINE_VERSION` is:

`ADVANCED-VISUAL-CLASSICAL-V2`

and Advanced search correctly reports seven `Advanced V2` query variants.

The `CLASSICAL-...-V4` label in Reverse Image logs refers to the existing Classical engine, not Advanced.

Current state is therefore:

- Reverse Image Classical: V4.
- Advanced Visual Intelligence: V2.

Do not describe Advanced as V4 until a real V4 engine is implemented, persisted, indexed, queried, and tested.

### Advanced vs Reverse result-quality findings

The user reports that Advanced and Local Reverse Image often produce essentially the same visible results. This is a measured observation from the device, not proof that the engines are identical.

The current Advanced service calls `ReverseImageSearchService` for 64 classical candidates, computes Advanced V2 over the full Advanced corpus, unions candidate IDs, and then fuses the evidence. Therefore both screens can converge when the classical engine is already strong and Advanced does not create enough ranking separation.

The current ranking issue is more specific than “add more algorithms”:

- generic color/texture matches can accumulate correlated evidence;
- the Advanced-to-classical score scales are heuristic and not calibrated together;
- Advanced calls the classical search with `minimumSimilarity=0.0f`, so all 64 classical candidates can enter fusion;
- regional and structural penalties exist but are heuristic rather than calibrated acceptance gates;
- there is no explicit match-type classifier or strong rejection band for generic resemblance;
- strong local geometric support is not yet given enough authority when available;
- ranking margin and cluster/coherence information are not yet used to distinguish a real group of variants from isolated false positives.

Required next architecture:

`broad retrieval → score normalization → correlated-signal control → independent evidence gates → geometric authority → match-type classification → false-positive suppression → final ranking`

Do not lower Reverse recall stages to solve this.

### Explainability findings

The current `WHY THIS RESULT` is an evidence dump and does not adequately answer:

- why this image was included;
- why it received its exact rank;
- why it was not rejected;
- which evidence families actually contributed to the final score;
- what negative evidence reduced its rank.

Next-generation explanation should contain a reconstructible decision record:

- match type;
- final score and acceptance band;
- ranking margin;
- independent evidence count;
- geometric support;
- regional support;
- structural support;
- color/texture support;
- contradictions and penalties;
- weighted independent contributions;
- `WHY INCLUDED`;
- `WHY THIS RANK`;
- `WHY NOT REJECTED`.

`confidencePercent` remains a heuristic evidence-strength indicator and is not a probability.

### Advanced query-version findings

Current V2 variants:

- original;
- 90°;
- 180°;
- 270°;
- center crops 92/82/72%.

Future variants must be added only for realistic failure modes and benchmarked for recall/precision/runtime.

### Current phase gate

The project is now in **post-integration device validation / diagnosis**, not in a state where every runtime behavior can be considered finished.

Priority order for the next implementation phase:

1. Fix MediaStore aggregate/per-volume double counting.
2. Replace Select All URI materialization with logical selection + streaming queue generation.
3. Add a real gallery/photo-picker path while keeping Files/document picker.
4. Preserve and improve per-item failure isolation.
5. Keep Advanced honestly labeled V2, or implement a real versioned Advanced V4 before using that label.
6. Rework ranking/fusion around normalized, independent evidence and explicit rejection/match classes.
7. Replace the current explainability dump with a reconstructible ranking decision ledger.
8. Run controlled benchmark: Reverse only vs Advanced only vs fused.

See `docs/ADVANCED_DEVICE_TEST_FINDINGS_2026-08-28.md` for the complete test record.

## Permanent constraints

- **999 is valid and intentional.**
- Never reduce Reverse Image 64 shortlist or 16 SIFT.
- Never weaken/remove Haar/pHash/dHash/HSV256/Sobel/AKAZE/RANSAC/SIFT.
- Never create a second launcher/application.
- Never make Advanced a hidden Reverse Image mode.
- Never require duplicate corpus import/fetch/decode.
- Never pass thousands of URIs through an Intent.
- Never let one bad URI abort a batch.
- Never let normal Activity destruction cancel durable indexing.
- Never call an aggregate score Haar.
- Never call confidence a probability.
- Never claim accuracy/performance improvement without controlled measurements.
- Never add algorithms merely for novelty; each must have a role, representation, metric, cost, failure modes, and benchmark value.
- Read `PROJECT_CONVERSATION_CONTEXT.md` and this file before architecture changes.
