#!/usr/bin/env python3
"""In-container orchestrator for the /run endpoint.

Runs INSIDE the sandbox container (mounted read-only at /in/orch.py — NOT baked
into the image, so it deploys without a rebuild). It judges the sample testcases
and an optional custom case, capturing each case's full stdout/stderr, and emits
one JSON blob on stdout for the host to parse.

Why here and not on the host: getting both the verdict (bt run) and the full
per-case program output (bt test) means several bt invocations; doing them in a
single container pays the validator-compile cost once instead of per case.

Invocation:  python3 /in/orch.py <submission_filename> [--custom]
  /problem        read-only problem package
  /in/<sub>       the submission
  /in/custom.in   custom stdin            (when --custom)
  /in/custom.ans  custom expected answer  (optional, when --custom)
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


def main() -> None:
    sub = sys.argv[1]
    has_custom = "--custom" in sys.argv[2:]
    os.environ["HOME"] = "/work"

    # Stage the read-only problem into writable tmpfs.
    WORK.mkdir(parents=True, exist_ok=True)
    for item in Path("/problem").iterdir():
        dst = WORK / item.name
        if item.is_dir():
            shutil.copytree(item, dst)
        else:
            shutil.copy2(item, dst)
    shutil.copy2(f"/in/{sub}", WORK / sub)

    sample_dir = WORK / "data" / "sample"
    real_samples = sorted(sample_dir.glob("*.in"))  # before adding custom

    custom_in = None
    custom_has_ans = False
    if has_custom:
        custom_in = sample_dir / "_custom.in"
        shutil.copy2("/in/custom.in", custom_in)
        if Path("/in/custom.ans").exists():
            shutil.copy2("/in/custom.ans", sample_dir / "_custom.ans")
            custom_has_ans = True

    os.chdir(WORK)

    # Verdicts: only cases that have an answer file can be judged.
    verdict_paths = [str(p.relative_to(WORK)) for p in real_samples]
    if custom_in is not None and custom_has_ans:
        verdict_paths.append(str(custom_in.relative_to(WORK)))
    verdict_text = ""
    if verdict_paths:
        vr = subprocess.run(
            ["bt", "run", "-ve", "--no-bar", sub, *verdict_paths],
            capture_output=True, text=True, errors="replace",
        )
        verdict_text = vr.stdout + "\n" + vr.stderr

    # Full per-case output via bt test (the only mode that surfaces the program's
    # own stdout AND stderr).
    output_cases = list(real_samples) + ([custom_in] if custom_in is not None else [])
    cases = []
    for in_file in output_cases:
        ans_file = in_file.with_suffix(".ans")
        tr = subprocess.run(
            ["bt", "test", "--no-bar", sub, str(in_file.relative_to(WORK))],
            capture_output=True, text=True, errors="replace",
        )
        cases.append({
            "name": "custom" if in_file.stem == "_custom" else f"sample/{in_file.stem}",
            "bt_name": f"sample/{in_file.stem}",   # how it appears in verdict_text
            "input": _read(in_file),
            "expected": _read(ans_file) if ans_file.exists() else None,
            "stdout": tr.stdout,
            "stderr": tr.stderr,
        })

    print(json.dumps({"verdict_text": verdict_text, "cases": cases}))


if __name__ == "__main__":
    main()
