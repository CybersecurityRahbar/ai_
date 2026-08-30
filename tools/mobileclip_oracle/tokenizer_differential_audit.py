#!/usr/bin/env python3
"""MobileCLIP-S2 tokenizer differential audit.

Compares the official Apple/OpenCLIP CLIP tokenizer with the exact
third-party `tokenizer.json` on a deterministic corpus. The audit respects
any post-processor already present in the tokenizer JSON and reports the
first token-ID divergence, including special-token handling.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from tokenizers import Tokenizer

CONTEXT = 77
SOT_FALLBACK = 49406
EOT_FALLBACK = 49407
PAD = 0

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
    return [int(v) for v in x[0].tolist()]


def third_party_ids(tok: Tokenizer, text: str) -> tuple[list[int], dict[str, Any]]:
    enc = tok.encode(text, add_special_tokens=True)
    raw_ids = [int(v) for v in enc.ids]
    raw_tokens = list(enc.tokens)
    vocab = tok.get_vocab()
    sot = int(vocab.get("<start_of_text>", SOT_FALLBACK))
    eot = int(vocab.get("<end_of_text>", EOT_FALLBACK))

    # The previous audit manually added SOT/EOT unconditionally. That can
    # double-wrap a tokenizer JSON whose post-processor already adds them.
    has_special_wrapper = (
        len(raw_ids) >= 2 and raw_ids[0] == sot and raw_ids[-1] == eot
    )
    if has_special_wrapper:
        ids = raw_ids[:CONTEXT]
    else:
        ids = [sot, *raw_ids[: CONTEXT - 2], eot]

    ids = (ids + [PAD] * CONTEXT)[:CONTEXT]
    return ids, {
        "raw_ids": raw_ids,
        "raw_tokens": raw_tokens,
        "special_wrapper_already_present": has_special_wrapper,
        "sot_id": sot,
        "eot_id": eot,
        "post_processor": repr(tok.post_processor),
    }


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

    cases: list[dict[str, Any]] = []
    divergent = 0
    for text in TEXTS:
        a = apple_ids(apple, text)
        b, meta = third_party_ids(third, text)
        d = first_diff(a, b)
        case: dict[str, Any] = {
            "text": text,
            "apple_ids": a,
            "third_party_ids": b,
            "ids_equal": d is None,
            "third_party_encoding": meta,
        }
        if d is not None:
            divergent += 1
            case.update({
                "first_difference_index": d,
                "apple_id_at_first_difference": a[d],
                "third_party_id_at_first_difference": b[d],
            })
        cases.append(case)

    report = {
        "format": "mobileclip-s2-tokenizer-differential-v2",
        "context_length": CONTEXT,
        "apple_tokenizer": args.apple_model,
        "third_party_tokenizer": str(args.third_party_tokenizer),
        "third_party_contract": {
            "vocab_size": len(third.get_vocab()),
            "post_processor": repr(third.post_processor),
            "added_tokens": [
                {"id": int(t.id), "content": t.content, "special": bool(t.special)}
                for t in third.get_added_tokens_decoder().values()
            ],
        },
        "summary": {
            "total_cases": len(TEXTS),
            "divergent_cases": divergent,
            "identical_cases": len(TEXTS) - divergent,
            "all_equal": divergent == 0,
        },
        "cases": cases,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("MobileCLIP S2 Tokenizer Differential Audit V2")
    print("==============================================")
    print(f"Cases: {len(TEXTS)}")
    print(f"Divergent: {divergent}")
    for case in cases:
        if case["ids_equal"]:
            print(f"PASS same IDs: {case['text']!r}")
        else:
            print(
                f"DIFF {case['text']!r}: first index={case['first_difference_index']} "
                f"apple={case['apple_id_at_first_difference']} "
                f"third={case['third_party_id_at_first_difference']} "
                f"specials_already_present={case['third_party_encoding']['special_wrapper_already_present']}"
            )
    return 0 if divergent == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
