#!/usr/bin/env python3
"""MobileCLIP-S2 tokenizer differential audit.

Compares the exact tokenizer used by Apple's pinned MobileCLIP-S2 reference
against the bundled third-party tokenizer.json. The audit distinguishes:
- tokenization/BPE divergence,
- vocabulary-ID remapping,
- special-token/post-processor differences,
- truncation/padding differences.
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
    "a diagram", "a dog", "a cat", "a landscape", "a person",
    "a screenshot", "a building", "two people standing together", "a red car", "",
    "hello world", "A PHOTO OF A PERSON", "A photo of a person, smiling.",
    "person wearing glasses", "two people in a room", "Arabic العربية", "1234567890",
    "symbols !@#$%^&*()", "a,b.c:d;e/f?g!",
]


def load_apple_mobileclip_tokenizer():
    import mobileclip
    return mobileclip.get_tokenizer("mobileclip_s2")


def apple_ids(tokenizer: Any, text: str) -> list[int]:
    x = tokenizer([text], context_length=CONTEXT)
    return [int(v) for v in x[0].tolist()]


def apple_encoder(tokenizer: Any) -> dict[str, int]:
    return {str(k): int(v) for k, v in tokenizer.encoder.items()}


def third_party_ids(tok: Tokenizer, text: str) -> tuple[list[int], dict[str, Any]]:
    enc = tok.encode(text, add_special_tokens=True)
    raw_ids = [int(v) for v in enc.ids]
    raw_tokens = list(enc.tokens)
    vocab = {str(k): int(v) for k, v in tok.get_vocab().items()}
    sot = int(vocab.get("<start_of_text>", SOT_FALLBACK))
    eot = int(vocab.get("<end_of_text>", EOT_FALLBACK))
    has_special_wrapper = len(raw_ids) >= 2 and raw_ids[0] == sot and raw_ids[-1] == eot
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


def tokenizer_json_contract(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    model = raw.get("model") or {}
    normalizer = raw.get("normalizer")
    pre_tokenizer = raw.get("pre_tokenizer")
    post_processor = raw.get("post_processor")
    decoder = raw.get("decoder")
    added = raw.get("added_tokens") or []
    vocab = model.get("vocab") or {}
    merges = model.get("merges") or []
    return {
        "root_keys": sorted(raw.keys()),
        "model_type": model.get("type"),
        "model_vocab_size": len(vocab),
        "model_vocab_sample": list(vocab.items())[:8],
        "merges_count": len(merges),
        "merges_sample": merges[:8],
        "normalizer": normalizer,
        "pre_tokenizer": pre_tokenizer,
        "post_processor": post_processor,
        "decoder": decoder,
        "added_tokens": added,
        "added_tokens_count": len(added),
    }


def compare_vocab(apple: dict[str, int], third: dict[str, int]) -> dict[str, Any]:
    ak = set(apple)
    tk = set(third)
    common = ak & tk
    same_mapping = sum(1 for k in common if apple[k] == third[k])
    remapped = sum(1 for k in common if apple[k] != third[k])
    return {
        "apple_vocab_size": len(apple),
        "third_vocab_size": len(third),
        "common_token_strings": len(common),
        "same_token_to_id_mapping": same_mapping,
        "same_token_strings_but_different_ids": remapped,
        "apple_only_token_strings": len(ak - tk),
        "third_only_token_strings": len(tk - ak),
        "mapping_is_identical": same_mapping == len(common) and ak == tk,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--third-party-tokenizer", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    third = Tokenizer.from_file(str(args.third_party_tokenizer))
    apple_wrapper = load_apple_mobileclip_tokenizer()
    # Apple's ClipTokenizer exposes the OpenCLIP SimpleTokenizer as .tokenizer.
    apple = getattr(apple_wrapper, "tokenizer", apple_wrapper)
    apple_vocab = apple_encoder(apple)
    third_vocab = {str(k): int(v) for k, v in third.get_vocab().items()}

    cases: list[dict[str, Any]] = []
    divergent = 0
    token_string_aligned = 0
    for text in TEXTS:
        a = apple_ids(apple_wrapper, text)
        b, meta = third_party_ids(third, text)
        d = first_diff(a, b)
        # Compare the human-readable OpenCLIP token strings when possible.
        case: dict[str, Any] = {
            "text": text,
            "apple_ids": a,
            "third_party_ids": b,
            "ids_equal": d is None,
            "third_party_encoding": meta,
        }
        if d is not None:
            divergent += 1
            case["first_difference_index"] = d
            case["apple_id_at_first_difference"] = a[d]
            case["third_party_id_at_first_difference"] = b[d]
            apple_token_for_id = getattr(apple, "decoder", {}).get(a[d])
            third_token_for_id = next((k for k, v in third_vocab.items() if v == b[d]), None)
            case["apple_token_at_first_difference"] = apple_token_for_id
            case["third_party_token_at_first_difference"] = third_token_for_id
            if apple_token_for_id is not None and apple_token_for_id == third_token_for_id:
                token_string_aligned += 1
                case["token_string_same_but_id_differs"] = True
            else:
                case["token_string_same_but_id_differs"] = False
        cases.append(case)

    contract = tokenizer_json_contract(args.third_party_tokenizer)
    report = {
        "format": "mobileclip-s2-tokenizer-differential-v4",
        "reference": "Apple mobileclip.get_tokenizer(\"mobileclip_s2\") -> OpenCLIP tokenizer",
        "context_length": CONTEXT,
        "third_party_contract": contract,
        "vocabulary_comparison": compare_vocab(apple_vocab, third_vocab),
        "summary": {
            "total_cases": len(TEXTS),
            "divergent_cases": divergent,
            "identical_cases": len(TEXTS) - divergent,
            "all_equal": divergent == 0,
            "divergences_with_same_token_string_but_different_id": token_string_aligned,
        },
        "cases": cases,
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print("MobileCLIP S2 Tokenizer Differential Audit V4")
    print("==============================================")
    print("Reference: Apple mobileclip.get_tokenizer(\"mobileclip_s2\") -> OpenCLIP")
    print(f"Cases: {len(TEXTS)}")
    print(f"Divergent: {divergent}")
    print(f"Same token string but different ID at first divergence: {token_string_aligned}")
    vc = report["vocabulary_comparison"]
    print(f"Apple vocab: {vc['apple_vocab_size']}  Third-party vocab: {vc['third_vocab_size']}")
    print(f"Common token strings: {vc['common_token_strings']}")
    print(f"Same token->ID mappings: {vc['same_token_to_id_mapping']}")
    print(f"Remapped common tokens: {vc['same_token_strings_but_different_ids']}")
    print(f"Apple-only tokens: {vc['apple_only_token_strings']}  Third-only tokens: {vc['third_only_token_strings']}")
    print(f"Third-party model type: {contract['model_type']}")
    print(f"Third-party merges: {contract['merges_count']}")
    for case in cases:
        if not case["ids_equal"]:
            print(
                f"DIFF {case['text']!r}: index={case['first_difference_index']} "
                f"apple_id={case['apple_id_at_first_difference']} "
                f"third_id={case['third_party_id_at_first_difference']} "
                f"apple_tok={case.get('apple_token_at_first_difference')!r} "
                f"third_tok={case.get('third_party_token_at_first_difference')!r}"
            )
    return 0 if divergent == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
