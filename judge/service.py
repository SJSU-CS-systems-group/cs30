"""Judge HTTP service — the black-box OCES layer (DECISIONS.md D12).

Spring Boot talks to this over HTTP and never touches Docker itself. This
service is the *trusted* boundary that holds Docker access; it treats student
code purely as data it drops into an ephemeral, hardened container (D3).

Run (v1, same machine as the backend is fine):

    uvicorn judge.service:app --host 127.0.0.1 --port 8000

Synchronous contract (see API.md):
    POST /submit -> 200 SubmitResponse   (all testcases; graded)
    POST /run    -> 200 RunResponse       (sample + optional custom; rich detail)
"""
from __future__ import annotations
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from .models import RunCase, SubmitResult
from .schemas import (
    JobState,
    RunRequest,
    RunResponse,
    RunTestcase,
    SubmitRequest,
    SubmitResponse,
    SubmitTestcase,
)
from .store import JudgeError, QueueFull, Store, SyncTimeout

store: Store


@asynccontextmanager
async def lifespan(app: FastAPI):
    global store
    store = Store()
    yield
    store.shutdown()


app = FastAPI(title="Lab Judge Service", version="0.2.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/submit", response_model=SubmitResponse)
def submit(req: SubmitRequest) -> SubmitResponse:
    """Grade a submission against ALL testcases. Secret cases reveal only
    status + time (no input/expected/output, to avoid leaking the hidden set)."""
    job = _execute(store.submit_sync, req)
    r: SubmitResult = job.result
    return SubmitResponse(
        status=r.status,
        passed=r.passed,
        total=r.total,
        max_time_s=r.max_time_s,
        testcases=[
            SubmitTestcase(
                name=c.name, status=c.status, time_s=c.time_s,
                input=c.input, expected=c.expected, stdout=c.stdout, stderr=c.stderr,
            )
            for c in r.cases
        ],
    )


@app.post("/run", response_model=RunResponse)
def run(req: RunRequest) -> RunResponse:
    """Run sample testcases (+ optional custom case) with full per-case detail —
    safe to disclose because samples are public and the custom case is the
    caller's own. For student self-testing/debugging."""
    job = _execute(store.run_sync, req)
    cases: list[RunCase] = job.result
    return RunResponse(testcases=[
        RunTestcase(
            name=c.name, status=c.status, time_s=c.time_s,
            input=c.input, expected=c.expected, stdout=c.stdout, stderr=c.stderr,
        )
        for c in cases
    ])


def _execute(method, req):
    """Shared error mapping for both endpoints."""
    try:
        job = method(req)
    except QueueFull as exc:
        raise HTTPException(status_code=429, detail=str(exc)) from exc
    except SyncTimeout as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except JudgeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if job.state is JobState.error:
        # Judge/infra failure (JE) — a system problem, not a student outcome.
        raise HTTPException(status_code=500, detail=job.error or "judge error")
    return job
