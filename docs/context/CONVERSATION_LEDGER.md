# Durable Conversation Ledger

## Policy established by the user
- Treat durable project context as a first-class requirement.
- Every project-related conversation turn, investigation, decision, experiment result, error, correction, and architectural decision should be recorded in the repository so future work does not lose context.
- This ledger is an ongoing project memory, not a substitute for source code or test evidence.
- Each future project-related assistant turn should append the substantive user request, the assistant's resulting decisions/findings, tests performed, and concrete repository changes. Full verbatim historical conversation is only possible when that text is available to the assistant; unavailable earlier text must not be fabricated.

## 2026-08-29 — Semantic Search / MobileCLIP model discovery and audit

### User objective
Reactivate the previously disabled semantic image/text search system in the Android Personal Memory AI project.
Use a MobileCLIP model with a matching text encoder and tokenizer. The user specifically remembers a Hugging Face package containing:
- MobileCLIP image model
- MobileCLIP text encoder/model
- tokenizer
- both model towers in `.tflite` format
The intended deployment pattern is: build the Android application first, then import the full model package on the phone, validate it, and use it locally.

### Durable technical history retained
- Exact candidate package: `plainhub/mobileclip-s2-tflite`.
- Pinned package revision used by audits: `868dc14eb50de4a8347714b019aae242a0778675`.
- Image TFLite SHA-256: `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`.
- Text TFLite SHA-256: `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`.
- Official Apple MobileCLIP reference commit used by the oracle: `aecfb5453d022e9deff12f81a150ea8f35194baa`.
- Official S2 contracts established previously: 512-D shared embedding, 256x256 image input, 77-token text context, vocab size 49,408, SOT 49,406 and EOT 49,407.

## 2026-08-30 21:55 +03:00 — Full repository/session re-audit and durable ledger enforcement

### User request
The user requested a full re-audit of the repository, the root `session-1788115451049.md`, all `docs` recursively, and the actual source tree; the user also established that substantive conversation context must be kept in the single central ledger `docs/context/CONVERSATION_LEDGER.md` with timestamps, request, response/work, evidence, unresolved issues, and next action.

### Result
The current repository was re-read. The root session file is a large historical conversation artifact. The current docs and source confirmed that Reverse Image, Advanced Visual, shared indexing/import, and face infrastructure exist; the new Face Identity V1 is specified but not complete; MobileCLIP remains gated on semantic validation. The duplicate-launcher issue is already fixed, the Picker has five routes with Folder/Tree as the scalable path, and MediaStore uses aggregate external volume only.

The authoritative next gate was set as:
`MobileCLIP S2 Deep Audit V2 → fixed provenance/revision → exact binary/tokenizer contract → runtime non-zero outputs → official PyTorch oracle comparison → cross-modal semantic validation`.

## 2026-08-30 23:02 +03:00 — Deep Oracle Audit and tokenizer investigation

### User request
The user selected the next step: execute a Deep Oracle Audit comparing Apple PyTorch MobileCLIP-S2 against the third-party TFLite image/text models on identical inputs and report the actual numerical differences.

### Execution and failure correction
The first Oracle workflow run failed before the oracle program started because `APPLE_REF` was not defined in the asset-download step while the shell used `set -euo pipefail`. The download itself had otherwise succeeded. The workflow was corrected by defining `APPLE_REF` in that step, after which the successful Oracle run completed.

