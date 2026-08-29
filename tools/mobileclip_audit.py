#!/usr/bin/env python3
import argparse, hashlib, json, os, struct
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def audit_tflite(path: Path) -> dict:
    result = {
        'path': str(path),
        'size_bytes': path.stat().st_size,
        'sha256': sha256(path),
        'parser': 'not-run',
    }
    try:
        import tflite  # type: ignore
        with path.open('rb') as f:
            buf = f.read()
        model = tflite.Model.GetRootAsModel(buf, 0)
        result['parser'] = 'python-tflite-schema'
        result['version'] = model.Version()
        result['subgraphs'] = model.SubgraphsLength()
        result['buffers'] = model.BuffersLength()
        result['operators_codes'] = model.OperatorCodesLength()
        graphs = []
        for gi in range(model.SubgraphsLength()):
            g = model.Subgraphs(gi)
            inputs = []
            for i in range(g.InputsLength()):
                inputs.append(g.Inputs(i))
            outputs = []
            for i in range(g.OutputsLength()):
                outputs.append(g.Outputs(i))
            tensors = []
            for ti in range(g.TensorsLength()):
                t = g.Tensors(ti)
                shape = [t.Shape(si) for si in range(t.ShapeLength())]
                name = t.Name().decode('utf-8', 'replace') if t.Name() else ''
                tensors.append({'index': ti, 'name': name, 'shape': shape, 'type': int(t.Type()), 'buffer': t.Buffer()})
            graphs.append({'index': gi, 'inputs': inputs, 'outputs': outputs, 'tensors': tensors})
        result['graphs'] = graphs
    except Exception as exc:
        result['parser_error'] = repr(exc)
    return result


def audit_tokenizer(path: Path) -> dict:
    raw = json.loads(path.read_text(encoding='utf-8'))
    model = raw.get('model') or {}
    vocab = model.get('vocab') or {}
    merges = model.get('merges') or []
    added = raw.get('added_tokens') or []
    post = raw.get('post_processor') or {}
    trunc = raw.get('truncation') or {}
    padding = raw.get('padding') or {}
    return {
        'path': str(path),
        'size_bytes': path.stat().st_size,
        'sha256': sha256(path),
        'tokenizer_class': raw.get('tokenizer_class'),
        'model_type': model.get('type'),
        'vocab_size': len(vocab),
        'merge_count': len(merges),
        'added_tokens': [
            {'id': x.get('id'), 'content': x.get('content'), 'special': x.get('special')}
            for x in added
        ],
        'post_processor_type': post.get('type'),
        'post_processor': post,
        'truncation': trunc,
        'padding': padding,
        'unk_token': raw.get('unk_token'),
        'bos_token': raw.get('bos_token'),
        'eos_token': raw.get('eos_token'),
        'pad_token': raw.get('pad_token'),
        'model_keys': sorted(model.keys()),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--image', required=True)
    ap.add_argument('--text', required=True)
    ap.add_argument('--tokenizer', required=True)
    ap.add_argument('--out', required=True)
    args = ap.parse_args()
    report = {
        'image': audit_tflite(Path(args.image)),
        'text': audit_tflite(Path(args.text)),
        'tokenizer': audit_tokenizer(Path(args.tokenizer)),
    }
    Path(args.out).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == '__main__':
    main()
