# MobileCLIP-S2 semantic models

The verified MobileCLIP-S2 deployment uses two separate TFLite towers imported by the user at runtime:

- `mobileclip_s2_image.tflite` — image tower, 256x256 FLOAT32 input, 512-D FLOAT32 output.
- `mobileclip_s2_text.tflite` — text tower, `[1,77]` INT64 token input, 512-D FLOAT32 output.

The binaries are intentionally not stored in this Git repository because they are large. The application imports them from device storage and copies them into private app storage under:

`<app-private-files>/models/semantic/`

The runtime performs tensor-contract and non-zero/finite-output validation before activating a tower. The two towers are compared against the pinned Apple MobileCLIP-S2 reference and must remain on the same verified semantic space.

## Tokenizer

The `plainhub/mobileclip-s2-tflite` `tokenizer.json` is **not** used for production tokenization. The tokenizer differential audit proved that its vocabulary and token-to-ID mapping are identical to Apple, but its serialized execution path produces different tokenization for ordinary text.

Production Android tokenization follows the Apple/OpenCLIP CLIP tokenizer semantics using the official Apple MobileCLIP-S2 tokenizer assets (`vocab.json` + `merges.txt`) pinned at:

`a406a1bd0b882b27509e608f3cb199de52010c4d`

The tokenizer assets are prepared during the Gradle build and stored only as application assets; no network access is required at runtime.

## Semantic search flow

`text query → OpenCLIP-compatible tokenizer → INT64 [1,77] → Text TFLite → normalized 512-D → cosine against persisted IMAGE embeddings`

`query image → Image TFLite → normalized 512-D → cosine against persisted IMAGE embeddings`

The persistent image embeddings remain in Room and are versioned independently from the text query tower, while both are required to share the same MobileCLIP-S2 semantic space.

See `docs/context/CONVERSATION_LEDGER.md` for the complete audit history and current project gate.
