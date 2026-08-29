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
from tokenizers import Tokenizer

EXPECTED = {
    "mobileclip_s2_image.tflite": "9190906f0af7c7da7fb64635332d739ace538a0421aacda912a8abe2f946c027",
    "mobileclip_s2_text.tflite": "92eba285a505df19f13126d373773714b4aae57863c7a6ba277d562ff7ad7182",
    "tokenizer.json": "166a5e8118fe3aa2f60a1877925a4dd5168ce93c58dd5efabc32a9a9eb8335ec",
}
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
    samples = []
    for text in ["a photo of a person", "a red car", "a landscape", "two people standing together"]:
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
        "model": raw.get("model"),
        "normalizer": raw.get("normalizer"),
        "pre_tokenizer": raw.get("pre_tokenizer"),
        "post_processor": raw.get("post_processor"),
        "decoder": raw.get("decoder"),
        "truncation": raw.get("truncation"),
        "padding": raw.get("padding"),
        "added_tokens_count": len(raw.get("added_tokens", [])),
        "samples": samples,
    }


def run_model(path: Path, tokenizer: Tokenizer | None, role: str) -> dict[str, Any]:
    interp = tf.lite.Interpreter(model_path=str(path), num_threads=max(1, min(4, os.cpu_count() or 1)))
    interp.allocate_tensors()
    inputs = interp.get_input_details()
    outputs = interp.get_output_details()
    result: dict[str, Any] = {"role": role, "inputs": [], "outputs": [], "invoke": "PASS"}

    for d in inputs:
        shape = [int(x) for x in d["shape"]]
        dtype = d["dtype"]
        name = d["name"].lower()
        if tokenizer is not None:
            ids = tokenizer.encode("a photo of a person").ids
            n = shape[-1] if shape else len(ids)
            ids = (ids[:n] + [0] * max(0, n - len(ids)))
            value = np.asarray(ids, dtype=dtype).reshape(shape)
            source = "tokenizer"
        elif len(shape) == 4:
            value = np.zeros(shape, dtype=dtype)
            source = "zero-image-smoke"
        else:
            value = np.zeros(shape, dtype=dtype)
            source = "zero-smoke"
        interp.set_tensor(d["index"], value)
        result["inputs"].append({
            "name": d["name"], "shape": shape, "dtype": str(dtype), "source": source
        })

    interp.invoke()
    embedding_shapes = []
    for d in outputs:
        out = np.asarray(interp.get_tensor(d["index"]))
        finite = bool(np.isfinite(out.astype(np.float32)).all())
        if len(out.shape) >= 2:
            embedding_shapes.append(int(out.shape[-1]))
        result["outputs"].append({
            "name": d["name"],
            "shape": [int(x) for x in out.shape],
            "dtype": str(out.dtype),
            "finite": finite,
            "min": float(np.min(out)) if out.size else None,
            "max": float(np.max(out)) if out.size else None,
            "l2_norm": float(np.linalg.norm(out.astype(np.float32))) if out.size else None,
        })
        if not finite:
            result["invoke"] = "FAIL"
    result["embedding_dims"] = sorted(set(embedding_shapes))
    return result


def write_json(p: Path, value: Any) -> None:
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--image-model", type=Path, required=True)
    ap.add_argument("--text-model", type=Path, required=True)
    ap.add_argument("--tokenizer", type=Path, required=True)
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
        assets[p.name] = {
            "sha256": actual,
            "expected_sha256": expected,
            "hash_match": expected is None or expected == actual,
        }
        if expected and expected != actual:
            failures.append(f"sha256:{p.name}")

    image_contract = inspect_model(args.image_model)
    text_contract = inspect_model(args.text_model)
    tokenizer_contract = inspect_tokenizer(args.tokenizer)

    contract = {
        "format": "mobileclip-s2-contract-v1",
        "assets": assets,
        "image": image_contract,
        "text": text_contract,
        "tokenizer": tokenizer_contract,
    }
    write_json(args.out / "mobileclip_s2_contract.json", contract)

    tokenizer = Tokenizer.from_file(str(args.tokenizer))
    try:
        image_run = run_model(args.image_model, None, "image_encoder")
        text_run = run_model(args.text_model, tokenizer, "text_encoder")
    except Exception as exc:
        failures.append(f"inference:{type(exc).__name__}:{exc}")
        image_run = {"invoke": "FAIL", "error": repr(exc), "embedding_dims": []}
        text_run = {"invoke": "FAIL", "error": repr(exc), "embedding_dims": []}

    if image_run.get("invoke") != "PASS":
        failures.append("image_inference")
    if text_run.get("invoke") != "PASS":
        failures.append("text_inference")
    compatible = bool(image_run.get("embedding_dims") and text_run.get("embedding_dims") and set(image_run["embedding_dims"]) == set(text_run["embedding_dims"]))
    if not compatible:
        failures.append("embedding_dimension")

    report = {
        "status": "FAIL" if failures else "PASS",
        "failures": failures,
        "assets": assets,
        "image_run": image_run,
        "text_run": text_run,
        "cross_modal_shape_compatibility": compatible,
        "semantic_accuracy_benchmark": "NOT_CLAIMED: deterministic smoke test only",
    }
    write_json(args.out / "mobileclip_s2_deep_audit.json", report)

    lines = [
        "MobileCLIP S2 Deep Audit",
        "========================",
        f"STATUS: {report['status']}",
        "",
    ]
    lines.extend(f"[{'PASS' if x['hash_match'] else 'FAIL'}] {name} SHA-256" for name, x in assets.items())
    lines += [
        f"[{'PASS' if image_run.get('invoke') == 'PASS' else 'FAIL'}] image TFLite invoke",
        f"[{'PASS' if text_run.get('invoke') == 'PASS' else 'FAIL'}] text TFLite invoke with tokenizer",
        f"[{'PASS' if compatible else 'FAIL'}] image/text embedding shape compatibility",
        "",
        "mobileclip_s2_contract.json generated from the actual FlatBuffers",
        "mobileclip_s2_deep_audit.json contains runtime smoke results",
    ]
    (args.out / "SUMMARY.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
