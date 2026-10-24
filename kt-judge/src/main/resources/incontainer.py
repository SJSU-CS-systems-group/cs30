#!/usr/bin/env python3
"""In-container orchestrator for /run and /submit.

Runs INSIDE the sandbox container (mounted read-only at /in/orch.py — NOT baked
into the image, so it deploys without a rebuild). It runs the needed bt commands
in a single container (paying the validator-compile cost once) and emits one JSON
blob on stdout for the host to parse.

Why both bt commands: `bt run` gives verdicts but not the program's full
stdout/stderr; `bt test` gives full stdout+stderr but no verdict. So we run
`bt run` for verdicts and `bt test` per case for the rich output. A file-I/O problem
(file_io.yaml) skips `bt test`: its wrapper saves each case's output during `bt run`.

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


# bt's testcase sanity checks warn about odd testcase FILES (empty, oversized, stray control
# bytes). The warning is printed in place of that case's verdict line, so an accepted case whose
# output is empty loses its "AC ... @ name" line entirely and disappears from the parsed verdict,
# which then trips the host's completeness check. The checks are for problem authors and grade
# nothing: the output validator decides AC/WA either way.
_NO_SANITY = "--no-testcase-sanity-checks"


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


def _seed_btcache() -> None:
    """Restore the image's pre-warmed bt cache into the run's tmpfs and point
    TMPDIR at it, so bt reuses the baked default output validator. It must
    land at the same path it was warmed at (/work/btcache)."""
    seed = Path("/opt/bt-cache-seed")
    if not seed.is_dir():
        return
    dst = Path("/work/btcache")
    dst.mkdir(parents=True, exist_ok=True)
    subprocess.run(["cp", "-a", f"{seed}/.", f"{dst}/"], check=False)
    os.environ["TMPDIR"] = str(dst)


# File I/O. A problem with a file_io.yaml gives each test case N, next to N.in:
#   N.yaml       `args: [...]`, the program's command-line arguments (bt passes these itself)
#   N.files/     starting files, copied next to the program before it runs
#   N.expected/  the files the program must create; a file named `stdout` stands for what it printed
# and file_io.yaml may say how each output file is compared:
#   compare:
#     "*":     {order: strict}             # the default for every file
#     odd.txt: {order: any, unique: true}  # any line order, repeats ignored
# The cs30-fileio wrapper in the image runs each case and glues the files the program wrote into
# one text; N.ans is glued from N.expected/ by the same wrapper, so bt compares like with like.
# backend/src/main/service/FileIoProblem.kt checks the same rules when a problem is added.
FILE_IO = "file_io.yaml"
FIO_RESULTS = Path("/work/fio")
FIO_COMPARE = FIO_RESULTS / "compare"
_FILE_NAME_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")
_ORDERS = ("strict", "any", "any_words")
# The most of one starting file shown as a case's input; the rest is cut.
_SHOWN_FILE_CHARS = 20_000


def _test_inputs():
    """Every test case's .in under data/sample and data/secret, nested groups included."""
    data = WORK / "data"
    for group in ("sample", "secret"):
        if (data / group).is_dir():
            yield from sorted((data / group).rglob("*.in"))


def _plain_names(folder: Path, what: str) -> list:
    """The file names in a test case's .files/ or .expected/ folder, which must all be plain files
    with plain names (the wrapper matches them by name and copies the folder flat)."""
    names = []
    for entry in sorted(folder.iterdir()):
        if not entry.is_file() or not _FILE_NAME_RE.fullmatch(entry.name):
            sys.exit(f"{FILE_IO}: {what} {entry} must be a plain file named like input.txt (no folders)")
        names.append(entry.name)
    return names


