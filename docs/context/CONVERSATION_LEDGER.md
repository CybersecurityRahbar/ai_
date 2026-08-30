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

## 2026-08-30 23:13 +03:00 — Tokenizer Differential Audit V3 result

### User-provided run result
The user shared `MobileCLIP S2 Tokenizer Differential Audit V3` run #3. The job failed because 18 of 19 deterministic test strings diverged from Apple, while the empty string matched. Every non-empty case diverged at sequence index 1. The preliminary V3 method had manually handled special tokens and was not yet sufficient to distinguish the cause.

### Interpretation
The V3 result was evidence of tokenizer incompatibility but not yet root-cause proof.

## 2026-08-30 23:20 +03:00 — Tokenizer Differential Audit V4 result and root-cause isolation

### User-provided run result
The user shared the V4 Actions output. It reported:
- `Cases: 19`
- `Divergent: 18`
- `Apple vocab: 49408`
- `Third-party vocab: 49408`
- `Common token strings: 49408`
- `Same token->ID mappings: 49408`
- `Remapped common tokens: 0`
- `Apple-only tokens: 0`
- `Third-only tokens: 0`

For every non-empty case the first divergence was index `1`, and token strings themselves differed, e.g. Apple `a</w>` vs third-party `adi`, Apple `two</w>` vs third-party `two`, and Apple `hello</w>` vs third-party `hello`.

### Root-cause conclusion
This is not a vocabulary-ID remapping problem. The entire 49,408-token vocabulary has identical token→ID mappings. The incompatibility is in tokenizer algorithm/configuration, especially word-boundary/pre-tokenization/BPE semantics. The Text TFLite binary remains approved because it matches Apple under Apple token IDs.

## 2026-08-30 23:22 +03:00 — User requests complete remaining-error review

### User request
The user shared another tokenizer audit failure and asked why the errors were not fully fixed and requested a complete review of remaining errors.

### Work performed
The V4 script/CI path was reviewed instead of treating the red workflow as evidence that the model was broken. The tokenizer parity failure is intentionally a data/parity gate, while earlier runtime exceptions had already been fixed.

A three-way audit was added comparing:
1. Apple MobileCLIP-S2 runtime tokenizer;
2. exact third-party tokenizer JSON;
3. official Apple MobileCLIP-S2/OpenCLIP tokenizer JSON.

## 2026-08-30 — User-provided V6 tokenizer audit result

### User-provided run result
The user shared `MobileCLIP S2 Tokenizer Differential Audit V6` output. The workflow completed the tokenization comparison and reported:
- `Third-party divergent cases: 18 / 19`
- Apple vocabulary size `49408`; third-party vocabulary size `49408`
- all `49408` token strings were common
- all `49408` token→ID mappings were identical
- zero remapped, Apple-only, or third-only tokens
- representative divergence: Apple `a</w>` vs JSON `adi`, Apple `two</w>` vs JSON `two`, Apple `hello</w>` vs JSON `hello`

### Important tooling gap discovered
Although the V6 implementation accepted `--official-apple-tokenizer`, the console output only printed the third-party comparison. Therefore a green/red workflow state did not visibly prove the official Apple JSON result. The script needed to print and evaluate the official Apple JSON parity explicitly.

### Remediation
`tools/mobileclip_oracle/tokenizer_differential_audit.py` was updated to V7 in commit `257d107f7abb550189f536c7b831830cef5e966e`.
The V7 behavior:
- compares both third-party and official Apple tokenizer JSON against the authoritative Apple runtime;
- prints official Apple JSON divergence count;
- records a production tokenizer verdict;
- treats third-party divergence as expected diagnostic evidence when official Apple JSON matches the runtime;
- keeps the workflow red only when official Apple JSON itself diverges or when the official JSON was not supplied.

The workflow already passes `--official-apple-tokenizer` and pins Apple MobileCLIP source plus the exact Hugging Face revisions. The next run must therefore provide the missing decisive number: `Official Apple JSON divergent cases: 0 / 19` or a concrete divergence requiring further investigation.

### Current technical state
- `mobileclip_s2_image.tflite`: keep; Deep Oracle proved numerical compatibility with Apple.
- `mobileclip_s2_text.tflite`: keep; Deep Oracle proved numerical compatibility with Apple under identical token IDs.
- Third-party `tokenizer.json`: not approved for Android production tokenization because its execution differs from Apple despite identical vocabulary mapping.
- Official Apple tokenizer JSON: awaiting the V7 three-way parity result.
- Android semantic Image↔Text integration: still blocked pending tokenizer parity.

### Next action
Run the V7 tokenizer differential workflow, read the report/artifact, verify official Apple JSON parity, then lock the Android tokenizer implementation and rerun the full end-to-end Deep Oracle with the selected tokenizer before resuming semantic search integration.

## 2026-08-30 — Current user turn

### User request
The user asked to inspect the latest V6 workflow output and continue the audit, with emphasis on why the errors remain and on identifying every remaining issue before proceeding.

### Assistant finding/work
The latest V6 output is not evidence that the TFLite models are wrong. The vocabulary comparison is exact, while segmentation differs. The actual remaining CI issue is therefore tokenizer parity plus insufficient visibility of the official Apple JSON result in the console output. The audit script was hardened to print and evaluate both JSON variants explicitly and to distinguish a deliberate parity finding from an implementation crash.

### Current gate
Do not replace either TFLite binary. Do not enable Android semantic search yet. The decisive next evidence is the V7 three-way report, specifically the official Apple JSON divergence count and its token-by-token result.
