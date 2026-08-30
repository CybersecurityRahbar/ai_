#!/usr/bin/env python3
"""MobileCLIP-S2 tokenizer differential audit.

Three-way audit:
  1) Apple MobileCLIP-S2 runtime tokenizer (authoritative behavior)
  2) third-party tokenizer.json from plainhub/mobileclip-s2-tflite
  3) optional official Apple OpenCLIP tokenizer.json

The audit separates vocabulary identity from tokenization behavior and keeps
its JSON report even when the comparison fails the parity gate.
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
        if hasattr(value, "encoder"):
            return value
    if isinstance(globals_dict, dict):
        for value in globals_dict.values():
            if hasattr(value, "encoder"):
                return value
    raise TypeError("Could not resolve Apple/OpenCLIP tokenizer to a vocabulary-bearing tokenizer")


def apple_vocab_and_decoder(wrapper: Any) -> tuple[dict[str, int], dict[int, str]]:
    tok = resolve_openclip_tokenizer(wrapper)
    return (
        {str(k): int(v) for k, v in tok.encoder.items()},
        {int(k): str(v) for k, v in tok.decoder.items()},
    )


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
        "pre_tokenizer": repr(tok.pre_tokenizer),
        "decoder": repr(tok.decoder),
        "model": repr(tok.model),
    }


def first_diff(a: list[int], b: list[int]) -> int | None:
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return i
    return None if len(a) == len(b) else min(len(a), len(b))


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


def compare_json_tokenizer(path: Path, apple_wrapper: Any, label: str) -> dict[str, Any]:
    tok = Tokenizer.from_file(str(path))
    out: dict[str, Any] = {"label": label, "path": str(path), "contract": tokenizer_json_contract(path), "cases": []}
    divergences = 0
    for text in TEXTS:
        a = apple_ids(apple_wrapper, text)
        enc = tok.encode(text, add_special_tokens=True)
        raw = [int(v) for v in enc.ids]
        third_sot = int(tok.get_vocab().get("<start_of_text>", SOT_FALLBACK))
        third_eot = int(tok.get_vocab().get("<end_of_text>", EOT_FALLBACK))
        if len(raw) >= 2 and raw[0] == third_sot and raw[-1] == third_eot:
            b = raw[:CONTEXT]
        else:
            b = [third_sot, *raw[: CONTEXT - 2], third_eot]
        b = (b + [PAD] * CONTEXT)[:CONTEXT]
        d = first_diff(a, b)
        case = {"text": text, "apple_ids": a, "json_ids": b, "ids_equal": d is None}
        if d is not None:
            divergences += 1
            case.update({
                "first_difference_index": d,
                "apple_id_at_first_difference": a[d],
                "json_id_at_first_difference": b[d],
                "apple_token_at_first_difference": apple_decoder_global.get(a[d]),
                "json_token_at_first_difference": next((k for k, v in tok.get_vocab().items() if int(v) == b[d]), None),
            })
        out["cases"].append(case)
    out["divergent_cases"] = divergences
    out["identical_cases"] = len(TEXTS) - divergences
    return out


apple_decoder_global: dict[int, str] = {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--third-party-tokenizer", type=Path, required=True)
    ap.add_argument("--official-apple-tokenizer", type=Path, default=None)
    ap.add_argument("--out", type=Path, required=True)
    args = ap.parse_args()

    global apple_decoder_global
    third = Tokenizer.from_file(str(args.third_party_tokenizer))
    apple_wrapper = load_apple_mobileclip_tokenizer()
    apple_vocab, apple_decoder_global = apple_vocab_and_decoder(apple_wrapper)
    third_vocab = {str(k): int(v) for k, v in third.get_vocab().items()}

    report: dict[str, Any] = {
        "format": "mobileclip-s2-tokenizer-differential-v6",
        "reference": "Apple mobileclip.get_tokenizer(\"mobileclip_s2\") -> OpenCLIP tokenizer",
        "context_length": CONTEXT,
        "third_party_contract": tokenizer_json_contract(args.third_party_tokenizer),
        "vocabulary_comparison": compare_vocab(apple_vocab, third_vocab),
        "third_party": compare_json_tokenizer(args.third_party_tokenizer, apple_wrapper, "plainhub"),
    }
    if args.official_apple_tokenizer:
        report["official_apple_json"] = compare_json_tokenizer(args.official_apple_tokenizer, apple_wrapper, "apple/MobileCLIP-S2-OpenCLIP")

    report["summary"] = {
        "third_party_ids_all_equal": report["third_party"]["divergent_cases"] == 0,
        "official_apple_json_all_equal": report.get("official_apple_json", {}).get("divergent_cases") == 0
        if args.official_apple_tokenizer else None,
        "vocabulary_mapping_identical": report["vocabulary_comparison"]["mapping_is_identical"],
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    vc = report["vocabulary_comparison"]
    print("MobileCLIP S2 Tokenizer Differential Audit V6")
    print("==============================================")
    print("Reference: Apple mobileclip.get_tokenizer(\"mobileclip_s2\") -> OpenCLIP")
    print(f"Third-party divergent cases: {report['third_party']['divergent_cases']} / {len(TEXTS)}")
    print(f"Apple vocab: {vc['apple_vocab_size']}  Third-party vocab: {vc['third_vocab_size']}")
    print(f"Common token strings: {vc['common_token_strings']}")
    print(f"Same token->ID mappings: {vc['same_token_to_id_mapping']}")
    print(f"Remapped common tokens: {vc['same_token_strings_but_different_ids']}")
    print(f"Apple-only tokens: {vc['apple_only_token_strings']}  Third-only tokens: {vc['third_only_token_strings']}")
    print(f"Third-party tokenizer model: {report['third_party_contract']['model_type']}")
    print(f"Third-party merges: {report['third_party_contract']['merges_count']}")
    for case in report["third_party"]["cases"]:
        if not case["ids_equal"]:
            print(
                f"DIFF {case['text']!r}: index={case['first_difference_index']} "
                f"apple_id={case['apple_id_at_first_difference']} "
                f"json_id={case['json_id_at_first_difference']} "
                f"apple_tok={case['apple_token_at_first_difference']!r} "
                f"json_tok={case['json_token_at_first_difference']!r}"
            )
    # Preserve the diagnostic report even when parity fails.
    if report["third_party"]["divergent_cases"] > 0:
        return 1
    if args.official_apple_tokenizer and report["official_apple_json"]["divergent_cases"] > 0:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