def _file_io_setup() -> bool:
    """Turns file I/O on for a problem with a file_io.yaml: checks the problem, writes the compare
    options and N.ans files the wrapper and bt need, and sets the CS30_FILEIO variables. Returns
    False, changing nothing, for a problem without one. A problem that breaks the rules stops the
    run with a message, as a judge error: grading it anyway could fail correct students."""
    path = WORK / FILE_IO
    if not path.is_file():
        return False
    from ruamel.yaml import YAML  # ships with bt

    yaml = YAML(typ="safe")
    conf = yaml.load(path.read_text()) or {}
    if not isinstance(conf, dict) or set(conf) - {"compare"}:
        sys.exit(f"{FILE_IO}: the only key allowed is 'compare'")
    compare = conf.get("compare") or {}
    if not isinstance(compare, dict):
        sys.exit(f"{FILE_IO}: 'compare' must map file names to options")
    lines = []
    for name, opts in compare.items():
        if name != "*" and not (isinstance(name, str) and _FILE_NAME_RE.fullmatch(name)):
            sys.exit(f"{FILE_IO}: compare: {name!r} is not a plain file name or '*'")
        if not isinstance(opts, dict) or set(opts) - {"order", "unique"}:
            sys.exit(f"{FILE_IO}: compare: {name}: options are 'order' and 'unique'")
        order = opts.get("order", "strict")
        unique = opts.get("unique", False)
        if order not in _ORDERS:
            sys.exit(f"{FILE_IO}: compare: {name}: order must be one of {', '.join(_ORDERS)}")
        if not isinstance(unique, bool):
            sys.exit(f"{FILE_IO}: compare: {name}: unique must be true or false")
        if unique and order == "strict":
            sys.exit(f"{FILE_IO}: compare: {name}: unique needs order 'any' or 'any_words'")
        lines.append((name, order, "true" if unique else "false"))
    lines.sort(key=lambda line: line[0] != "*")  # the default first, so a file's own line wins

    problem_yaml = WORK / "problem.yaml"
    if problem_yaml.is_file():
        ptype = (yaml.load(problem_yaml.read_text()) or {}).get("type", "pass-fail")
        types = ptype if isinstance(ptype, list) else str(ptype).split()
        if {"interactive", "multi-pass"} & set(types):
            sys.exit(f"{FILE_IO}: file I/O does not work with {ptype} problems")

    expected_names = set()
    expected_dirs = []
    for in_file in _test_inputs():
        stem = in_file.with_suffix("")
        files_dir, exp_dir = Path(f"{stem}.files"), Path(f"{stem}.expected")
        if files_dir.is_dir():
            _plain_names(files_dir, "starting file")
        if exp_dir.is_dir():
            if in_file.with_suffix(".ans").exists():
                sys.exit(f"{FILE_IO}: {in_file.with_suffix('.ans')} and {exp_dir} both give the answer; keep one")
            names = _plain_names(exp_dir, "expected file")
            if not names:
                sys.exit(f"{FILE_IO}: {exp_dir} is empty")
            expected_names.update(names)
            expected_dirs.append((in_file, exp_dir))
    typos = {name for name, _, _ in lines if name != "*"} - expected_names
    if typos:
        sys.exit(f"{FILE_IO}: compare names {sorted(typos)}, which no test case expects")

    FIO_RESULTS.mkdir(parents=True, exist_ok=True)
    FIO_COMPARE.write_text("".join(f"{n} {o} {u}\n" for n, o, u in lines))
    os.environ.update({
        "CS30_FILEIO": "1",
        "CS30_FILEIO_DATA": str(WORK / "data"),
        "CS30_FILEIO_RESULTS": str(FIO_RESULTS),
        "CS30_FILEIO_COMPARE": str(FIO_COMPARE),
    })
    for in_file, exp_dir in expected_dirs:
        r = subprocess.run(["cs30-fileio", "--glue", str(exp_dir)], capture_output=True, text=True, errors="replace")
        if r.returncode != 0:
            sys.exit(f"{FILE_IO}: could not build the answer from {exp_dir}: {r.stderr.strip()}")
        in_file.with_suffix(".ans").write_text(r.stdout)
    return True


def _check_judge_errors() -> None:
    """The wrapper leaves a judge_error file where a case failed on the judge's side (e.g. its
    starting files could not be copied); fail the whole run rather than grade it."""
    for marker in FIO_RESULTS.rglob("judge_error"):
        sys.exit(f"file I/O: {marker.read_text().strip()}")


