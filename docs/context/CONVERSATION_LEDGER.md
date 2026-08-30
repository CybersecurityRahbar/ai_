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

### Current repository state found
Package: `app/src/main/java/com/example/personalmemoryai/semantic/`
Current files include:
- `MobileClipImageEncoder.kt`
- `MobileClipModelManager.kt`
- `SemanticSearchService.kt`
- `TextEncoder.kt`

The current `MobileClipModelManager.kt` only accepts `mobileclip_s2_fp16.tflite` and validates a single TFLite model intended for the image path.
The current `MobileClipImageEncoder.kt` implements image-only inference and normalization.
The current `SemanticSearchService.kt` is image-semantic search only; it explicitly says the text encoder is deferred.
The current `TextEncoder.kt` is only an interface plus `UnavailableTextEncoder`; text inference is not implemented yet.

### Exact Hugging Face candidate located
Repository/model package:
`plainhub/mobileclip-s2-tflite`

Hugging Face page:
https://huggingface.co/plainhub/mobileclip-s2-tflite

Files currently shown in the repository:
- `mobileclip_s2_image.tflite` — remote size 144,120,668 bytes; SHA-256 `9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027`
- `mobileclip_s2_text.tflite` — remote size 253,874,828 bytes; SHA-256 `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`
- `tokenizer.json` — approximately 1.71 MB
- license shown by Hugging Face: Apache-2.0
- repository model tree identifies base model as `apple/MobileCLIP-S2`

The files were added together in Hugging Face commit `868dc14eb50de4a8347714b019aae242a0778675`, whose verified commit page lists all three files in the same upload.

### Direct links
Model package: https://huggingface.co/plainhub/mobileclip-s2-tflite
Files:
- https://huggingface.co/plainhub/mobileclip-s2-tflite/blob/main/mobileclip_s2_image.tflite
- https://huggingface.co/plainhub/mobileclip-s2-tflite/blob/main/mobileclip_s2_text.tflite
- https://huggingface.co/plainhub/mobileclip-s2-tflite/blob/main/tokenizer.json

### Deep audit findings from public model metadata
1. The package is real and contains the three requested artifacts together.
2. The image tower is 144 MB and the text tower is 254 MB; together the two TFLite files are roughly 398 MB, plus tokenizer metadata.
3. Both TFLite files are stored through Xet/LFS; Hugging Face exposes their remote sizes and SHA-256 values.
4. The repository model tree declares `apple/MobileCLIP-S2` as the base model.
5. The model card README in `plainhub/mobileclip-s2-tflite` is currently empty/minimal, so the third-party package itself does NOT document tensor names, exact tensor shapes/dtypes, preprocessing, tokenizer contract, or runtime examples sufficiently.
6. The official Apple `MobileCLIP-S2` configuration specifies:
   - shared embedding dimension: 512
   - image size: 256 x 256
   - text context length: 77
   - text vocabulary size: 49,408
   - text width/dimension: 512
   - 12 text transformer layers
   - 8 attention heads per layer
   - non-causal text masking in the model config
7. Official Apple inference encodes image and text separately, L2-normalizes both feature vectors, and compares them in the shared embedding space. The official code also exposes model-specific tokenization.
8. Apple’s official tokenizer implementation uses the CLIP-style byte/BPE vocabulary with `<start_of_text>` and `<end_of_text>` special tokens and a default context length of 77. The exact IDs and implementation details must be recovered from the matching tokenizer/package, not guessed.
9. The third-party `tokenizer.json` is a large JSON tokenizer artifact and public rendering exposes its vocabulary content, but the public model card does not provide a clean runtime contract. It must be parsed and validated against the text TFLite model before production use.
10. A second TFLite repository (`anton96vice/mobileclip2_tflite`) exists, but its documented workflow treats tokenizer acquisition separately, so it is not the user’s remembered single-folder package requirement.

