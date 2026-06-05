from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum


class Status(Enum):
    AC = "AC"
    WA = "WA"
    TLE = "TLE"
    RTE = "RTE"
    MLE = "MLE"
    CE = "CE"
    JE = "JE"

    def __str__(self) -> str:
        return self.value


# Worst-first. The overall verdict is the highest-precedence one across testcases.
PRECEDENCE = [Status.CE, Status.JE, Status.RTE, Status.MLE, Status.TLE, Status.WA, Status.AC]


@dataclass
class TestcaseResult:
    name: str
    status: Status
    time_s: float
    detail: str | None = None


@dataclass
class Verdict:
    status: Status
    testcases: list[TestcaseResult] = field(default_factory=list)
    passed: int = 0
    total: int = 0
    max_time_s: float = 0.0
    raw_stdout: str = ""
    raw_stderr: str = ""
    returncode: int = 0


@dataclass
class RawRun:
    stdout: str
    stderr: str
    returncode: int


@dataclass
class RunCase:
    """One sample-or-custom case for the /run endpoint: verdict + full output.
    status/time_s are None for a custom case submitted without an expected answer.
    """
    name: str
    status: str | None
    time_s: float | None
    input: str | None
    expected: str | None
    stdout: str
    stderr: str


@dataclass
class SubmitCase:
    """One graded case for /submit. Sample cases carry full detail (public);
    secret cases carry status + time only (the rest stays None — no leak)."""
    name: str
    status: str
    time_s: float
    input: str | None = None
    expected: str | None = None
    stdout: str | None = None
    stderr: str | None = None


@dataclass
class SubmitResult:
    status: str
    passed: int
    total: int
    max_time_s: float
    cases: list[SubmitCase]
