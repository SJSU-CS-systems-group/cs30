from __future__ import annotations
import re

from .models import PRECEDENCE, Status, TestcaseResult, Verdict


_PER_CASE_RE = re.compile(
    r"^\s*\S+:\s+(?P<verdict>AC|WA|TLE|RTE|MLE|CE|JE)\s+"
    r"(?P<time>[\d.]+)s\s+@\s+(?P<case>\S+)"
    r"(?:\s+permitted:\s+\[[^\]]+\])?"
    r"(?:\s+(?P<detail>.*))?$"
)


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
        # Heuristics for "didn't even reach a testcase".
        haystack = (stdout + "\n" + stderr).lower()
        if "compile" in haystack and ("error" in haystack or "failed" in haystack):
            overall = Status.CE
        else:
            overall = Status.JE
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