### Important limitation of this audit
The web-accessible Hugging Face interface exposes metadata and LFS/Xet hashes for the binary TFLite files but does not provide their internal FlatBuffer tensor tables in text. The local runtime currently lacks TensorFlow/TFLite/FlatBuffers inspection libraries. Therefore this audit CONFIRMS package identity, file integrity metadata, model family, and official architecture configuration, but does NOT yet claim exact binary input/output tensor names, dtypes, quantization parameters, or graph signatures for the two third-party TFLite files.

That binary contract must be obtained by actually downloading the TFLite artifacts into a controlled environment and inspecting the FlatBuffer/interpreter metadata before wiring them into Android.

### Required production validation before accepting the model
The eventual import manager must validate at least:
1. All required files are present.
2. SHA-256 / integrity checks are recorded.
3. Image TFLite tensor inputs/outputs, shape and types are compatible with the Android encoder.
4. Text TFLite tensor inputs/outputs, token ID shape/type, and output embedding are compatible.
5. Tokenizer configuration, vocabulary/BPE data, special tokens, and context length match the text model.
6. Image preprocessing matches the model’s trained contract (256x256 according to official S2 configuration unless binary inspection proves otherwise).
7. Both output embeddings are finite, non-zero, and 512-dimensional according to the official S2 configuration.
8. Cross-modal sanity tests show known matching image/text pairs rank sensibly relative to mismatched pairs.
9. Model version/manifest is persisted so stale embeddings cannot silently mix with a different model/tokenizer version.
10. Only after these checks should the model be marked READY and used for indexing/search.

### Architectural direction agreed
Do NOT build the semantic engine around a guessed model first.
First lock down the exact model package and its real tensor/tokenizer contract, then implement the Android runtime around that contract.

Target architecture:
Image -> MobileCLIP image encoder -> 512-D shared embedding
Text -> exact MobileCLIP tokenizer -> MobileCLIP text encoder -> 512-D shared embedding
Then cosine similarity / ranking for semantic retrieval.

The long-term project direction is a shared AI visual core where semantic embeddings, reverse-image signals, advanced visual analysis, and face embeddings can share decoding/caching/index infrastructure instead of repeatedly decoding the same image.

### New action taken in the 2026-08-30 turn
The user asked to run the stronger validation and explicitly requested that the complete substantive conversation context be kept in `docs`.

The first Deep Audit artifact was downloaded from GitHub Actions and inspected locally. It proved:
- image TFLite input `[1,3,256,256] FLOAT32`, output `[1,512] FLOAT32`
- text TFLite input `[1,77] INT64`, output `[1,512] FLOAT32`
- tokenizer vocabulary size 49,408
- tokenizer contains `<start_of_text>` = 49406 and `<end_of_text>` = 49407
- image smoke output was non-zero
- text smoke output was ZERO (`l2_norm=0.0`)
- despite that, the old audit reported PASS because it only checked finiteness and shape compatibility

This exposed a real audit weakness: TFLite `invoke()` success is not sufficient evidence that the text encoder is functioning correctly.

### Official preprocessing reference checked
The official Apple MobileCLIP implementation was consulted. Relevant official behavior:
- MobileCLIP-S2 image size is 256x256.
- Official inference preprocessing uses resize + center crop + RGB + ToTensor for the MobileCLIP V1 S2 path.
- Official image and text features are L2-normalized before cross-modal cosine-style comparison.
- Official S2 configuration declares embedding dimension 512 and text context length 77.
These references are from Apple’s public repository/model documentation and should remain the source of truth for preprocessing rather than assumptions made from the third-party package.

### Deep Audit V2 implementation
The repository audit was strengthened in commit `20c1c54407e2dbe274f2b725ca4f4fc858b5370b` and the workflow in commit `215c80185e7755ee0a675bbf51c57c6f8acd0afc`.

`tools/mobileclip_audit/deep_audit.py` was subsequently hardened in commit `86a144797a1755e9bd796e6d00bd41cee6c8ecdc` to use value-based dtype validation and a clean expected-hash table.

