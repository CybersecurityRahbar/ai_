#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any

import numpy as np
import tensorflow as tf
import torch
from PIL import Image
from tokenizers import Tokenizer

import mobileclip


EMBED_DIM = 512
CONTEXT = 77
HF_REVISION = "868dc14eb50de4a8347714b019aae242a0778675"
APPLE_REVISION = "aecfb5453d022e9deff12f81a150ea8f35194baa"
SOT = 49406
EOT = 49407
PAD = 0


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(4 * 1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def flatten(x: Any) -> np.ndarray:
    if isinstance(x, torch.Tensor):
        return x.detach().cpu().float().numpy().reshape(-1)
    return np.asarray(x, dtype=np.float32).reshape(-1)


def normalize(v: Any) -> np.ndarray:
    x = flatten(v)
    n = float(np.linalg.norm(x))
    if not np.isfinite(n) or n <= 1e-12:
        raise ValueError(f"invalid embedding norm: {n}")
    return x / n


def metrics(a: Any, b: Any) -> dict[str, float | int | bool]:
    x = flatten(a)
    y = flatten(b)
    if x.shape != y.shape:
        return {"shape_equal": False, "size_a": int(x.size), "size_b": int(y.size)}
    d = x - y
    n = float(np.linalg.norm(x))
    m = float(np.linalg.norm(y))
    return {
        "shape_equal": True,
        "dimension": int(x.size),
        "finite_a": bool(np.isfinite(x).all()),
        "finite_b": bool(np.isfinite(y).all()),
        "norm_a": n,
        "norm_b": m,
        "cosine": float(np.dot(x, y) / (n * m)) if n > 0 and m > 0 else float("nan"),
        "max_abs_diff": float(np.max(np.abs(d))),
        "mean_abs_diff": float(np.mean(np.abs(d))),
        "rmse": float(math.sqrt(float(np.mean(d * d)))),
        "relative_l2_error": float(np.linalg.norm(d) / max(n, 1e-12)),
        "exact_equal": bool(np.array_equal(x, y)),
        "close_1e-4": bool(np.allclose(x, y, rtol=1e-4, atol=1e-4)),
        "close_1e-3": bool(np.allclose(x, y, rtol=1e-3, atol=1e-3)),
    }


def normalize_third_party_encoding(tok: Tokenizer, text: str) -> tuple[list[int], dict[str, Any]]:
    # Important: tokenizer.json may already contain a PostProcessor that adds
    # SOT/EOT. Never prepend them blindly. First observe the tokenizer's actual
    # `encode()` result with add_special_tokens=True (the default).
    enc = tok.encode(text, add_special_tokens=True)
    raw_ids = [int(x) for x in enc.ids]
    vocab = tok.get_vocab()
    sot = int(vocab.get("<start_of_text>", SOT))
    eot = int(vocab.get("<end_of_text>", EOT))

    starts_with_specials = len(raw_ids) >= 2 and raw_ids[0] == sot and raw_ids[-1] == eot
    if starts_with_specials:
        ids = raw_ids[:CONTEXT]
    else:
        body = raw_ids[: CONTEXT - 2]
        ids = [sot, *body, eot]

    ids = ids[:CONTEXT]
    ids += [PAD] * (CONTEXT - len(ids))
    if len(ids) != CONTEXT:
        raise ValueError(f"third-party token sequence length {len(ids)} != {CONTEXT}")

    return ids, {
        "raw_ids": raw_ids,
        "raw_tokens": list(enc.tokens),
        "special_tokens_were_already_present": starts_with_specials,
        "sot_id": sot,
        "eot_id": eot,
        "tokenizer_added_tokens": list(getattr(enc, "words", [])) if hasattr(enc, "words") else None,
    }


def load_tflite(path: Path) -> tf.lite.Interpreter:
    interp = tf.lite.Interpreter(model_path=str(path), num_threads=4)
    interp.allocate_tensors()
    if len(interp.get_input_details()) != 1 or len(interp.get_output_details()) < 1:
        raise ValueError(f"unexpected tensor count for {path}")
    return interp


def run_tflite_image(path: Path, chw: np.ndarray) -> np.ndarray:
    interp = load_tflite(path)
    inp = interp.get_input_details()[0]
    if inp["dtype"] != np.dtype(np.float32) or list(inp["shape"]) != [1, 3, 256, 256]:
        raise ValueError(f"image tensor contract mismatch: {inp}")
    interp.set_tensor(inp["index"], chw.astype(np.float32, copy=False))
    interp.invoke()
    return np.asarray(interp.get_tensor(interp.get_output_details()[0]["index"]), dtype=np.float32)


def run_tflite_text(path: Path, ids: list[int]) -> np.ndarray:
    interp = load_tflite(path)
    inp = interp.get_input_details()[0]
    if inp["dtype"] != np.dtype(np.int64) or list(inp["shape"]) != [1, 77]:
        raise ValueError(f"text tensor contract mismatch: {inp}")
    interp.set_tensor(inp["index"], np.asarray(ids, dtype=np.int64).reshape(1, 77))
    interp.invoke()
    return np.asarray(interp.get_tensor(interp.get_output_details()[0]["index"]), dtype=np.float32)


def prepare_shared_image(path: Path, preprocess: Any) -> np.ndarray:
    with Image.open(path) as im:
        rgb = im.convert("RGB")
        tensor = preprocess(rgb).unsqueeze(0)
    arr = tensor.detach().cpu().float().numpy()
    return arr


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--tflite-image", type=Path, required=True)
    ap.add_argument("--tflite-text", type=Path, required=True)
    ap.add_argument("--tokenizer", type=Path, required=True)
    ap.add_argument("--semantic-image", type=Path, required=True)
    ap.add_argument("--apple-checkpoint", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    tokenizer = Tokenizer.from_file(str(args.tokenizer))
    model, _, preprocess = mobileclip.create_model_and_transforms(
        "mobileclip_s2", pretrained=str(args.apple_checkpoint), reparameterize=True, device="cpu"
    )
    tokenizer_apple = mobileclip.get_tokenizer("mobileclip_s2")
    model.eval()

    prompts = [
        "a diagram",
        "a dog",
        "a cat",
        "a landscape",
        "a person",
        "a screenshot",
        "a building",
        "two people standing together",
        "a red car",
    ]

    shared_image = prepare_shared_image(args.semantic_image, preprocess)
    with torch.no_grad():
        apple_image = model.encode_image(torch.from_numpy(shared_image), normalize=False).cpu().numpy()

    tflite_image = run_tflite_image(args.tflite_image, shared_image)

    text_rows: list[dict[str, Any]] = []
    apple_text_vectors: dict[str, np.ndarray] = {}
    tflite_text_vectors_third: dict[str, np.ndarray] = {}
    tflite_text_vectors_apple: dict[str, np.ndarray] = {}

    for prompt in prompts:
        apple_tokens_tensor = tokenizer_apple(prompt)
        apple_ids = [int(x) for x in apple_tokens_tensor[0].tolist()]
        third_ids, third_meta = normalize_third_party_encoding(tokenizer, prompt)
        token_equal = apple_ids == third_ids

        with torch.no_grad():
            apple_text = model.encode_text(torch.tensor([apple_ids], dtype=torch.long), normalize=False).cpu().numpy()

        tflite_third = run_tflite_text(args.tflite_text, third_ids)
        tflite_apple = run_tflite_text(args.tflite_text, apple_ids)

        apple_text_vectors[prompt] = apple_text
        tflite_text_vectors_third[prompt] = tflite_third
        tflite_text_vectors_apple[prompt] = tflite_apple

        text_rows.append({
            "prompt": prompt,
            "token_ids_equal": token_equal,
            "apple_token_ids": apple_ids,
            "third_party_token_ids": third_ids,
            "third_party_encoding_meta": third_meta,
            "apple_vs_tflite_third_party_ids": metrics(apple_text, tflite_third),
            "apple_vs_tflite_apple_ids": metrics(apple_text, tflite_apple),
            "third_party_vs_apple_input_tflite": metrics(tflite_third, tflite_apple),
        })

    image_cos = float(np.dot(normalize(apple_image), normalize(tflite_image)))

    apple_image_n = normalize(apple_image)
    tflite_image_n = normalize(tflite_image)

    apple_scores = {p: float(np.dot(apple_image_n, normalize(v))) for p, v in apple_text_vectors.items()}
    tflite_scores_third = {p: float(np.dot(tflite_image_n, normalize(v))) for p, v in tflite_text_vectors_third.items()}
    tflite_scores_apple = {p: float(np.dot(tflite_image_n, normalize(v))) for p, v in tflite_text_vectors_apple.items()}

    apple_rank = sorted(apple_scores.items(), key=lambda x: x[1], reverse=True)
    tflite_rank_third = sorted(tflite_scores_third.items(), key=lambda x: x[1], reverse=True)
    tflite_rank_apple = sorted(tflite_scores_apple.items(), key=lambda x: x[1], reverse=True)

    result = {
        "format": "mobileclip-s2-deep-oracle-v2",
        "provenance": {
            "apple_repo_commit": APPLE_REVISION,
            "apple_checkpoint": str(args.apple_checkpoint),
            "hf_tflite_revision": HF_REVISION,
            "semantic_image": str(args.semantic_image),
            "tflite_image_sha256": sha256(args.tflite_image),
            "tflite_text_sha256": sha256(args.tflite_text),
            "tokenizer_sha256": sha256(args.tokenizer),
            "apple_checkpoint_sha256": sha256(args.apple_checkpoint),
        },
        "tokenizer_contract": {
            "third_party_vocab_size": len(tokenizer.get_vocab()),
            "third_party_post_processor": repr(tokenizer.post_processor),
            "third_party_added_tokens": [
                {"id": int(t.id), "content": t.content, "special": bool(t.special)}
                for t in tokenizer.get_added_tokens_decoder().values()
            ],
            "apple_special_token_ids": {"sot": SOT, "eot": EOT},
        },
        "shared_input": {
            "image_tensor_shape": list(shared_image.shape),
            "image_tensor_dtype": str(shared_image.dtype),
            "image_min": float(shared_image.min()),
            "image_max": float(shared_image.max()),
        },
        "image_embedding": {
            "apple_vs_tflite": metrics(apple_image, tflite_image),
            "normalized_cosine": image_cos,
        },
        "text_comparisons": text_rows,
        "cross_modal": {
            "apple_ranking": apple_rank,
            "tflite_ranking_using_third_party_tokenizer": tflite_rank_third,
            "tflite_ranking_using_apple_tokenizer_ids": tflite_rank_apple,
            "apple_top1": apple_rank[0][0],
            "tflite_top1_third_party": tflite_rank_third[0][0],
            "tflite_top1_apple_ids": tflite_rank_apple[0][0],
            "ranking_same_as_apple_third_party_ids": [p for p, _ in apple_rank] == [p for p, _ in tflite_rank_third],
            "ranking_same_as_apple_apple_ids": [p for p, _ in apple_rank] == [p for p, _ in tflite_rank_apple],
        },
    }

    write = args.out / "mobileclip_s2_oracle_report.json"
    write.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    lines = [
        "MobileCLIP S2 Deep Oracle Audit V2",
        "===================================",
        f"Apple commit: {APPLE_REVISION}",
        f"TFLite package revision: {HF_REVISION}",
        f"Image normalized cosine (Apple vs TFLite): {image_cos:.9f}",
        f"Apple top-1 prompt: {apple_rank[0][0]}",
        f"TFLite top-1 (third-party tokenizer): {tflite_rank_third[0][0]}",
        f"TFLite top-1 (Apple token IDs): {tflite_rank_apple[0][0]}",
        "",
    ]
    for row in text_rows:
        m1 = row["apple_vs_tflite_third_party_ids"]
        m2 = row["apple_vs_tflite_apple_ids"]
        lines.append(
            f"{row['prompt']!r}: ids_equal={row['token_ids_equal']} "
            f"specials_already_present={row['third_party_encoding_meta']['special_tokens_were_already_present']} "
            f"third_cos={m1.get('cosine', float('nan')):.9f} third_max_abs={m1.get('max_abs_diff', float('nan')):.6g} "
            f"apple_ids_cos={m2.get('cosine', float('nan')):.9f} apple_ids_max_abs={m2.get('max_abs_diff', float('nan')):.6g}"
        )
    (args.out / "SUMMARY.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
