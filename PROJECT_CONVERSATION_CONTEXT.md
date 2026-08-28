# Personal Memory AI — Persistent Conversation Context Ledger

## Purpose

Durable memory for the reverse-image/indexing/Advanced Visual Intelligence development conversation. Read this file and `PROJECT_PROGRESS.md` before modifying Reverse Image, shared corpus/indexing, or Advanced Visual Intelligence.

## Latest device-test checkpoint — 2026-08-28

The user installed the current build and performed a real Android 12 / SM-G981U test. This is the authoritative runtime checkpoint and overrides assumptions based only on CI.

### A. Picker / corpus selection problems found

1. `OPEN SYSTEM FILES / GALLERY` opened filesystem/document providers but did not expose the expected Samsung Gallery UI. Cause: the current implementation uses `OpenMultipleDocuments(image/*)`, which is a document-provider flow and cannot guarantee that an OEM Gallery app appears. Required solution: add a genuine Gallery/Photo Picker route while retaining the existing Files/document route.

2. Selecting about 500+ items through the system document route worked.

3. Selecting all 6,000+ images from the in-app picker terminated the app. Root cause: `selectAllMedia()` calls `queryAllUris()` to materialize the complete URI corpus into a `List<String>`, then copies it into `selected`. This violates the large-corpus memory requirement. Required solution: logical Select All state + streamed MediaStore enumeration to the existing `.uris` queue file on submission. Never materialize all URIs for Select All.

4. In-app media count showed approximately `215,992`, while device storage/media inspection showed approximately `107,994`. Root cause identified in code: `prepareVolumes()` adds `MediaStore.VOLUME_EXTERNAL` and also adds individual external volume names. The aggregate external collection can contain the same media as the per-volume collections, producing near-2x enumeration. Required solution: aggregate OR individual volumes, never both; add unique identity regression coverage.

5. Mixed-format/folder import behavior is improved. Invalid/unreadable images now fail item-by-item while other selected items can continue. Preserve this behavior and improve diagnostic failure categories.

### B. Shared indexing observations

The user observed roughly a 4x improvement in indexing speed compared with the older implementation. This is a real device observation but not a controlled benchmark claim.

Logs demonstrate shared decode, batch size 16, parallelism 4 and durable batch commits. Example: `items=1083`, `indexed=0`, `skipped=1083`, `failed=0` indicates the existing fingerprints were already up to date and recomputation was skipped.

### C. Critical Advanced version correction

Advanced is currently V2, not V4.

Current source:

`AdvancedVisualFingerprintEngine.ENGINE_VERSION = "ADVANCED-VISUAL-CLASSICAL-V2"`

The seven query variants are therefore correctly reported as `Advanced V2`.

The `...-V4` label in logs refers to the **Classical Reverse Image** engine:

`CLASSICAL-PHASH-DHASH-HSV256-SOBEL-AKAZE-V4`

Do not call Advanced V4 until a real Advanced V4 implementation exists, with a new fingerprint contract, persistence/migration, indexing, query/search, and regression tests.

### D. Advanced vs Reverse Image runtime quality finding

The user observed little visible difference between the Advanced and Local Reverse Image result sets. This does not prove identical algorithms. The current Advanced service intentionally calls the Reverse Image service for 64 classical candidates, computes Advanced V2 against the full Advanced corpus, unions candidate IDs, and fuses the results. Thus the existing strong classical engine can dominate the resulting ordering.

The user also observed that true variants of the queried image often rank highly, but some unrelated or weakly similar images can appear above genuine variants.

Current likely causes requiring measurement:

- global color/texture agreement can be generic;
- correlated signals are counted too independently;
- classical and Advanced score scales are heuristic and not calibrated to each other;
- Advanced calls the classical path with `minimumSimilarity=0.0f`, allowing all 64 classical candidates into fusion;
- current penalties do not form calibrated reject/gate rules;
- geometric/local evidence needs stronger authority when demonstrably supported;
- no explicit match-type classifier exists;
- no ranking-margin or cluster-coherence gate exists.

Target future ranking architecture:

`broad recall retrieval → signal normalization → correlation control → independent evidence gates → geometric authority → match-type classification → false-positive suppression → final ranking`

Never reduce the existing Reverse Image 64/16 pipeline to gain speed.

### E. Explainability finding

Current `WHY THIS RESULT` displays many component percentages and reason codes, but the user reports that it is not practically useful.

Required new explanation model:

- match type;
- final score and acceptance band;
- ranking margin to next candidates;
- independent evidence count;
- geometric support;
- regional support;
- structural support;
- color/texture evidence;
- contradiction/negative evidence;
- actual weighted independent contributions;
- why included;
- why this rank;
- why not rejected.

The explanation must be reconstructible from real stored decision evidence; it must not be a post-hoc text decoration.

### F. Required next implementation phase

Priority 0 — selection correctness:

1. Fix aggregate/per-volume MediaStore double counting.
2. Replace Select All materialization with logical Select All + streaming queue writing.
3. Add genuine Gallery/Photo Picker route and retain Files/document provider route.
4. Preserve per-item failure isolation and improve failure taxonomy.

Priority 1 — Advanced identity:

5. Keep Advanced labeled V2 until a real V4 engine is implemented; do not fake versioning.

Priority 2 — ranking quality:

6. Normalize signal scales.
7. Add independence-aware evidence accounting/correlation suppression.
8. Add explicit match classification.
9. Add geometric/local authority and rejection logic when strong support exists.
10. Add negative evidence, ranking margin, and cluster coherence.

Priority 3 — explainability:

11. Persist a reconstructible decision ledger.
12. Show why included, why this rank, and why not rejected.
13. Expose actual independent evidence contributions.

Priority 4 — benchmark:

14. Run identical queries through Reverse-only, Advanced-only, and fused modes.
15. Measure Recall@1/5/10, false positives, ranking position, latency, indexing throughput, memory, failures and explanation correctness.

## Current permanent architecture

- `IntelligenceHomeActivity` is the single Android launcher.
- `LOCAL REVERSE IMAGE SEARCH` remains an independent top-level screen.
- `ADVANCED VISUAL INTELLIGENCE` remains an independent top-level screen in the same visual design language.
- Both consume one shared local corpus/import/decode path.
- Reverse Image retains its own Haar/Classical/AKAZE/SIFT feature stores.
- Advanced retains an independent Advanced V2 feature store.
- Reverse Image remains full-strength: DigiKam-style Haar, pHash, dHash, HSV256, spatial Sobel/shape, persisted AKAZE, AKAZE mutual/RANSAC, SIFT mutual/RANSAC, rotation/crop variants.
- Reverse Image recall pipeline is locked at `full corpus → 64 → AKAZE/RANSAC all 64 → SIFT/RANSAC all 16 → final ranking`.
- MobileCLIP/neural semantic search remains postponed.
- Do not duplicate corpus import/decode for Advanced.
- Do not pass thousands of URIs through an Intent.
- Do not lower candidate coverage to improve speed.
- Do not claim confidence is probability.
- Do not claim performance/accuracy gains without measurement.
- Every future algorithm must have a defined purpose, representation, metric, cost, failure modes and benchmark value.

## Previous runtime incident retained

The earlier `ActivityNotFoundException` was caused by `BulkImagePickerActivity.launchIntent()` returning a bare Intent. It was fixed by explicit component routing and a regression test. The current device test did NOT report that crash; the new selection issues above are separate.

## Documentation checkpoint

Full device findings are recorded in:

`docs/ADVANCED_DEVICE_TEST_FINDINGS_2026-08-28.md`

The progress log is updated in:

`PROJECT_PROGRESS.md`
