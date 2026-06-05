"""In-memory submission store + worker pool.

v1 only (DECISIONS.md D12, Phase 1). The store is a process-local dict and the
pool is a bounded ThreadPoolExecutor (≈ #CPU cores — the per-machine sweet spot
from the Judge0 paper). Threads are correct here because each job blocks on
`docker run`, i.e. on I/O, where Python releases the GIL (D4).

Phase 2 swaps this module for a real queue (Redis/RabbitMQ) + results DB without
touching service.py or the runner.
"""
from __future__ import annotations
import tempfile
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError as FutureTimeout
from dataclasses import dataclass
from pathlib import Path

from .config import get_config
from .models import RawRun, Verdict
from .runner import run_all, run_judged_custom, run_raw_custom
from .schemas import (
    JobState,
    Mode,
    SubmissionRequest,
)


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
    req: SubmissionRequest
    state: JobState = JobState.queued
    verdict: Verdict | None = None
    raw: RawRun | None = None
    error: str | None = None


class Store:
    def __init__(self) -> None:
        cfg = get_config().concurrency
        self._lock = threading.Lock()
        self._pool = ThreadPoolExecutor(max_workers=cfg.max_workers)
        self._max_queue_size = cfg.max_queue_size
        self._inflight = 0  # jobs queued or running

    def run_sync(self, req: SubmissionRequest) -> Job:
        """Submit and block until judged; return the completed Job.

        Raises JudgeError (bad request → 400), QueueFull (at capacity → 429),
        or SyncTimeout (queue wait exceeded the sync window → 504).
        """
        job = self._accept(req)
        fut = self._pool.submit(self._run, job)
        try:
            fut.result(timeout=_sync_wait_seconds(req))
        except FutureTimeout:
            # The job is still queued/running and will finish + decrement
            # inflight on its own; the caller simply gave up waiting.
            raise SyncTimeout("judge did not return in time (overloaded); retry later")
        return job

    def _accept(self, req: SubmissionRequest) -> Job:
        # Validate cheaply *before* accepting, so the client gets a 4xx now
        # rather than an opaque failure later.
        _resolve_problem_dir(req.problem_id)
        _ext_for(req.language)
        if req.mode in (Mode.run, Mode.run_judged) and req.stdin is None:
            raise JudgeError(f"mode={req.mode.value} requires stdin")
        if req.mode is Mode.run_judged and req.expected is None:
            raise JudgeError("mode=run_judged requires expected")

        with self._lock:
            if self._inflight >= self._max_queue_size:
                raise QueueFull(
                    f"judge at capacity ({self._inflight}/{self._max_queue_size} in flight)"
                )
            self._inflight += 1
        return Job(id=uuid.uuid4().hex, req=req)

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    # -- worker -------------------------------------------------------------
    def _run(self, job: Job) -> None:
        job.state = JobState.running
        try:
            self._execute(job)
            job.state = JobState.done
        except Exception as exc:  # any infra failure -> error state, never a verdict
            job.error = f"{type(exc).__name__}: {exc}"
            job.state = JobState.error
        finally:
            with self._lock:
                self._inflight -= 1

    def _execute(self, job: Job) -> None:
        req = job.req
        problem_dir = _resolve_problem_dir(req.problem_id)
        ext = _ext_for(req.language)

        with tempfile.TemporaryDirectory(prefix="judge-sub-") as tmp:
            code_path = Path(tmp) / f"submission{ext}"
            code_path.write_text(req.source, encoding="utf-8")

            kw = {"wall_timeout": req.wall_timeout} if req.wall_timeout else {}
            if req.mode is Mode.submit:
                job.verdict = run_all(problem_dir, code_path, **kw)
            elif req.mode is Mode.run:
                job.raw = run_raw_custom(problem_dir, code_path, req.stdin, **kw)
            elif req.mode is Mode.run_judged:
                job.verdict = run_judged_custom(problem_dir, code_path, req.stdin, req.expected, **kw)


def _sync_wait_seconds(req: SubmissionRequest) -> int:
    cfg = get_config().timeouts
    if req.wall_timeout:
        wall = req.wall_timeout
    elif req.mode is Mode.submit:
        wall = cfg.run_all_wall_seconds
    else:
        wall = cfg.custom_wall_seconds
    return wall + _SYNC_MARGIN_SECONDS


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
