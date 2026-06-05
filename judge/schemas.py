"""Wire formats for the judge HTTP service.

Inputs (source, stdin, expected) are **plain JSON strings** — editor text is
valid UTF-8 and JSON escaping handles all special characters. Program output
(stdout/stderr) is returned **base64-encoded**, because a program can emit bytes
that are not valid UTF-8/JSON (Judge0 paper, Listing 3 — see DECISIONS.md D12).
"""
from __future__ import annotations
from enum import Enum

from pydantic import BaseModel, Field

from .models import RawRun, Verdict


class Mode(str, Enum):
    submit = "submit"          # judge against all sample + secret cases
    run = "run"                # custom stdin, no judging (show stdout)
    run_judged = "run_judged"  # custom stdin + expected answer, judged


class SubmissionRequest(BaseModel):
    problem_id: str = Field(..., description="Problem dir name under problems_dir")
    language: str = Field(..., description="Submission language (see config.yaml `languages`)")
    source: str = Field(..., description="Source code (plain text)")
    mode: Mode = Mode.submit
    stdin: str | None = Field(None, description="Custom stdin (run / run_judged)")
    expected: str | None = Field(None, description="Expected output (run_judged)")
    wall_timeout: int | None = Field(None, ge=1, le=300)


class JobState(str, Enum):
    queued = "queued"
    running = "running"
    done = "done"
    error = "error"  # judge/infra failure (NOT a student verdict like WA/RTE)


class TestcaseOut(BaseModel):
    name: str
    status: str
    time_s: float
    detail: str | None = None


class VerdictOut(BaseModel):
    status: str
    passed: int
    total: int
    max_time_s: float
    testcases: list[TestcaseOut]


class RawOut(BaseModel):
    stdout_b64: str
    stderr_b64: str
    returncode: int


class JudgeResponse(BaseModel):
    """Synchronous response body: one of `verdict` (submit / run_judged) or
    `raw` (run) is populated."""
    mode: Mode
    verdict: VerdictOut | None = None
    raw: RawOut | None = None


def verdict_to_out(v: Verdict) -> VerdictOut:
    return VerdictOut(
        status=str(v.status),
        passed=v.passed,
        total=v.total,
        max_time_s=v.max_time_s,
        testcases=[
            TestcaseOut(name=t.name, status=str(t.status), time_s=t.time_s, detail=t.detail)
            for t in v.testcases
        ],
    )
