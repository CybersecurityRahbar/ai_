#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from typing import Any

import numpy as np
import tensorflow as tf
import tflite
from PIL import Image
from tokenizers import Tokenizer

EXPECTED = {
    "mobileclip_s2_image.tflite": "9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027",
    "mobileclip_s2_text.tflite": "92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad7182",
    "tokenizer.json": "166a5e8118fe8ff5?",
}
# The tokenizer hash is filled from the known package audit below. Keeping the
# value in a separate constant makes accidental drift obvious in CI.
EXPECTED["tokenizer.json"] = "166a5e8118fe3aa2f60a1877925a4dd5168ce93c58dd5efabc32a9a9eb8335ec"

EXPECTED_EMBED_DIM = 512
EXPECTED_IMAGE_SHAPE = [1, 3, 256, 256]
EXPECTED_TEXT_SHAPE = [1, 77]
EXPECTED_TEXT_VOCAB = 49408
SOT = 49406
EOT = 49407

TYPE_NAMES = {
    int(tflite.TensorType.FLOAT32): "FLOAT32",
    int(tflite.TensorType.FLOAT16): "FLOAT16",
    int(tflite.TensorType.FLOAT64): "FLOAT64",
    int(tflite.TensorType.UINT8): "UINT8",
    int(tflite.TensorType.INT8): "INT8",
    int(tflite.TensorType.INT16): "INT16",
    int(tflite.TensorType.INT32): "INT32",
    int(tflite.TensorType.INT64): "INT64",
    int(tflite.TensorType.BOOL): "BOOL",
}