def _shown_input(in_file: Path) -> str:
    """A file-I/O case's input as shown to the student: its arguments, its stdin if any, and its
    starting files, glued like the output so each file is labelled."""
    stem = in_file.with_suffix("")
    parts = []
    case_yaml = Path(f"{stem}.yaml")
    if case_yaml.is_file():
        from ruamel.yaml import YAML

        args = (YAML(typ="safe").load(case_yaml.read_text()) or {}).get("args") or []
        if args:
            parts.append("=== arguments ===\n" + " ".join(str(a) for a in args) + "\n")
    stdin = _read(in_file) or ""
    if stdin:
        parts.append("=== stdin ===\n" + stdin)
    files_dir = Path(f"{stem}.files")
    if files_dir.is_dir():
        for f in sorted(files_dir.iterdir()):
            text = _read(f) or ""
            if len(text) > _SHOWN_FILE_CHARS:
                text = text[:_SHOWN_FILE_CHARS] + "\n[... cut]\n"
            parts.append(f"=== {f.name} ===\n{text}")
    return "".join(p if p.endswith("\n") else p + "\n" for p in parts)


def _fio_case_detail(in_file: Path) -> dict:
    """A file-I/O case's detail from what the wrapper saved during `bt run`. `bt test` is not used:
    it would run the program a second time, and without the case's arguments. A case bt killed
    (TLE) left nothing, so it shows no output, as with stdin problems."""
    case_dir = FIO_RESULTS / _bt_name(in_file)
    ans = in_file.with_suffix(".ans")
    return {
        "name": _bt_name(in_file),
        "bt_name": _bt_name(in_file),
        "input": _shown_input(in_file),
        "expected": _read(ans) if ans.exists() else None,
        "stdout": _read(case_dir / "answer") or "",
        "stderr": _read(case_dir / "debug") or "",
    }


def main() -> None:
    sub = sys.argv[1]
    mode = sys.argv[sys.argv.index("--mode") + 1]
    os.environ["HOME"] = "/work"
    _seed_btcache()

    sample_dir, customs = _stage(sub)
    real_samples = sorted(p for p in sample_dir.glob("*.in") if not p.stem.startswith("_custom"))
    file_io = _file_io_setup()
    os.chdir(WORK)

    if file_io:
        # Custom cases are typed stdin only; a file-I/O program also needs its starting files and
        # arguments, so they would only ever fail. The app hides the box; this covers older apps.
        for c in customs:
            c.unlink()
        if mode == "submit":
            verdict_text = _bt("run", "-ve", "-aa", "--no-bar", _NO_SANITY, sub)
            _check_judge_errors()
            # Detail for samples, and for secret cases that crashed (input/expected are withheld
            # by the host regardless), as for stdin problems.
            rte = set(re.findall(r"\bRTE\b.*?@\s*(\S+)", verdict_text))
            shown = real_samples + [p for p in _test_inputs() if _bt_name(p).startswith("secret/") and _bt_name(p) in rte]
        else:
            verdict_paths = [str(p.relative_to(WORK)) for p in real_samples]
            verdict_text = _bt("run", "-ve", "--no-bar", _NO_SANITY, sub, *verdict_paths) if verdict_paths else ""
            _check_judge_errors()
            shown = real_samples
        cases = [_fio_case_detail(p) for p in shown]
    elif mode == "submit":
        # Verdicts for ALL cases (no path filter), rich detail for samples only.
        # -aa: run every testcase AND keep going after timeouts, so passed/total
        # is complete and consistent for grading (default bt stops early on TLE).
        verdict_text = _bt("run", "-ve", "-aa", "--no-bar", _NO_SANITY, sub)
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
        verdict_text = _bt("run", "-ve", "--no-bar", _NO_SANITY, sub, *verdict_paths) if verdict_paths else ""
        tle = set(_TLE_RE.findall(verdict_text))
        out_cases = list(real_samples) + customs
        cases = [_case_detail(sub, p, skip=f"sample/{p.stem}" in tle) for p in out_cases]

    print(json.dumps({"verdict_text": verdict_text, "cases": cases}))


if __name__ == "__main__":
    main()
