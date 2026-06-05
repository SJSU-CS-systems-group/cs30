"""In-memory worker pool + synchronous job execution.

v1 only (DECISIONS.md D12, Phase 1). A bounded ThreadPoolExecutor (≈ #CPU cores
— the per-machine sweet spot from the Judge0 paper) is the in-process work queue;
threads are correct because each job blocks on `docker run`, i.e. on I/O, where
Python releases the GIL (D4). Calls are synchronous: submit, block on the
future, return the result (no polling — see processes.md).

Phase 2 swaps this module for a real queue (Redis/RabbitMQ) + workers without
touching service.py or the runner.
"""
from __future__ import annotations
import tempfile
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .config import get_config
from .runner import run_samples, run_submit
from .schemas import JobState, RunRequest, SubmitRequest


# Slack beyond a run's wall limit to allow for queue wait + container spin-up
# before a synchronous caller is told the judge is overloaded (504).
_SYNC_MARGIN_SECONDS = 30


class JudgeError(Exception):
    """Bad request the caller can fix (maps to HTTP 400)."""


class QueueFull(Exception):
    """Too many jobs in flight; caller should retry later (maps to HTTP 429)."""


class SyncTimeout(Exception):
    """Result did not arrive within the sync window (maps to HTTP 504)."""


@dataclass
class Job:
    id: str
    state: JobState = JobState.queued
    result: object | None = None   # Verdict (submit) or list[RunCase] (run)
    error: str | None = None


class Store:
    def __init__(self) -> None:
        cfg = get_config().concurrency
        self._lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=cfg.max_workers)
        self._max_queue_size = cfg.max_queue_size
        self._inflight = 0  # jobs queued or running

    # -- public API ---------------------------------------------------------
    def submit_sync(self, req: SubmitRequest) -> Job:
        """Judge against all testcases; Job.result is a SubmitResult."""
        _validate(req.problem_id, req.language)
        return self._run_and_wait(
            req,
            lambda pd, cp: run_submit(pd, cp, wall_timeout=req.wall_timeout),
        )

    def run_sync(self, req: RunRequest) -> Job:
        """Run sample (+ optional custom) cases; Job.result is list[RunCase]."""
        _validate(req.problem_id, req.language)
        return self._run_and_wait(
            req,
            lambda pd, cp: run_samples(
                pd, cp, req.stdin, req.expected, wall_timeout=req.wall_timeout
            ),
        )

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    # -- internals ----------------------------------------------------------
    def _run_and_wait(self, req, runner_fn: Callable[[Path, Path], object]) -> Job:
        with self._lock:
            if self._inflight >= self._max_queue_size:
                raise QueueFull(
                    f"judge at capacity ({self._inflight}/{self._max_queue_size} in flight)"
                )
            self._inflight += 1

        job = Job(id=uuid.uuid4().hex)
        fut = self._pool.submit(self._work, job, req, runner_fn)
        try:
            fut.result(timeout=_sync_wait_seconds(req))
        except FutureTimeout:
            # Job is still running; it will finish + decrement inflight on its
            # own. The caller simply gave up waiting.
            raise SyncTimeout("judge did not return in time (overloaded); retry later")
        return job

    def _work(self, job: Job, req, runner_fn) -> None:
        job.state = JobState.running
        try:
            problem_dir = _resolve_problem_dir(req.problem_id)
            ext = _ext_for(req.language)
            with tempfile.TemporaryDirectory(prefix="judge-sub-") as tmp:
                code_path = Path(tmp) / f"submission{ext}"
                code_path.write_text(req.source, encoding="utf-8")
                code_path.chmod(0o644)  # readable by the container's (different) uid
                job.result = runner_fn(problem_dir, code_path)
            job.state = JobState.done
        except Exception as exc:  # any infra failure -> error state, never a verdict
            job.error = f"{type(exc).__name__}: {exc}"
            job.state = JobState.error
        finally:
            with self._lock:
                self._inflight -= 1


def _sync_wait_seconds(req) -> int:
    cfg = get_config().timeouts
    wall = req.wall_timeout or cfg.run_all_wall_seconds
    return wall + _SYNC_MARGIN_SECONDS


def _validate(problem_id: str, language: str) -> None:
    _resolve_problem_dir(problem_id)
    _ext_for(language)


def _resolve_problem_dir(problem_id: str) -> Path:
    # Reject path traversal: problem_id is a plain directory name.
    if not problem_id or "/" in problem_id or "\\" in problem_id or problem_id.startswith("."):
        raise JudgeError(f"invalid problem_id: {problem_id!r}")
    problems_dir = get_config().problems_dir.resolve()
    path = (problems_dir / problem_id).resolve()
    if problems_dir not in path.parents or not (path / "problem.yaml").is_file():
        raise JudgeError(f"unknown problem_id: {problem_id!r}")
    return path


def _ext_for(language: str) -> str:
    ext = get_config().languages.get(language.lower())
    if ext is None:
        raise JudgeError(f"unsupported language: {language!r}")
    return ext
