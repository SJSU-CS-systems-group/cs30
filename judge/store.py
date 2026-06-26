"""In-memory worker pool + synchronous job execution.

A bounded ThreadPoolExecutor (≈ #CPU cores) is the in-process work queue;
threads are fine because each job blocks on `docker run` (I/O, where Python
releases the GIL). Calls are synchronous: submit, block on the future, return
the result.
"""
from __future__ import annotations
import re
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

    # public API
    def submit_sync(self, req: SubmitRequest) -> Job:
        """Judge against all testcases; Job.result is a SubmitResult."""
        _validate(req.problem_id, req.language, req.pool)
        return self._run_and_wait(
            req,
            lambda pd, cp: run_submit(pd, cp, wall_timeout=req.wall_timeout),
        )

    def run_sync(self, req: RunRequest) -> Job:
        """Run sample + custom cases; Job.result is a RunResult."""
        _validate(req.problem_id, req.language, req.pool)
        customs = _custom_stdins(req)
        max_n = get_config().limits.max_custom_cases
        if len(customs) > max_n:
            raise JudgeError(f"too many custom cases ({len(customs)} > {max_n})")
        return self._run_and_wait(
            req,
            lambda pd, cp: run_samples(pd, cp, customs, wall_timeout=req.wall_timeout),
        )

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)

    # internals
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
            problem_dir = _resolve_problem_dir(req.problem_id, req.pool)
            ext = _ext_for(req.language)
            with tempfile.TemporaryDirectory(prefix="judge-sub-") as tmp:
                code_path = Path(tmp) / _submission_filename(req.language, ext, req.source)
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


def _validate(problem_id: str, language: str, pool: str | None = None) -> None:
    _resolve_problem_dir(problem_id, pool)
    _ext_for(language)


def _custom_stdins(req: RunRequest) -> list[str]:
    # Prefer the list; fall back to the deprecated single `stdin` for back-compat.
    if req.custom_stdins:
        return list(req.custom_stdins)
    return [req.stdin] if req.stdin is not None else []


def _is_plain_name(s: str) -> bool:
    # A single directory name — no separators, no traversal, no hidden dirs.
    return bool(s) and "/" not in s and "\\" not in s and not s.startswith(".")


def _resolve_problem_dir(problem_id: str, pool: str | None = None) -> Path:
    # problem_id and the optional pool are each a plain directory name. A pool
    # scopes the lookup to problems_dir/<pool>/ (per-course namespacing). If the
    # pool dir doesn't exist, fall back to the flat problems_dir. Both names are
    # validated the same way so neither can escape the configured root.
    if not _is_plain_name(problem_id):
        raise JudgeError(f"invalid problem_id: {problem_id!r}")
    if pool is not None and not _is_plain_name(pool):
        raise JudgeError(f"invalid pool: {pool!r}")
    root = get_config().problems_dir.resolve()
    base = root
    if pool:
        pool_dir = (root / pool).resolve()
        if pool_dir.is_dir():
            base = pool_dir
        else:
            pool = None  # no pool dir -> fall back to flat root
    path = (base / problem_id).resolve()
    if base not in path.parents or not (path / "problem.yaml").is_file():
        where = f"{problem_id!r} in pool {pool!r}" if pool else f"{problem_id!r}"
        raise JudgeError(f"unknown problem_id: {where}")
    return path


def _ext_for(language: str) -> str:
    ext = get_config().languages.get(language.lower())
    if ext is None:
        raise JudgeError(f"unsupported language: {language!r}")
    return ext


_JAVA_PUBLIC_CLASS = re.compile(r"\bpublic\s+(?:final\s+|abstract\s+)?class\s+([A-Za-z_]\w*)")


def _submission_filename(language: str, ext: str, source: str) -> str:
    # Java requires the file name to match the public class name, else javac
    # errors ("class X is public, should be declared in a file named X.java").
    if language.lower() == "java":
        m = _JAVA_PUBLIC_CLASS.search(source)
        return f"{m.group(1)}.java" if m else "Main.java"
    return f"submission{ext}"