The audit now:
- records the exact FlatBuffer contract
- verifies the expected model/tokenizer SHA-256 hashes
- verifies image `[1,3,256,256] FLOAT32` input and `[1,512]` output
- verifies text `[1,77] INT64` input and `[1,512]` output
- validates tokenizer vocabulary size 49,408
- validates `<start_of_text>` 49406 and `<end_of_text>` 49407
- constructs the full 77-token CLIP sequence with SOT/EOT and zero padding
- requires non-zero finite embeddings rather than accepting zero outputs
- applies the official 256px RGB ToTensor-style preprocessing for the image audit
- performs real text embedding diversity checks
- performs a deterministic cross-modal semantic ranking test using the official Apple repository’s `docs/fig_accuracy_latency.png` reference diagram and prompts including `a diagram`, `a dog`, and `a cat`
- writes `mobileclip_s2_contract.json` as a V2 contract and `mobileclip_s2_deep_audit.json` as the runtime/semantic report

`.github/workflows/mobileclip_s2_deep_audit.yml` was strengthened in commit `9a62008811c23f551b82e31e89fc50e23c49d5d5` to download the official Apple reference diagram and run the V2 semantic checks, while deliberately excluding `docs/context/**` from the workflow trigger so conversation-ledger updates do not launch needless model downloads/audits.

### Important correctness note about the V2 test
The V2 test is intentionally stricter than the previous audit. A successful result would establish much stronger evidence that the tokenizer + text encoder + image encoder actually form a usable cross-modal MobileCLIP pipeline. A failure must block Android integration until the root cause is understood.

The audit should not claim production semantic quality from one diagram test alone. Passing V2 is a gate for functional compatibility, not a benchmark of broad model accuracy.

### Exact prior user request recorded
The user requested:
"حسنا قم بالاختبار والتنفيذ \nثانيا دخلت الى المستودع ولك لم اجد الكثير من الردود والاجوبه والاساله التي نتداولها هنا وهذا يدل انك لم تكن ترسل كل شيئ الى جيت هاب ولذلك اريد ان تجعل لك قاعده انه ماتم تداوله في المحادثه هنا يتم رفعه الى قسم docs هل فهمت الان ابدا العمل"

Meaning captured for durable project memory: run the stronger MobileCLIP validation and establish/maintain a durable repository record of substantive project conversation context under `docs/context`, so future work can reconstruct goals, decisions, errors, fixes, and test evidence.

### Prior status after implementation
- V2 audit code is committed.
- V2 workflow is committed.
- The model remains unapproved for Android production integration until the stronger V2 gate passes.

## 2026-08-30 21:55 +03:00 — Full repository/session re-audit and durable ledger enforcement

### User request (verbatim)
"حسنا الان اعد المراجعه لانه تم اضافه اشياء وتعديل اشياء اعمل مراجعه شامله بالكامل وايضا ملف session-1788115451049.md الذي في جذر المشروع لانه يحتوي على كامل المحادثه السابقه والاوامر والاساله والاجوبه واين توقفنا بالضبط وايضا راجع مجلد docs وماداخله بشكل كامل وايضا راجع ملفات المشروع بشكل اضافي لتعرف ماذا عملنا واين توقفنا هيا انطلق ولاتنسا رفع اي شيئ نقوم به هنا اي شيئ ارسله لك واي شيئ تجيبني هل فهمت انا لا اريد اذكيرك في كل رساله هل فهمت وسوف اراجع المستودع وارى في مجلد docs هل انت اضفت ملف سياق المحادثه وهل قمت باضافه اخر طلب مني واخر جواب منك بشكل كامل بالتاريخ والساعه في ملف واحد فقط لكل طلب وجواب يتم اضافته الى هذا الملف هل فهمت الان قم بعمل المطلوب واعط اكبر جهد لك واكبر سياق واكبر تفكير"

