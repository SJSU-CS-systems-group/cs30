"""Wire formats for the judge HTTP service.

All fields are plain JSON strings — inputs (source, stdin, expected) are editor
text, and program output (stdout/stderr) is decoded with errors="replace" so odd
bytes can't break JSON.

Two endpoints:
  POST /submit -> SubmitResponse  (all testcases; secret cases reveal status only)
  POST /run    -> RunResponse     (sample testcases + optional custom; rich detail)
"""
from __future__ import annotations
from enum import Enum

from pydantic import BaseModel, Field


class JobState(str, Enum):
    queued = "queued"
    running = "running"
    done = "done"
    error = "error"  # judge/infra failure (NOT a student verdict like WA/RTE)


# requests

class SubmitRequest(BaseModel):
    problem_id: str = Field(..., description="Problem dir name under problems_dir (or under the pool, if given)")
    pool: str | None = Field(None, description="Optional pool name; scopes lookup to problems_dir/<pool>/. Plain dir name.")
    language: str = Field(..., description="Submission language (see config.yaml `languages`)")
    source: str = Field(..., description="Source code (plain text)")
    wall_timeout: int | None = Field(None, ge=1, le=300)


class RunRequest(BaseModel):
    problem_id: str = Field(..., description="Problem dir name under problems_dir (or under the pool, if given)")
    pool: str | None = Field(None, description="Optional pool name; scopes lookup to problems_dir/<pool>/. Plain dir name.")
    language: str = Field(..., description="Submission language (see config.yaml `languages`)")
    source: str = Field(..., description="Source code (plain text)")
    custom_stdins: list[str] = Field(
        default_factory=list,
        description="Custom stdin inputs; each adds an ungraded 'custom/N' case (no expected). Capped by limits.max_custom_cases.",
    )
    stdin: str | None = Field(None, description="Deprecated: single custom stdin; prefer custom_stdins.")
    wall_timeout: int | None = Field(None, ge=1, le=300)


# /submit response (graded; no leaks for secret cases)

class SubmitTestcase(BaseModel):
    name: str                       # e.g. "sample/1", "secret/10"
    status: str                     # AC | WA | TLE | RTE | MLE | CE
    time_s: float
    # Populated for SAMPLE cases only (public); null for secret cases — exposing
    # these would leak the hidden test set (a program can echo its stdin).
    input: str | None = None
    expected: str | None = None
    stdout: str | None = None
    stderr: str | None = None


class SubmitResponse(BaseModel):
    status: str          # overall verdict (worst across cases)
    passed: int
    total: int
    max_time_s: float
    testcases: list[SubmitTestcase]
    compile_output: str | None = None   # compiler diagnostic when status == "CE"


# /run response (sample + custom; full per-case detail)

class RunTestcase(BaseModel):
    name: str                       # "sample/1" or "custom"
    status: str | None = None       # None for a custom case with no expected answer
    time_s: float | None = None
    input: str | None = None
    expected: str | None = None     # the answer file (public for samples)
    stdout: str = ""                # the program's actual output ("got")
    stderr: str = ""                # the program's stderr (debug logs); bt chatter stripped


class RunResponse(BaseModel):
    testcases: list[RunTestcase]
    compile_output: str | None = None   # compiler diagnostic on a build failure
