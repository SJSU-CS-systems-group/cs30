from __future__ import annotations
import re

from .models import PRECEDENCE, Status, TestcaseResult, Verdict


_PER_CASE_RE = re.compile(
    r"^\s*\S+:\s+(?P<verdict>AC|WA|TLE|RTE|MLE|CE|JE)\s+"
    r"(?P<time>[\d.]+)s\s+@\s+(?P<case>\S+)"
    r"(?:\s+permitted:\s+\[[^\]]+\])?"
    r"(?:\s+(?P<detail>.*))?$"
)


# bt's own progress/diagnostic lines (run/test mode). Anchored to bt's wording
# and our fixed names (submission.*, _custom) to avoid stripping a program's
# real stderr. Coupled to bt's output format (pinned version), like the parser.
_BT_NOISE_RE = re.compile(
    r"^(?:"
    r"ERROR: problem:.*"
    r"|PROBLEM\s.*"
    r"|Building (?:output|input) validators?.*"
    r"|Build submissions?:.*"
    r"|Run: using timelimit:.*"
    r"|Running:\s.*"
    r"|Running \S+:\s\S+.*"
    r"|Done:\s+[\d.]+s.*"
    r")$"
)


def clean_compile_output(text: str) -> str:
    """Extract the compiler diagnostic from bt's build-failure output: drop bt's
    chatter and rewrite the internal container path to just the source filename."""
    text = re.sub(r"/tmp/bapctools_\w+/problem/submissions/[^/\s]+/", "", text)
    return strip_bt_noise(text).strip()


def strip_bt_noise(stderr: str) -> str:
    """Remove bt's chatter from a run-mode stderr, leaving only the program's
    real stderr. bt interleaves its progress (`Running…`, `Done:`, `PROBLEM…`,
    the statement-.tex warning) with the submission's stderr; on a clean run
    this returns "" so a successful run does not look like a failure, while a
    program's actual stderr (e.g. a crash traceback) is preserved.
    """
    kept = [
        ln for ln in stderr.splitlines()
        if ln.strip() and not _BT_NOISE_RE.match(ln)
    ]
    return "\n".join(kept)


def parse_run_output(stdout: str, stderr: str, returncode: int) -> Verdict:
    cases: dict[str, TestcaseResult] = {}
    # bt writes verdict lines to stderr (along with progress bars and warnings);
    # scan both streams so we catch results regardless.
    for line in (stdout + "\n" + stderr).splitlines():
        m = _PER_CASE_RE.match(line)
        if not m:
            continue
        status = Status[m["verdict"]]
        case = TestcaseResult(
            name=m["case"],
            status=status,
            time_s=float(m["time"]),
            detail=(m["detail"] or None),
        )
        # bt can print the same case multiple times; keep the worst verdict.
        prev = cases.get(case.name)
        if prev is None or PRECEDENCE.index(case.status) < PRECEDENCE.index(prev.status):
            cases[case.name] = case

    if not cases:
        # Heuristics for "didn't even reach a testcase". Note bt says
        # "compilation"/"Build submissions: ... Failed" (not literally "compile").
        haystack = (stdout + "\n" + stderr).lower()
        compile_failed = (
            "compil" in haystack
            or ("build submissions:" in haystack and "failed" in haystack)
        )
        overall = Status.CE if compile_failed else Status.JE
        return Verdict(
            status=overall,
            raw_stdout=stdout, raw_stderr=stderr, returncode=returncode,
        )

    results = list(cases.values())
    overall = min(results, key=lambda c: PRECEDENCE.index(c.status)).status
    return Verdict(
        status=overall,
        testcases=results,
        passed=sum(1 for c in results if c.status is Status.AC),
        total=len(results),
        max_time_s=max(c.time_s for c in results),
        raw_stdout=stdout,
        raw_stderr=stderr,
        returncode=returncode,
    )
