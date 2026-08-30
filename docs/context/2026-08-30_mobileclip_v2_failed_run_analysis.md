# MobileCLIP S2 Deep Audit V2 — Failed Run Analysis (2026-08-30)

## User-reported GitHub Actions result
The user supplied a failed `MobileCLIP S2 Deep Audit` run. The audit output was:

```text
MobileCLIP S2 Deep Audit V2
============================
STATUS: FAIL

[PASS] mobileclip_s2_image.tflite SHA-256
[FAIL] mobileclip_s2_text.tflite SHA-256
[PASS] tokenizer.json SHA-256
[PASS] image TFLite invoke + non-zero output
[PASS] text TFLite invoke + non-zero output
[PASS] tokenizer vocab + CLIP special tokens
[FAIL] cross-modal semantic ranking

The text path uses CLIP SOT/EOT IDs 49406/49407 and a fixed 77-token context.
Image preprocessing follows the official MobileCLIP-S2 256px RGB ToTensor path.
mobileclip_s2_contract.json is generated from the actual downloaded FlatBuffers.
mobileclip_s2_deep_audit.json contains deterministic runtime and semantic checks.
```

## Interpretation
This is a meaningful failure, not a harmless CI failure. The model is NOT approved for Android semantic-search integration yet.

Two separate issues must be distinguished:

### 1. Text SHA-256 failure
The current public Hugging Face repository `plainhub/mobileclip-s2-tflite` exposes `mobileclip_s2_text.tflite` SHA-256 `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718` and size 253,874,828 bytes. The repository's current `deep_audit.py` expected-hash table also contains that same hash.

Therefore a `[FAIL] mobileclip_s2_text.tflite SHA-256` result in a historical run does NOT by itself prove the binary is corrupt. It most likely means the exact commit checked out by that failed run used a different/stale expected hash, or the downloaded bytes in that run were not the same as the current public file. The next diagnostic run must print both `actual_sha256` and `expected_sha256` from the same commit and record the exact model source revision.

### 2. Cross-modal semantic-ranking failure
This is the more important technical failure. The run proves:
- image model loads and produces a finite, non-zero 512-D output;
- text model loads and produces a finite, non-zero 512-D output;
- tokenizer has 49,408 vocabulary entries;
- tokenizer special IDs are `<start_of_text>=49406` and `<end_of_text>=49407`;
- text input contract is `[1,77]` integer tokens;
- image input contract is `[1,3,256,256]` float32;
- both towers expose a 512-D embedding.

Yet the image/text vectors did not satisfy the expected semantic ordering for the deterministic Apple MobileCLIP reference image. Thus successful TFLite invocation and shape compatibility are insufficient: the two towers may still be semantically incompatible.

## Relevant current binary contract observed from the downloaded audit bundle
Image TFLite:
- input `[1,3,256,256]`
- input dtype FLOAT32
- output `[1,512]`
- output dtype FLOAT32
- SHA-256 `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`

Text TFLite:
- input `[1,77]`
- input dtype INT64
- output `[1,512]`
- output dtype FLOAT32
- SHA-256 in the previously downloaded bundle `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`

Tokenizer:
- vocabulary size 49,408
- `<start_of_text>` ID 49406
- `<end_of_text>` ID 49407

## Official MobileCLIP reference checked
Apple's official MobileCLIP repository confirms the intended inference pattern: encode image and text separately, L2-normalize both feature vectors, then compare them in the shared embedding space. The official preprocessing builder for V1 MobileCLIP uses resize to the configured resolution, center crop, and ToTensor. MobileCLIP-S2 is configured for 256px input and a 512-D shared embedding.

## Root-cause hypotheses that remain open
1. Text TFLite and image TFLite may have been converted from incompatible checkpoints/exports.
2. The text conversion may require a tensor/input convention not captured by the current simple SOT/EOT + zero-pad construction even though the nominal `[1,77] INT64` contract matches.
3. The third-party TFLite conversion may preserve shape/runtime compatibility but not the exact semantic weights/post-processing expected by the image tower.
4. The semantic test threshold or prompt set could be too brittle. This must be checked against the official Apple PyTorch implementation rather than weakening the gate blindly.
5. A normalization/feature-postprocessing mismatch could exist if either exported tower already normalizes or expects a different final feature path.

## Required next diagnostic sequence
Do not integrate into Android yet.

1. Make the audit print actual and expected SHA-256 values for every asset, plus the Hugging Face repository commit/revision used for the download.
2. Save the full numeric semantic scores for the deterministic diagram image against the prompts `a diagram`, `a dog`, `a cat`, and additional control prompts.
3. Measure text-embedding pairwise diversity and norms across a larger prompt set.
4. Verify the exact tensor contract beyond only shape/dtype: names, quantization, output tensor ordering, and whether any hidden/alternate output exists.
5. Independently run the official Apple MobileCLIP-S2 PyTorch model on the SAME deterministic image and SAME text prompts to establish the semantic oracle.
6. Compare PyTorch image/text ranking against the TFLite pair. If PyTorch passes and TFLite fails, the conversion/export or token/input mapping is the root problem; if both fail, the test itself or reference assumptions need correction.
7. Only after those checks pass should the model package be marked production-ready and Android semantic indexing be enabled.

## Important project rule
A green Gradle build does not certify semantic-model correctness. MobileCLIP remains blocked from production integration until structural, runtime, tokenizer, and cross-modal semantic checks all pass with recorded evidence.
