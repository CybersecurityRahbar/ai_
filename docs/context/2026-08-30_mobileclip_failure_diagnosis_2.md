# MobileCLIP S2 failure diagnosis — 2026-08-30

## User request
Diagnose the MobileCLIP S2 Deep Audit V2 failure itself, not merely report that CI failed. Determine why `mobileclip_s2_text.tflite` SHA-256 and `cross-modal semantic ranking` failed, and preserve this investigation in project context.

## Repository evidence reviewed
- Current Android Build run `33310000420` succeeded.
- Current binary audit run `33310000433` succeeded.
- The current `deep_audit.py` expected text SHA is `92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad718`.
- Hugging Face currently reports the same SHA for `plainhub/mobileclip-s2-tflite/mobileclip_s2_text.tflite`, size 253,874,828 bytes.
- Hugging Face commit `868dc14eb50de4a8347714b019aae242a0778675` added image TFLite, text TFLite, and tokenizer together.
- The current Deep Audit workflow downloads from the mutable `main` revision of the third-party Hugging Face repository instead of pinning a model commit.

## Historical failure interpretation
The failed V2 run occurred after commit `20c1c54407e2dbe274f2b725ca4f4fc858b5370b`, while the current repository later documented the failure at commit `e9554520a6e17066a2ba6b95d75ce89e414e4e93`.

### Text SHA failure
The current expected text hash matches the current public Hugging Face file. Therefore the historical `[FAIL] mobileclip_s2_text.tflite SHA-256` does not establish corruption by itself. The most defensible explanation is that the failed run downloaded bytes that differed from the current file or used stale expected-hash/model provenance at that point in history. The workflow is currently non-reproducible because it downloads `resolve/main` rather than a fixed HF commit/revision.

### Cross-modal semantic failure
The V2 audit proves the image and text models can be allocated and invoked, and that tokenizer vocabulary/special-token checks pass. However, the semantic gate is only a five-prompt ranking assertion whose PASS condition is that `a diagram` is rank #1 for the Apple reference image. The report does not print the score table in the console, so the failure does not reveal whether the issue is model incompatibility, token construction, export/conversion mismatch, preprocessing, or an over-brittle semantic gate.

The official Apple MobileCLIP code confirms that image and text features are encoded independently, L2-normalized, and compared in their shared embedding space. Apple also provides an official MobileCLIP-S2 checkpoint and reference image. This establishes the correct semantic oracle, but the current audit has not yet run the same image and prompts through the official PyTorch checkpoint and compared the resulting vectors against the third-party TFLite pair.

## Stronger conclusion
At this stage it is NOT justified to say that the TFLite image/text pair is semantically correct merely because both invocations pass. The next decisive experiment is an oracle comparison:
1. pin the third-party TFLite package to HF commit `868dc14eb50de4a8347714b019aae242a0778675`;
2. record exact SHA-256 values used in the run;
3. run the official Apple MobileCLIP-S2 PyTorch model on exactly the same reference image and prompt set;
4. run the third-party TFLite image/text pair on the same inputs;
5. compare rankings and cosine similarities, plus embedding norms;
6. inspect token IDs/post-processor details and tensor output selection if the TFLite result diverges.

If the official PyTorch pair ranks the diagram correctly while TFLite does not, the problem is in the third-party TFLite export/conversion, tensor conventions, or tokenization/input mapping. If both fail, the current semantic test is invalid or the reference assumptions are wrong.

## Important architectural gate
Do not mark MobileCLIP as production-ready for Android semantic search until structural contract, deterministic provenance, tokenizer contract, runtime behavior, and cross-modal semantic correctness have all passed with recorded evidence.
