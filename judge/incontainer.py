#!/usr/bin/env python3
"""In-container orchestrator for /run and /submit.

Runs INSIDE the sandbox container (mounted read-only at /in/orch.py — NOT baked
into the image, so it deploys without a rebuild). It runs the needed bt commands
in a single container (paying the validator-compile cost once) and emits one JSON
blob on stdout for the host to parse.

Why both bt commands: `bt run` gives verdicts but not the program's full
stdout/stderr; `bt test` gives full stdout+stderr but no verdict. So we run
`bt run` for verdicts and `bt test` per case for the rich output.

Invocation:
  python3 /in/orch.py <submission> --mode submit
  python3 /in/orch.py <submission> --mode run [--custom]

Modes:
  submit -> verdicts for ALL cases (sample + secret); rich detail for SAMPLE
            cases only (secret detail is withheld by the host anyway).
  run    -> sample cases (+ optional custom); rich detail for all of them.

Output: {"verdict_text": "<bt run output>", "cases": [ {name, bt_name, input,
expected, stdout, stderr}, ... ]}
"""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

WORK = Path("/work/problem")


def _read(p: Path):
    try:
        return p.read_text(errors="replace")
    except Exception:
        return None


def _stage(sub: str, has_custom: bool):
    WORK.mkdir(parents=True, exist_ok=True)
    for item in Path("/problem").iterdir():
        dst = WORK / item.name
        if item.is_dir():
            shutil.copytree(item, dst)
        else:
            shutil.copy2(item, dst)
    shutil.copy2(f"/in/{sub}", WORK / sub)

    sample_dir = WORK / "data" / "sample"
    custom_in = None
    custom_has_ans = False
    if has_custom:
        custom_in = sample_dir / "_custom.in"
        shutil.copy2("/in/custom.in", custom_in)
        if Path("/in/custom.ans").exists():
            shutil.copy2("/in/custom.ans", sample_dir / "_custom.ans")
            custom_has_ans = True
    return sample_dir, custom_in, custom_has_ans


def _bt(*args: str) -> str:
    r = subprocess.run(["bt", *args], capture_output=True, text=True, errors="replace")
    return r.stdout + "\n" + r.stderr


def _case_detail(sub: str, in_file: Path) -> dict:
    ans = in_file.with_suffix(".ans")
    r = subprocess.run(
        ["bt", "test", "--no-bar", sub, str(in_file.relative_to(WORK))],
        capture_output=True, text=True, errors="replace",
    )
    return {
        "name": "custom" if in_file.stem == "_custom" else f"sample/{in_file.stem}",
        "bt_name": f"sample/{in_file.stem}",
        "input": _read(in_file),
        "expected": _read(ans) if ans.exists() else None,
        "stdout": r.stdout,
        "stderr": r.stderr,
    }


def main() -> None:
    sub = sys.argv[1]
    mode = sys.argv[sys.argv.index("--mode") + 1]
    has_custom = "--custom" in sys.argv
    os.environ["HOME"] = "/work"

    sample_dir, custom_in, custom_has_ans = _stage(sub, has_custom)
    real_samples = sorted(p for p in sample_dir.glob("*.in") if p.stem != "_custom")
    os.chdir(WORK)

    if mode == "submit":
        # Verdicts for ALL cases (no path filter), rich detail for samples only.
        verdict_text = _bt("run", "-ve", "--no-bar", sub)
        cases = [_case_detail(sub, p) for p in real_samples]
    else:  # run: samples (+ custom), rich detail for all
        verdict_paths = [str(p.relative_to(WORK)) for p in real_samples]
        if custom_in is not None and custom_has_ans:
            verdict_paths.append(str(custom_in.relative_to(WORK)))
        verdict_text = _bt("run", "-ve", "--no-bar", sub, *verdict_paths) if verdict_paths else ""
        out_cases = list(real_samples) + ([custom_in] if custom_in is not None else [])
        cases = [_case_detail(sub, p) for p in out_cases]

    print(json.dumps({"verdict_text": verdict_text, "cases": cases}))


if __name__ == "__main__":
    main()
