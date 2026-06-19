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
  python3 /in/orch.py <submission> --mode run   (custom cases auto-discovered from /in/custom_*.in)

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


def _custom_index(p: Path) -> int:
    # /in/custom_3.in -> 3
    try:
        return int(p.stem.rsplit("_", 1)[-1])
    except ValueError:
        return 0


def _stage(sub: str):
    WORK.mkdir(parents=True, exist_ok=True)
    for item in Path("/problem").iterdir():
        dst = WORK / item.name
        if item.is_dir():
            shutil.copytree(item, dst)
        else:
            shutil.copy2(item, dst)
    shutil.copy2(f"/in/{sub}", WORK / sub)

    # Each /in/custom_<n>.in becomes an ungraded case data/sample/_custom_<n>.in.
    sample_dir = WORK / "data" / "sample"
    customs = []
    for src in sorted(Path("/in").glob("custom_*.in"), key=_custom_index):
        dst = sample_dir / f"_custom_{_custom_index(src)}.in"
        shutil.copy2(src, dst)
        customs.append(dst)
    return sample_dir, customs


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
    stem = in_file.stem
    name = f"custom/{stem.rsplit('_', 1)[-1]}" if stem.startswith("_custom") else bt_name
    return {
        "name": name,
        "bt_name": bt_name,
        "input": _read(in_file),
        "expected": _read(ans) if ans.exists() else None,
        "stdout": out,
        "stderr": err,
    }


def main() -> None:
    sub = sys.argv[1]
    mode = sys.argv[sys.argv.index("--mode") + 1]
    os.environ["HOME"] = "/work"

    sample_dir, customs = _stage(sub)
    real_samples = sorted(p for p in sample_dir.glob("*.in") if not p.stem.startswith("_custom"))
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
    else:  # run: samples + custom cases; custom cases are ungraded (no verdict)
        verdict_paths = [str(p.relative_to(WORK)) for p in real_samples]
        verdict_text = _bt("run", "-ve", "--no-bar", sub, *verdict_paths) if verdict_paths else ""
        tle = set(_TLE_RE.findall(verdict_text))
        out_cases = list(real_samples) + customs
        cases = [_case_detail(sub, p, skip=f"sample/{p.stem}" in tle) for p in out_cases]

    print(json.dumps({"verdict_text": verdict_text, "cases": cases}))


if __name__ == "__main__":
    main()