### Deep Oracle V1 result
The successful run proved:
- Image Apple vs TFLite normalized cosine: `1.000000000`.
- Text TFLite matches Apple PyTorch to approximately `1e-6` scale when the TFLite model is fed the exact token IDs produced by Apple MobileCLIP.
- For example: `a diagram` cosine `1.000000062`, max absolute difference `1.90735e-06`; `a dog` cosine `1.000000104`, max absolute difference `6.67572e-06`; `a cat` cosine `0.999999994`, max absolute difference `2.86102e-06`.
- Using the third-party tokenizer output caused divergent text embeddings and wrong top-1 ranking (`screenshot` instead of Apple's `diagram`).

This strongly isolated the remaining issue to tokenization or token-sequence construction rather than the image TFLite model or text TFLite model.

### Methodological correction before tokenizer verdict
A review of the first tokenizer differential implementation found two issues that had to be eliminated before declaring the third-party tokenizer wrong:
1. The first differential script manually wrapped `Tokenizer.encode()` with SOT/EOT, even though the JSON may have a post-processor that already inserts them.
2. The differential script used generic OpenCLIP `ViT-B-16` tokenization as its default Apple reference instead of the official Apple MobileCLIP-S2 tokenizer.

Therefore the tokenizer verdict from the first differential attempt is explicitly NOT authoritative.

### Corrected tokenizer differential audit
`tools/mobileclip_oracle/tokenizer_differential_audit.py` was corrected to:
- use `mobileclip.get_tokenizer("mobileclip_s2")` as the Apple reference;
- respect existing special-token post-processing in `tokenizer.json`;
- record raw token IDs, raw token strings, SOT/EOT IDs, post-processor state, and first divergence index;
- compare a deterministic corpus containing English, capitalization, punctuation, Arabic, digits, symbols, and multiword prompts.

The corrected audit is configured in `.github/workflows/mobileclip_s2_tokenizer_differential_audit.yml`, which pins the Apple reference commit and the exact Hugging Face tokenizer revision and uploads the JSON report even if the differential check fails.

### Current state at the end of this turn
- `mobileclip_s2_image.tflite`: keep; Oracle evidence shows numerical agreement with Apple.
- `mobileclip_s2_text.tflite`: keep; Oracle evidence shows numerical agreement with Apple when supplied identical token IDs.
- `tokenizer.json`: unresolved until the corrected differential audit runs and its report is examined.
- Android MobileCLIP production integration remains blocked until tokenizer parity is established.
- The current repository HEAD includes the corrected differential script at commit `2a6842399eb1853873fac058f290971f86c9e670`.

## 2026-08-30 23:02 +03:00 — User has downloaded the model pair and requested continuation

### User request
The user confirmed downloading the Image TFLite and Text TFLite models to the phone and asked to continue verification so the project can resume; the user also noted that two previous assistant turns had not been fully reflected in the GitHub conversation context.

### Repository-memory correction
The assistant acknowledged the ledger omission and re-established the rule that every substantive project turn must be recorded in this single file. The prior missing context was reconstructed from the available conversation and repository evidence rather than fabricated.

## 2026-08-30 23:02 +03:00 — Corrected tokenizer differential audit execution

### User request
The user instructed: "حسنا اكمل التدقيق هيا" (continue the audit).

### Work performed
The tokenizer differential tooling and workflow were re-read before execution. The review found that the differential script still referenced generic OpenCLIP `ViT-B-16` as its Apple tokenizer model, which was methodologically incorrect for a MobileCLIP-S2 parity test.

`tools/mobileclip_oracle/tokenizer_differential_audit.py` was corrected in commit `2a6842399eb1853873fac058f290971f86c9e670` to use the official Apple `mobileclip.get_tokenizer("mobileclip_s2")`. It also preserves tokenizer-native special-token post-processing rather than blindly adding SOT/EOT.

The tokenizer differential workflow remains:
`.github/workflows/mobileclip_s2_tokenizer_differential_audit.yml`
and is pinned to Apple commit `aecfb5453d022e9deff12f81a150ea8f35194baa` and tokenizer revision `868dc14eb50de4a8347714b019aae242a0778675`.

### Current gate
A new tokenizer differential workflow run must produce the authoritative answer. Until its report is examined, `tokenizer.json` remains unapproved and Android semantic integration remains blocked.

### Next action
Retrieve and analyze the corrected differential report token-by-token; if divergences remain, identify the exact tokenizer component responsible (normalizer, pre-tokenizer/byte encoding, BPE merges, added/special tokens, or post-processor). If IDs match, rerun the Oracle using the tokenizer natively and then clear the tokenizer gate.