def digest(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def tensor_info(sg: Any, i: int) -> dict[str, Any]:
    t = sg.Tensors(i)
    raw_name = t.Name()
    name = raw_name.decode("utf-8", "replace") if raw_name else f"tensor_{i}"
    shape = [int(x) for x in t.ShapeAsNumpy().tolist()]
    sig = shape
    if hasattr(t, "ShapeSignatureAsNumpy"):
        try:
            sig = [int(x) for x in t.ShapeSignatureAsNumpy().tolist()]
        except Exception:
            pass
    return {
        "index": i,
        "name": name,
        "dtype": TYPE_NAMES.get(int(t.Type()), str(int(t.Type()))),
        "shape": shape,
        "shape_signature": sig,
        "buffer": int(t.Buffer()),
    }


def inspect_model(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    model = tflite.Model.GetRootAsModel(data, 0)
    subgraphs = []
    for sgi in range(model.SubgraphsLength()):
        sg = model.Subgraphs(sgi)
        tensors = [tensor_info(sg, i) for i in range(sg.TensorsLength())]
        inputs = [tensors[int(sg.Inputs(i))] for i in range(sg.InputsLength())]
        outputs = [tensors[int(sg.Outputs(i))] for i in range(sg.OutputsLength())]
        ops = []
        for oi in range(sg.OperatorsLength()):
            op = sg.Operators(oi)
            ops.append({
                "index": oi,
                "opcode_index": int(op.OpcodeIndex()),
                "inputs": [int(op.Inputs(i)) for i in range(op.InputsLength())],
                "outputs": [int(op.Outputs(i)) for i in range(op.OutputsLength())],
            })
        subgraphs.append({
            "index": sgi,
            "inputs": inputs,
            "outputs": outputs,
            "tensor_count": len(tensors),
            "operator_count": len(ops),
            "operators": ops,
        })
    return {
        "file": path.name,
        "bytes": path.stat().st_size,
        "sha256": digest(path),
        "tflite_schema_version": int(model.Version()),
        "subgraphs": subgraphs,
    }


def inspect_tokenizer(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    tok = Tokenizer.from_file(str(path))
    vocab = (raw.get("model") or {}).get("vocab") or {}
    samples = []
    for text in [
        "a photo of a person",
        "a red car",
        "a landscape",
        "two people standing together",
        "a diagram",
    ]:
        enc = tok.encode(text)
        samples.append({
            "text": text,
            "ids": enc.ids,
            "tokens": enc.tokens,
            "attention_mask": enc.attention_mask,
            "type_ids": enc.type_ids,
            "length": len(enc.ids),
        })
    return {
        "file": path.name,
        "bytes": path.stat().st_size,
        "sha256": digest(path),
        "top_level_keys": sorted(raw.keys()),
        "model": {
            "type": (raw.get("model") or {}).get("type"),
            "vocab_size": len(vocab),
            "unk_token": (raw.get("model") or {}).get("unk_token"),
        },
        "special_token_ids": {
            "<start_of_text>": vocab.get("<start_of_text>"),
            "<end_of_text>": vocab.get("<end_of_text>"),
        },
        "normalizer": raw.get("normalizer"),
        "pre_tokenizer": raw.get("pre_tokenizer"),
        "post_processor": raw.get("post_processor"),
        "decoder": raw.get("decoder"),
        "truncation": raw.get("truncation"),
        "padding": raw.get("padding"),
        "added_tokens_count": len(raw.get("added_tokens", [])),
        "samples": samples,
    }


def clip_token_ids(tokenizer: Tokenizer, text: str) -> tuple[list[int], list[int]]:
    ids = tokenizer.encode(text).ids
    body = ids[: EXPECTED_TEXT_SHAPE[-1] - 2]
    tokens = [SOT] + body + [EOT]
    padded = tokens + [0] * (EXPECTED_TEXT_SHAPE[-1] - len(tokens))
    if len(padded) != EXPECTED_TEXT_SHAPE[-1]:
        raise ValueError(f"tokenized sequence length {len(padded)} != 77")
    mask = [1] * len(tokens) + [0] * (EXPECTED_TEXT_SHAPE[-1] - len(tokens))
    return padded, mask


def normalize(v: np.ndarray) -> np.ndarray:
    x = np.asarray(v, dtype=np.float32).reshape(-1)
    norm = float(np.linalg.norm(x))
    if not np.isfinite(norm) or norm <= 1e-8:
        raise ValueError(f"embedding norm invalid: {norm}")
    return x / norm


def cosine(a: np.ndarray, b: np.ndarray) -> float:
    return float(np.dot(normalize(a), normalize(b)))


def prepare_image(path: Path) -> np.ndarray:
    # Official MobileCLIP-S2 preprocessing is Resize(256), CenterCrop(256),
    # RGB, ToTensor. ToTensor yields float32 in [0,1]; the exported graph is NCHW.
    with Image.open(path) as src:
        image = src.convert("RGB")
        short = min(image.size)
        scale = 256.0 / float(short)
        resized = image.resize(
            (max(256, int(round(image.width * scale))), max(256, int(round(image.height * scale)))),
            Image.Resampling.BILINEAR,
        )
        left = (resized.width - 256) // 2
        top = (resized.height - 256) // 2
        crop = resized.crop((left, top, left + 256, top + 256))
    arr = np.asarray(crop, dtype=np.float32) / 255.0
    return np.transpose(arr, (2, 0, 1))[None, ...]


def invoke_image(path: Path, image: np.ndarray) -> tuple[np.ndarray, dict[str, Any]]:
    interp = tf.lite.Interpreter(model_path=str(path), num_threads=max(1, min(4, os.cpu_count() or 1)))
    interp.allocate_tensors()
    inputs = interp.get_input_details()
    outputs = interp.get_output_details()
    if len(inputs) != 1 or inputs[0]["dtype"] is not np.dtype(np.float32):
        raise ValueError(f"unexpected image input contract: {inputs}")
    if list(inputs[0]["shape"]) != EXPECTED_IMAGE_SHAPE:
        raise ValueError(f"unexpected image input shape: {inputs[0]['shape']}")
    interp.set_tensor(inputs[0]["index"], image.astype(np.float32))
    interp.invoke()
    output = np.asarray(interp.get_tensor(outputs[0]["index"]), dtype=np.float32)
    if output.shape != (1, EXPECTED_EMBED_DIM):
        raise ValueError(f"unexpected image output shape: {output.shape}")
    return output, {
        "input_name": inputs[0]["name"],
        "input_shape": [int(x) for x in inputs[0]["shape"]],
        "input_dtype": str(inputs[0]["dtype"]),
        "output_name": outputs[0]["name"],
        "output_shape": [int(x) for x in output.shape],
        "output_dtype": str(output.dtype),
        "l2_norm": float(np.linalg.norm(output)),
    }


def invoke_text(path: Path, tokenizer: Tokenizer, text: str) -> tuple[np.ndarray, dict[str, Any]]:
    interp = tf.lite.Interpreter(model_path=str(path), num_threads=max(1, min(4, os.cpu_count() or 1)))
    interp.allocate_tensors()
    inputs = interp.get_input_details()
    outputs = interp.get_output_details()
    if len(inputs) != 1 or inputs[0]["dtype"] is not np.dtype(np.int64):
        raise ValueError(f"unexpected text input contract: {inputs}")
    if list(inputs[0]["shape"]) != EXPECTED_TEXT_SHAPE:
        raise ValueError(f"unexpected text input shape: {inputs[0]['shape']}")
    ids, mask = clip_token_ids(tokenizer, text)
    interp.set_tensor(inputs[0]["index"], np.asarray(ids, dtype=np.int64).reshape(EXPECTED_TEXT_SHAPE))
    interp.invoke()
    output = np.asarray(interp.get_tensor(outputs[0]["index"]), dtype=np.float32)
    if output.shape != (1, EXPECTED_EMBED_DIM):
        raise ValueError(f"unexpected text output shape: {output.shape}")
    return output, {
        "text": text,
        "ids": ids,
        "attention_mask": mask,
        "input_name": inputs[0]["name"],
        "input_shape": [int(x) for x in inputs[0]["shape"]],
        "input_dtype": str(inputs[0]["dtype"]),
        "output_name": outputs[0]["name"],
        "output_shape": [int(x) for x in output.shape],
        "output_dtype": str(output.dtype),
        "l2_norm": float(np.linalg.norm(output)),
    }


def smoke_contract(run_path: Path, tokenizer: Tokenizer | None, role: str) -> dict[str, Any]:
    interp = tf.lite.Interpreter(model_path=str(run_path), num_threads=max(1, min(4, os.cpu_count() or 1)))
    interp.allocate_tensors()
    inputs = interp.get_input_details()
    outputs = interp.get_output_details()
    if tokenizer is not None:
        ids, _ = clip_token_ids(tokenizer, "a photo of a person")
        value = np.asarray(ids, dtype=inputs[0]["dtype"]).reshape(inputs[0]["shape"])
        source = "clip-tokenizer-with-special-tokens"
    else:
        shape = [int(x) for x in inputs[0]["shape"]]
        value = np.zeros(shape, dtype=inputs[0]["dtype"])
        source = "zero-image-smoke"
    interp.set_tensor(inputs[0]["index"], value)
    interp.invoke()
    out = np.asarray(interp.get_tensor(outputs[0]["index"]), dtype=np.float32)
    finite = bool(np.isfinite(out).all())
    norm = float(np.linalg.norm(out)) if out.size else 0.0
    return {
        "role": role,
        "input": {"name": inputs[0]["name"], "shape": [int(x) for x in inputs[0]["shape"]], "dtype": str(inputs[0]["dtype"]), "source": source},
        "output": {"name": outputs[0]["name"], "shape": [int(x) for x in out.shape], "dtype": str(out.dtype), "finite": finite, "l2_norm": norm},
        "invoke": "PASS" if finite and norm > 1e-8 else "FAIL",
    }


def write_json(p: Path, value: Any) -> None:
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image-model", type=Path, required=True)
    ap.add_argument("--text-model", type=Path, required=True)
    ap.add_argument("--tokenizer", type=Path, required=True)
    ap.add_argument("--semantic-image", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    paths = [args.image_model, args.text_model, args.tokenizer]
    failures: list[str] = []
    assets: dict[str, Any] = {}
    for p in paths:
        if not p.is_file():
            failures.append(f"missing:{p}")
            continue
        actual = digest(p)
        expected = EXPECTED.get(p.name)
        assets[p.name] = {"sha256": actual, "expected_sha256": expected, "hash_match": expected is None or expected == actual}
        if expected and expected != actual:
            failures.append(f"sha256:{p.name}")

    image_contract = inspect_model(args.image_model)
    text_contract = inspect_model(args.text_model)
    tokenizer_contract = inspect_tokenizer(args.tokenizer)
    contract = {
        "format": "mobileclip-s2-contract-v2",
        "official_reference": {
            "embed_dim": EXPECTED_EMBED_DIM,
            "image_size": 256,
            "context_length": 77,
            "vocab_size": EXPECTED_TEXT_VOCAB,
            "special_tokens": {"<start_of_text>": SOT, "<end_of_text>": EOT},
        },
        "assets": assets,
        "image": image_contract,
        "text": text_contract,
        "tokenizer": tokenizer_contract,
    }
    write_json(args.out / "mobileclip_s2_contract.json", contract)

    tokenizer = Tokenizer.from_file(str(args.tokenizer))
    image_smoke = smoke_contract(args.image_model, None, "image_encoder")
    text_smoke = smoke_contract(args.text_model, tokenizer, "text_encoder")
    if image_smoke["invoke"] != "PASS":
        failures.append("image_smoke")
    if text_smoke["invoke"] != "PASS":
        failures.append("text_smoke")

    vocab = (json.loads(args.tokenizer.read_text(encoding="utf-8")).get("model") or {}).get("vocab") or {}
    if len(vocab) != EXPECTED_TEXT_VOCAB:
        failures.append(f"tokenizer_vocab:{len(vocab)}")
    if vocab.get("<start_of_text>") != SOT or vocab.get("<end_of_text>") != EOT:
        failures.append("tokenizer_special_tokens")

    semantic: dict[str, Any] = {"status": "NOT_RUN", "reason": None}
    try:
        image_raw, image_meta = invoke_image(args.image_model, prepare_image(args.semantic_image))
        prompts = ["a diagram", "a dog", "a cat", "a landscape", "a person"]
        text_vectors: dict[str, np.ndarray] = {}
        text_meta = []
        for prompt in prompts:
            vec, meta = invoke_text(args.text_model, tokenizer, prompt)
            if float(meta["l2_norm"]) <= 1e-8:
                raise ValueError(f"zero text embedding for prompt: {prompt}")
            text_vectors[prompt] = vec
            text_meta.append(meta)
        image_norm = normalize(image_raw)
        scores = {p: float(np.dot(image_norm, normalize(v))) for p, v in text_vectors.items()}
        ranked = sorted(scores.items(), key=lambda kv: kv[1], reverse=True)
        semantic = {
            "status": "PASS" if ranked[0][0] == "a diagram" else "FAIL",
            "image": image_meta,
            "texts": text_meta,
            "cosine_scores": scores,
            "ranking": ranked,
            "top1_expected": "a diagram",
        }
        if semantic["status"] != "PASS":
            failures.append("semantic_cross_modal_ranking")

        pairwise = {}
        for a in prompts:
            for b in prompts:
                if a < b:
                    pairwise[f"{a} <> {b}"] = cosine(text_vectors[a], text_vectors[b])
        semantic["text_pairwise_cosine"] = pairwise
        if len({round(x, 6) for x in pairwise.values()}) < 3:
            failures.append("text_embedding_diversity")
    except Exception as exc:
        semantic = {"status": "FAIL", "error": repr(exc)}
        failures.append(f"semantic:{type(exc).__name__}:{exc}")

    report = {
        "status": "FAIL" if failures else "PASS",
        "failures": failures,
        "assets": assets,
        "image_smoke": image_smoke,
        "text_smoke": text_smoke,
        "tokenizer_contract_checks": {
            "vocab_size": len(vocab),
            "vocab_size_match": len(vocab) == EXPECTED_TEXT_VOCAB,
            "start_token_id": vocab.get("<start_of_text>"),
            "end_token_id": vocab.get("<end_of_text>"),
            "special_token_ids_match": vocab.get("<start_of_text>") == SOT and vocab.get("<end_of_text>") == EOT,
            "context_length": EXPECTED_TEXT_SHAPE[-1],
        },
        "semantic_accuracy_benchmark": semantic,
    }
    write_json(args.out / "mobileclip_s2_deep_audit.json", report)

    lines = [
        "MobileCLIP S2 Deep Audit V2",
        "============================",
        f"STATUS: {report['status']}",
        "",
    ]
    lines.extend(f"[{'PASS' if x['hash_match'] else 'FAIL'}] {name} SHA-256" for name, x in assets.items())
    lines += [
        f"[{'PASS' if image_smoke['invoke'] == 'PASS' else 'FAIL'}] image TFLite invoke + non-zero output",
        f"[{'PASS' if text_smoke['invoke'] == 'PASS' else 'FAIL'}] text TFLite invoke + non-zero output",
        f"[{'PASS' if report['tokenizer_contract_checks']['special_token_ids_match'] else 'FAIL'}] tokenizer vocab + CLIP special tokens",
        f"[{'PASS' if semantic.get('status') == 'PASS' else 'FAIL'}] cross-modal semantic ranking",
        "",
        "The text path uses CLIP SOT/EOT IDs 49406/49407 and a fixed 77-token context.",
        "Image preprocessing follows the official MobileCLIP-S2 256px RGB ToTensor path.",
        "mobileclip_s2_contract.json is generated from the actual downloaded FlatBuffers.",
        "mobileclip_s2_deep_audit.json contains deterministic runtime and semantic checks.",
    ]
    (args.out / "SUMMARY.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
