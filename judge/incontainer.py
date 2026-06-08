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
import re
import shutil
import signal
import subprocess
import sys
from pathlib import Path

WORK = Path("/work/problem")

# Case names that bt reported as TLE — we skip bt test for these (its output is
# meaningless and running it on an infinite loop is what hangs).
_TLE_RE = re.compile(r"\bTLE\b.*?@\s*(\S+)")


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


# bt test does NOT enforce a time limit. We skip it for cases bt already
# reported as TLE; the safety net below covers any other hang (e.g. a custom
# case with no expected answer, which has no verdict to check).
_CASE_TIMEOUT = 10


def _run_capped(cmd: list[str]):
    # Own process group so a timeout kills bt AND its child (the submission).
    # Killing only bt leaves the child holding the output pipe, which makes
    # cleanup block forever (the bug behind the earlier TLE -> 500).
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                         text=True, errors="replace", start_new_session=True)
    try:
        out, err = p.communicate(timeout=_CASE_TIMEOUT)
    except subprocess.TimeoutExpired:
        os.killpg(os.getpgid(p.pid), signal.SIGKILL)
        out, err = p.communicate()
        err = (err or "") + "\n[run exceeded time limit — output truncated]"
    return out, err


def _bt_name(in_file: Path) -> str:
    # bt names a case by its path under data/ without the .in suffix,
    # e.g. data/secret/3.in -> "secret/3", data/sample/_custom.in -> "sample/_custom".
    rel = str(in_file.relative_to(WORK / "data"))
    return rel[:-3] if rel.endswith(".in") else rel


def _case_detail(sub: str, in_file: Path, skip: bool = False) -> dict:
    ans = in_file.with_suffix(".ans")
    bt_name = _bt_name(in_file)
    if skip:
        out, err = "", ""   # TLE case: no meaningful output, don't run bt test
    else:
        out, err = _run_capped(["bt", "test", "--no-bar", sub, str(in_file.relative_to(WORK))])
    return {
        "name": "custom" if in_file.stem == "_custom" else bt_name,
        "bt_name": bt_name,
        "input": _read(in_file),
        "expected": _read(ans) if ans.exists() else None,
        "stdout": out,
        "stderr": err,
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
        # -aa: run every testcase AND keep going after timeouts, so passed/total
        # is complete and consistent for grading (default bt stops early on TLE).
        verdict_text = _bt("run", "-ve", "-aa", "--no-bar", sub)
        tle = set(_TLE_RE.findall(verdict_text))
        rte = set(re.findall(r"\bRTE\b.*?@\s*(\S+)", verdict_text))
        cases = [_case_detail(sub, p, skip=_bt_name(p) in tle) for p in real_samples]
        # Capture the error output for SECRET cases that crashed (RTE), so the
        # student can see the traceback. We do NOT run bt test on AC/WA/TLE
        # secret cases. (input/expected are withheld by the host regardless.)
        secret_dir = WORK / "data" / "secret"
        if secret_dir.is_dir():
            for inf in sorted(secret_dir.glob("*.in")):
                if _bt_name(inf) in rte:
                    cases.append(_case_detail(sub, inf))
    else:  # run: samples (+ custom), rich detail for all
        verdict_paths = [str(p.relative_to(WORK)) for p in real_samples]
        if custom_in is not None and custom_has_ans:
            verdict_paths.append(str(custom_in.relative_to(WORK)))
        verdict_text = _bt("run", "-ve", "--no-bar", sub, *verdict_paths) if verdict_paths else ""
        tle = set(_TLE_RE.findall(verdict_text))
        out_cases = list(real_samples) + ([custom_in] if custom_in is not None else [])
        cases = [_case_detail(sub, p, skip=f"sample/{p.stem}" in tle) for p in out_cases]

    print(json.dumps({"verdict_text": verdict_text, "cases": cases}))


if __name__ == "__main__":
    main()
