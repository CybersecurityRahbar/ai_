# MobileCLIP S2 — Verified Contract Audit

Date: 2026-08-29

## Source bundle

The GitHub Actions audit artifact `mobileclip-s2-audit-bundle` (workflow run `33270564030`, artifact `9719977302`) contains the three model assets plus a generated contract:

- `.audit/mobileclip_s2/mobileclip_s2_image.tflite` — 144,120,668 bytes
- `.audit/mobileclip_s2/mobileclip_s2_text.tflite` — 253,874,828 bytes
- `.audit/mobileclip_s2/tokenizer.json` — 1,708,304 bytes
- `mobileclip_s2_contract.json` — generated structural audit

SHA-256:

- image: `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`
- text: `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad7182`
- tokenizer: `166a5e8118fe3aa2f60a1877925a4dd5168ce93c58dd5efabc32a9a9eb8335ec`

## Tensor contract confirmed from the actual JSON

### Image encoder

- TFLite schema version: 3
- subgraphs: 1
- input tensor index: 0
- input name: `serving_default_args_0:0`
- input shape: `[1, 3, 256, 256]`
- input tensor type: float32 (`type=0` in the audit parser)
- output tensor index: 1794
- output name: `StatefulPartitionedCall:0`
- output shape: `[1, 512]`
- output type: float32 (`type=0`)
- buffers: 1798
- operator codes: 15

### Text encoder

- TFLite schema version: 3
- subgraphs: 1
- input tensor index: 0
- input name: `serving_default_args_0:0`
- input shape: `[1, 77]`
- input tensor type: int64 (`type=4` in the audit parser)
- output tensor index: 823
- output name: `StatefulPartitionedCall:0`
- output shape: `[1, 512]`
- output type: float32 (`type=0`)
- buffers: 827
- operator codes: 17

Thus the two encoders structurally expose the expected 512-dimensional embedding outputs and the expected MobileCLIP-S2 image/text input sizes.

## Tokenizer findings

The downloaded `tokenizer.json` is a minimal BPE model object with:

- vocabulary size: 49,408
- merge count: 48,894
- `model` keys: `vocab`, `merges`
- no added tokens in this JSON
- no normalizer object
- no pre-tokenizer object
- no decoder object
- no post-processor object
- no explicit BOS/EOS/PAD metadata in this JSON

This does **not** mean the tokenizer is invalid. It means this file alone does not encode all CLIP special-token/preprocessing policy. The policy must be supplied by the compatible CLIP/OpenCLIP configuration/implementation.

## External cross-check

Apple's MobileCLIP-S2 configuration specifies:

- embedding dimension: 512
- image size: 256
- text context length: 77
- vocabulary size: 49,408
- text dimension: 512
- 12 transformer layers
- 8 attention heads per layer
- non-causal text masking

The public OpenCLIP tokenizer implementation documents the CLIP BPE vocabulary behavior, including the 77-token context and CLIP start/end special tokens. The corresponding Hugging Face tokenizer configuration for `Xenova/mobileclip_s2` identifies the tokenizer as CLIPTokenizer and explicitly defines `<|startoftext|>`, `<|endoftext|>`, and a padding policy.

References checked:
- Apple MobileCLIP-S2 config on Hugging Face
- OpenCLIP tokenizer implementation
- Xenova MobileCLIP S2 tokenizer configuration
- public TFLite bundle containing the image/text TFLite models and tokenizer

## What is still NOT proven by this audit

This structural audit does not by itself prove numerical compatibility. Before integrating MobileCLIP into Android, the following must be tested end-to-end:

1. exact image preprocessing (RGB order, scaling, mean/std, resize/interpolation/crop)
2. exact CLIP tokenization including byte-to-unicode BPE behavior and special IDs
3. exact sequence construction and padding/truncation to 77 tokens
4. whether L2 normalization is required outside the TFLite graphs
5. numerical inference on known image/text pairs against an independent reference implementation
6. cosine-similarity sanity checks proving image/text embeddings inhabit the same trained space

## Integration gate

Do not declare MobileCLIP "production-ready" until an automated inference audit proves the above behavior. The current artifact proves the files are present and their structural tensor contracts match the expected MobileCLIP-S2 dimensions; it is a strong acquisition/contract checkpoint, not the final numerical conformance test.
