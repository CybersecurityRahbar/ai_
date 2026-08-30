#!/usr/bin/env python3
"""MobileCLIP-S2 tokenizer differential audit.

Compares Apple MobileCLIP/OpenCLIP token IDs with the checked-in third-party
`tokenizer.json` on a deterministic corpus. This intentionally does not
compare embeddings; it locates the first tokenization divergence.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from tokenizers import Tokenizer

SOT_FALLBACK = 49406
EOT_FALLBACK = 49407
CONTEXT = 77

TEXTS = [
    "a diagram",
    "a dog",
    "a cat",
    "a landscape",
    "a person",
    "a screenshot",
    "a building",
    "two people standing together",
    "a red car",
    "",
    "hello world",
    "A PHOTO OF A PERSON",
    "A photo of a person, smiling.",
    "person wearing glasses",
    "two people in a room",
    "Arabic العربية",
    "1234567890",
    "symbols !@#$%^&*()",
    "a,b.c:d;e/f?g!",
]


def load_openclip(model_name: str):
    import open_clip
    return open_clip.get_tokenizer(model_name)


def apple_ids(tokenizer: Any, text: str) -> list[int]:
    x = tokenizer([text], context_length=CONTEXT)
    if hasattr(x, "tolist"):
        return [int(v) for v in x[0].tolist()]
    return [int(v) for v in x[0]]


def tokenizers_ids(tok: Tokenizer, text: str) -> list[int]:
    enc = tok.encode(text)
    ids = list(enc.ids)
    vocab = tok.get_vocab()
    sot = int(vocab.get("<start_of_text>", SOT_FALLBACK))
    eot = int(vocab.get("<end_of_text>", EOT_FALLBACK))
    body = ids[: CONTEXT - 2]
    out = [sot] + body + [eot]
    return out + [0] * (CONTEXT - len(out))


def first_diff(a: list[int], b: list[int]) -> int | None:
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return i
    return None if len(a) == len(b) else min(len(a), len(b))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--third-party-tokenizer", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--apple-model", default="ViT-B-16")
    args = ap.parse_args()

    third = Tokenizer.from_file(str(args.third_party_tokenizer))
    apple = load_openclip(args.apple_model)

    report: dict[str, Any] = {
        "format": "mobileclip-s2-tokenizer-differential-v1",
        "context_length": CONTEXT,
        "apple_tokenizer": args.apple_model,
        "third_party_tokenizer": str(args.third_party_tokenizer),
        "cases": [],
    }
    divergent = 0
    for text in TEXTS:
        a = apple_ids(apple, text)
        b = tokenizers_ids(third, text)
        d = first_diff(a, b)
        case = {"text": text, "apple_ids": a, "third_party_ids": b, "ids_equal": d is None}
        if d is not None:
            divergent += 1
            case["first_difference_index"] = d
            case["apple_id_at_first_difference"] = a[d]
            case["third_party_id_at_first_difference"] = b[d]
        report["cases"].append(case)

    report["summary"] = {
        "total_cases": len(TEXTS),
        "divergent_cases": divergent,
        "identical_cases": len(TEXTS) - divergent,
        "all_equal": divergent == 0,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("MobileCLIP S2 Tokenizer Differential Audit V1")
    print("==============================================")
    print(f"Cases: {len(TEXTS)}")
    print(f"Divergent: {divergent}")
    for case in report["cases"]:
        if case["ids_equal"]:
            print(f"PASS same IDs: {case['text']!r}")
        else:
            print(
                f"DIFF {case['text']!r}: first index={case['first_difference_index']} "
                f"apple={case['apple_id_at_first_difference']} "
                f"third={case['third_party_id_at_first_difference']}"
            )

    return 0 if divergent == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
