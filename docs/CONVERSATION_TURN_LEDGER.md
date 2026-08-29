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
