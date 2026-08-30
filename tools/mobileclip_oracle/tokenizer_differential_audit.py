#!/usr/bin/env python3
"""MobileCLIP-S2 tokenizer differential audit.

Three-way audit:
  1) Apple MobileCLIP-S2 runtime tokenizer (authoritative behavior)
  2) third-party tokenizer.json from plainhub/mobileclip-s2-tflite
  3) official Apple MobileCLIP-S2/OpenCLIP tokenizer.json

The audit separates vocabulary identity from tokenization behavior. A
third-party divergence is a diagnostic finding, not a tool crash, when the
official Apple JSON is proven identical to the Apple runtime.
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
    "a diagram", "a dog", "a cat", "a landscape", "a person", "a screenshot",
    "a building", "two people standing together", "a red car", "", "hello world",
    "A PHOTO OF A PERSON", "A photo of a person, smiling.", "person wearing glasses",
    "two people in a room", "Arabic العربية", "1234567890", "symbols !@#$%^&*()",
    "a,b.c:d;e/f?g!",
]


def load_apple_mobileclip_tokenizer():
    import mobileclip
    return mobileclip.get_tokenizer("mobileclip_s2")


def apple_ids(tokenizer: Any, text: str) -> list[int]:
    x = tokenizer([text], context_length=CONTEXT)
    return [int(v) for v in x[0].tolist()]


def resolve_openclip_tokenizer(wrapper: Any) -> Any:
    candidate = getattr(wrapper, "tokenizer", None)
    if candidate is not None and hasattr(candidate, "encoder"):
        return candidate
    try:
        import open_clip.tokenizer as tokenizer_module
        candidate = getattr(tokenizer_module, "_tokenizer", None)
        if candidate is not None and hasattr(candidate, "encoder"):
            return candidate
    except Exception:
        pass
    globals_dict = getattr(wrapper, "__globals__", None)
    for cell in getattr(wrapper, "__closure__", None) or ():
        try:
            value = cell.cell_contents
        except ValueError:
            continue
        if hasattr(value, "encoder") and hasattr(value, "decoder"):
            return value
    if isinstance(globals_dict, dict):
        for value in globals_dict.values():
            if hasattr(value, "encoder") and hasattr(value, "decoder"):
                return value
    raise TypeError("Could not resolve Apple/OpenCLIP tokenizer to a vocabulary-bearing tokenizer")


def apple_vocab_and_decoder(wrapper: Any) -> tuple[dict[str, int], dict[int, str]]:
    tok = resolve_openclip_tokenizer(wrapper)
    return (
        {str(k): int(v) for k, v in tok.encoder.items()},
        {int(k): str(v) for k, v in tok.decoder.items()},
    )


def first_diff(a: list[int], b: list[int]) -> int | None:
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return i
    return None if len(a) == len(b) else min(len(a), len(b))


def normalize_json_ids(tok: Tokenizer, text: str) -> tuple[list[int], dict[str, Any]]:
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
        "pre_tokenizer": repr(tok.pre_tokenizer),
        "decoder": repr(tok.decoder),
        "model": repr(tok.model),
    }


def tokenizer_json_contract(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    model = raw.get("model") or {}
    return {
        "root_keys": sorted(raw.keys()),
        "model_type": model.get("type"),
        "model_vocab_size": len(model.get("vocab") or {}),
        "merges_count": len(model.get("merges") or []),
        "normalizer": raw.get("normalizer"),
        "pre_tokenizer": raw.get("pre_tokenizer"),
        "post_processor": raw.get("post_processor"),
        "decoder": raw.get("decoder"),
        "added_tokens": raw.get("added_tokens") or [],
        "added_tokens_count": len(raw.get("added_tokens") or []),
    }


def compare_vocab(apple: dict[str, int], third: dict[str, int]) -> dict[str, Any]:
    ak, tk = set(apple), set(third)
    common = ak & tk
    same = sum(1 for k in common if apple[k] == third[k])
    remapped = len(common) - same
    return {
        "apple_vocab_size": len(apple),
        "third_vocab_size": len(third),
        "common_token_strings": len(common),
        "same_token_to_id_mapping": same,
        "same_token_strings_but_different_ids": remapped,
        "apple_only_token_strings": len(ak - tk),
        "third_only_token_strings": len(tk - ak),
        "mapping_is_identical": same == len(common) and ak == tk,
    }


def compare_json_tokenizer(
    path: Path, apple_wrapper: Any, apple_decoder: dict[int, str], label: str
) -> dict[str, Any]:
    tok = Tokenizer.from_file(str(path))
    decoder = {int(v): str(k) for k, v in tok.get_vocab().items()}
    out: dict[str, Any] = {
        "label": label,
        "path": str(path),
        "contract": tokenizer_json_contract(path),
        "cases": [],
    }
    divergences = 0
    for text in TEXTS:
        a = apple_ids(apple_wrapper, text)
        b, meta = normalize_json_ids(tok, text)
        d = first_diff(a, b)
        case: dict[str, Any] = {
            "text": text,
            "apple_ids": a,
            "json_ids": b,
            "ids_equal": d is None,
            "json_encoding": meta,
        }
        if d is not None:
            divergences += 1
            at = apple_decoder.get(a[d])
            jt = decoder.get(b[d])
            case.update({
                "first_difference_index": d,
                "apple_id_at_first_difference": a[d],
                "json_id_at_first_difference": b[d],
                "apple_token_at_first_difference": at,
                "json_token_at_first_difference": jt,
                "token_string_same_but_id_differs": at is not None and at == jt,
            })
        out["cases"].append(case)
    out["divergent_cases"] = divergences
    out["identical_cases"] = len(TEXTS) - divergences
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--third-party-tokenizer", type=Path, required=True)
    ap.add_argument("--official-apple-tokenizer", type=Path, default=None)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    third = Tokenizer.from_file(str(args.third_party_tokenizer))
    apple_wrapper = load_apple_mobileclip_tokenizer()
    apple_vocab, apple_decoder = apple_vocab_and_decoder(apple_wrapper)
    third_vocab = {str(k): int(v) for k, v in third.get_vocab().items()}

    report: dict[str, Any] = {
        "format": "mobileclip-s2-tokenizer-differential-v7",
        "reference": "Apple mobileclip.get_tokenizer(\"mobileclip_s2\") -> OpenCLIP tokenizer",
        "context_length": CONTEXT,
        "third_party_contract": tokenizer_json_contract(args.third_party_tokenizer),
        "vocabulary_comparison": compare_vocab(apple_vocab, third_vocab),
        "third_party": compare_json_tokenizer(
            args.third_party_tokenizer, apple_wrapper, apple_decoder, "plainhub"
        ),
    }

    if args.official_apple_tokenizer:
        report["official_apple_json"] = compare_json_tokenizer(
            args.official_apple_tokenizer,
            apple_wrapper,
            apple_decoder,
            "apple/MobileCLIP-S2-OpenCLIP",
        )

    third_divergences = report["third_party"]["divergent_cases"]
    official_present = args.official_apple_tokenizer is not None
    official_divergences = report.get("official_apple_json", {}).get("divergent_cases")
    report["summary"] = {
        "third_party_ids_all_equal": third_divergences == 0,
        "official_apple_json_present": official_present,
        "official_apple_json_all_equal": official_divergences == 0 if official_present else None,
        "vocabulary_mapping_identical": report["vocabulary_comparison"]["mapping_is_identical"],
        "production_tokenizer_verdict": (
            "USE_OFFICIAL_APPLE_JSON_OR_FAITHFUL_OPENCLIP_IMPLEMENTATION"
            if official_present and official_divergences == 0
            else "UNRESOLVED"
        ),
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    vc = report["vocabulary_comparison"]
    print("MobileCLIP S2 Tokenizer Differential Audit V7")
    print("==============================================")
    print("Reference: Apple mobileclip.get_tokenizer(\"mobileclip_s2\") -> OpenCLIP")
    print(f"Third-party divergent cases: {third_divergences} / {len(TEXTS)}")
    print(f"Apple vocab: {vc['apple_vocab_size']}  Third-party vocab: {vc['third_vocab_size']}")
    print(f"Common token strings: {vc['common_token_strings']}")
    print(f"Same token->ID mappings: {vc['same_token_to_id_mapping']}")
    print(f"Remapped common tokens: {vc['same_token_strings_but_different_ids']}")
    print(f"Apple-only tokens: {vc['apple_only_token_strings']}  Third-only tokens: {vc['third_only_token_strings']}")
    if official_present:
        print(f"Official Apple JSON divergent cases: {official_divergences} / {len(TEXTS)}")
    else:
        print("Official Apple JSON: NOT PROVIDED")
    print(f"Production tokenizer verdict: {report['summary']['production_tokenizer_verdict']}")

    for label in ("third_party", "official_apple_json"):
        if label not in report:
            continue
        for case in report[label]["cases"]:
            if not case["ids_equal"]:
                print(
                    f"DIFF[{report[label]['label']}] {case['text']!r}: index={case['first_difference_index']} "
                    f"apple_id={case['apple_id_at_first_difference']} "
                    f"json_id={case['json_id_at_first_difference']} "
                    f"apple_tok={case['apple_token_at_first_difference']!r} "
                    f"json_tok={case['json_token_at_first_difference']!r}"
                )

    # A third-party mismatch is an expected diagnostic finding when the
    # official Apple serialized tokenizer matches the authoritative runtime.
    if official_present:
        return 0 if official_divergences == 0 else 1
    return 0 if third_divergences == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