### Repository/session review performed
The current `main` tree was re-read. The root now contains `session-1788115451049.md`, and the GitHub commit immediately preceding this re-audit (`e6f66c7ec924a4e142f8838a354b7e4e86abb547`, 2026-08-30 18:50:43Z) added that session file. Its current repository size is about 637,852 bytes, making it a substantial conversation artifact rather than a small summary.

The session was sampled across its chronological range, including early DigiKam/reverse-image discussions, the 999-image classical engine work, the Advanced Visual section and its CI fixes, the large-selection/picker failures, the creation of Advanced V2/Region/Structural/Fusion work, the face/person identity redesign, the MobileCLIP package search, the binary-audit design, the actual Deep Audit run, and the subsequent MobileCLIP V2 failure diagnosis. The session's latest available material confirms that the dialogue had reached the MobileCLIP audit/validation path after the face/person architecture had been specified, rather than reaching a completed implementation of the new Face Identity engine.

### Current `docs` and `docs/context` contents verified
The root `docs` directory currently contains:
- `ADVANCED_DEVICE_TEST_ANALYSIS_ADDENDUM_2026-08-28.md`
- `ADVANCED_DEVICE_TEST_FINDINGS_2026-08-28.md`
- `ADVANCED_VISUAL_FINAL_BENCHMARK_PLAN.md`
- `CONVERSATION_CONTEXT.md`
- `CONVERSATION_CONTEXT_TURN_2026-08-28_PICKER_FIX.md`
- `CONVERSATION_TURN_LEDGER.md`
- `FACE_IDENTITY_DEVICE_BENCHMARK_2026-08-29.md`
- `FACE_IDENTITY_VISUAL_MEMORY_ENGINE_V1_SPEC.md`
- `MOBILECLIP_S2_AUDIT_VERIFIED.md`
- `context/`

`docs/context` currently contains:
- `2026-08-29_mobileclip_deep_audit_checkpoint.md`
- `2026-08-30_mobileclip_failure_diagnosis_2.md`
- `2026-08-30_mobileclip_v2_failed_run_analysis.md`
- `CONVERSATION_LEDGER.md`
- `CONVERSATION_LEDGER_RULE.md`

The ledger rule was also reviewed. It requires every substantive project turn to be appended with request/message, assistant work/findings, relevant research/verification, failures/fixes, and current objective/next action. This turn is being appended to that same single conversational ledger rather than creating another per-turn transcript file.

### Important source-code verification performed
The current Android manifest was rechecked. `IntelligenceHomeActivity` is the only `MAIN/LAUNCHER`; `ReverseImageSearchActivity`, `AdvancedVisualIntelligenceActivity`, `BulkImagePickerActivity`, face screens and other feature activities are non-exported. The previous duplicate-launcher defect is therefore not present in the current source.

`AdvancedVisualIntelligenceService.kt` was rechecked. It defines `FUSION_VERSION = "ADVANCED-VISUAL-FUSION-V4"`, uses the independent Advanced corpus, seven query variants, region consistency and multiscale structural consensus, and does NOT route ranking through `ReverseImageSearchService`. Its feature extractor remains V2; V4 denotes the fusion/ranking layer. The current seven variants are original, 90/180/270 degree rotations, and 92/82/72 percent center crops; horizontal flip is not currently one of these whole-image variants.

`BulkImagePickerActivity.kt` was rechecked. It currently exposes five acquisition routes: OEM/system Gallery, modern Photo Picker, system Files, Folder/Tree for scalable large corpora, and the in-app MediaStore browser. It uses `MediaStore.VOLUME_EXTERNAL` only, logical Select All, disk-backed `.uris` queues, and folder/tree streaming. The system multi-document path still receives its ActivityResult/ClipData in memory, so Folder/Tree remains the correct mechanism for many-thousand selections.

`ImageCorpusImportWorker.kt` was rechecked. It uses durable WorkManager execution, batching, per-item isolation, provider read retries, descriptor fallback, and primary external-storage fallback for relevant document URIs. Unsupported/corrupt images and source/provider failures are classified separately from the overall batch.

