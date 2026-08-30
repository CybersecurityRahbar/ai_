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
- Tokenizer SHA-256 observed in CI: `166a5e8118fe3aa2f60a1877925a4dd5168ce93c58dd5efabc32a9a9eb8335ec`.
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

### User model-download confirmation
The user confirmed downloading the MobileCLIP image TFLite and text TFLite models to the phone and asked to continue verification. The assistant confirmed that both TFLite binaries should be kept and not replaced; the unresolved artifact is the tokenizer contract.

## 2026-08-30 23:13 +03:00 — Tokenizer Differential Audit V3 result

### User-provided run result
The user shared `MobileCLIP S2 Tokenizer Differential Audit V3` run #3. The job failed because 18 of 19 deterministic test strings diverged from Apple, while the empty string matched. Every non-empty case diverged at sequence index 1; examples included Apple token `320` vs third-party tokens `2486`, `4775`, `546`, `34304`, etc. The output reported `specials_already_present=False` for the divergent cases.

### Interpretation
The V3 result is strong evidence that the third-party `tokenizer.json` produces incompatible token IDs for ordinary text, but it does not by itself establish why. It could be a vocabulary-ID remapping, a different BPE vocabulary/merge set, different normalization/pre-tokenization, or a special-token/padding convention difference. Because the text TFLite model is numerically identical to Apple when fed Apple IDs, changing the Text TFLite binary is not justified.

### Apple reference verification
The pinned Apple source was inspected directly. `mobileclip.get_tokenizer("mobileclip_s2")` constructs `ClipTokenizer`, which in turn uses `open_clip.get_tokenizer(model_name)` and defaults to `ViT-B-16` unless a specific tokenizer name is supplied. Therefore the V3 statement that the reference is Apple `mobileclip.get_tokenizer("mobileclip_s2")` is correct and internally resolves through the OpenCLIP tokenizer used by Apple's MobileCLIP implementation. Apple source also documents that the tokenizer's empty-string result supplies SOT/EOT and that the model consumes fixed-length context tensors.

## 2026-08-30 23:13 +03:00 — Tokenizer Differential Audit V4 implementation

### User request
The user instructed: continue the audit.

### Work performed
The V3 result showed all non-empty strings diverging at index 1, so a deeper audit was implemented instead of immediately declaring the tokenizer corrupt.

`tools/mobileclip_oracle/tokenizer_differential_audit.py` was upgraded to V4 in commit `3a8c0e6544faa2ed0f88b7b904cedfc0936345e3`. The V4 audit now:
- compares Apple MobileCLIP-S2's actual tokenizer wrapper;
- preserves tokenizer-native special-token handling;
- compares final token IDs and first divergence;
- resolves token strings at the first differing IDs when possible;
- compares the complete Apple token-string → ID vocabulary mapping against the third-party mapping;
- counts common token strings with identical IDs versus common token strings with different IDs;
- counts Apple-only and third-party-only token strings;
- inspects the third-party `tokenizer.json` model type, vocabulary size, merges count/sample, normalizer, pre-tokenizer, post-processor, decoder, and added tokens;
- keeps the deterministic multilingual/punctuation corpus used by V3.

The V4 goal is to distinguish a pure ID remapping from a genuinely different tokenization/BPE algorithm. If token strings match while IDs differ, the third-party artifact may contain the right segmentation but the wrong vocabulary index assignment for the TFLite text model. If token strings themselves differ, the BPE/normalizer/pre-tokenizer contract is different.

### Workflow status
The tokenizer differential workflow is configured to run on changes to the tokenizer audit workflow/script and is pinned to the same Apple commit and Hugging Face revision. The V4 script change also triggered the broader Deep Oracle workflow because its path is watched by that workflow. The latest GitHub push at commit `3a8c0e6544faa2ed0f88b7b904cedfc0936345e3` showed the MobileCLIP Deep Oracle workflow in progress; the tokenizer differential run for the V4 change is expected to run from the matching push trigger.

### Current gate
No tokenizer replacement or Android semantic integration is approved yet. The exact V4 report is the next evidence required. The binary evidence remains strong: Image TFLite and Text TFLite are numerically compatible with Apple's reference under identical input contracts. The remaining investigation is strictly tokenizer parity and sequence construction.

### Next action
Retrieve the V4 tokenizer differential report and determine whether the third-party JSON has the same token strings/BPE semantics with different IDs, or a fundamentally different tokenizer contract. Then use that evidence to choose the minimal safe fix before enabling the Android Image + Text semantic runtime.