`AppDatabase.kt` was rechecked at version 12. Advanced V2 fingerprint persistence is represented in the schema, including spatial color, spatial LBP, gradient magnitude, and illumination-robust structure fields. Migration `11 -> 12` recreates the Advanced fingerprint table, which implies a deliberate reindex requirement after that schema-breaking change.

### Face/person state verified against actual code
`FaceEntity` models a face occurrence rather than claiming real-world identity. `EmbeddingEntity` already records model/version/dimension/normalization metadata. `PersonEntity` is a logical visual cluster, not an automatically verified identity.

`FaceAnalysisService` currently runs detector, crop, quality, landmark shape, pose estimation, and all configured identity models while isolating individual model failures. `FaceIndexingService` persists per-face model embeddings plus landmark shape embeddings.

`FaceSearchService` still searches mainly per stored face embedding and groups by `face.id`; it does not yet perform full person-level multi-prototype ranking, open-set margin logic, local facial-part identity evidence, or a true person prototype bank. `PersonClusteringService` has a preliminary diversity-limited template mechanism with a maximum of 12 templates, but it is not yet the complete Face Identity & Visual Memory V1 specified in `FACE_IDENTITY_VISUAL_MEMORY_ENGINE_V1_SPEC.md`.

### MobileCLIP state verified against actual code and audit tooling
The current semantic implementation remains image-only in `SemanticSearchService.kt`; text inference remains deferred at runtime. `MobileClipModelManager.kt` still represents a single imported TFLite image model path rather than the complete Image + Text + Tokenizer package.

The current Deep Audit tooling structurally inspects FlatBuffers, validates the expected contracts, parses the tokenizer, performs non-zero output checks, and runs the diagram-based cross-modal ranking gate. However, `.github/workflows/mobileclip_s2_deep_audit.yml` still downloads the third-party Hugging Face package from mutable `resolve/main` instead of a fixed model revision, which is a reproducibility defect.

The durable MobileCLIP failure analyses establish that the V2 audit must not be bypassed. A historical audit failure included a text SHA-256 failure and cross-modal semantic-ranking failure. The current public text hash matches the expected value now, so that historical hash failure alone does not prove permanent binary corruption. The decisive next experiment is an oracle comparison between the exact third-party TFLite image/text pair and the official Apple MobileCLIP-S2 PyTorch reference using the same deterministic image and prompt set, with exact hashes/revision recorded.

### Where the project is actually stopped now
The project is NOT stopped at a completed Face Identity engine and is NOT approved to integrate MobileCLIP yet.

The immediate authoritative gate is:

`MobileCLIP S2 Deep Audit V2 → fixed provenance/revision → exact binary/tokenizer contract → runtime non-zero outputs → official PyTorch oracle comparison → cross-modal semantic validation`

Only after that gate is resolved should the Android Semantic Runtime be redesigned for Image + Text. The next major architecture stream after that remains implementation of Face Identity & Visual Memory V1, beginning with an end-to-end audit of the existing face detector/alignment/model/database/UI stack rather than inventing an unvalidated model.

### Durable context rule confirmed in this turn
For every substantive future project turn, conversational context will be appended to **only** `docs/context/CONVERSATION_LEDGER.md`. Each entry must contain the timestamp, user request, assistant findings/decision/work, relevant evidence, unresolved items, and next action. No new per-turn conversation-history file should be created. Existing specialized technical documents remain project references, while this ledger is the single ongoing request/response history.

### Repository change for this turn
The current re-audit record is being committed to:
`docs/context/CONVERSATION_LEDGER.md`

No application source code is being changed by this re-audit. No new algorithm is being claimed as implemented. The existing rules remain: one launcher, independent Reverse/Advanced/Face/Semantic engines, shared corpus/decode where appropriate, no recall reduction, no invented models, and no production MobileCLIP approval before the semantic gate passes.
