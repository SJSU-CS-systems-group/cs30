"""Judge HTTP service — the black-box OCES layer (DECISIONS.md D12).

Spring Boot talks to this over HTTP and never touches Docker itself. This
service is the *trusted* boundary that holds Docker access; it treats student
code purely as data it drops into an ephemeral, hardened container (D3).

Run (v1, same machine as the backend is fine):

    cd /home/ddd/cs30
    pip install -r judge/requirements.txt
    uvicorn judge.service:app --host 127.0.0.1 --port 8000

Synchronous contract (see API.md):
    POST /submissions  -> 200 {mode, verdict|raw}   (blocks until judged)
"""
from __future__ import annotations
import base64
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from .schemas import (
    JobState,
    JudgeResponse,
    RawOut,
    SubmissionRequest,
    verdict_to_out,
)
from .store import JudgeError, QueueFull, Store, SyncTimeout

store: Store


@asynccontextmanager
async def lifespan(app: FastAPI):
    global store
    store = Store()
    yield
    store.shutdown()


app = FastAPI(title="Lab Judge Service", version="0.1.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/submissions", response_model=JudgeResponse)
def create_submission(req: SubmissionRequest) -> JudgeResponse:
    """Judge one submission synchronously: block until the run completes, then
    return the verdict (submit / run_judged) or raw output (run)."""
    try:
        job = store.run_sync(req)
    except QueueFull as exc:
        raise HTTPException(status_code=429, detail=str(exc)) from exc
    except SyncTimeout as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except JudgeError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    if job.state is JobState.error:
        # Judge/infra failure (JE) — a system problem, not a student outcome.
        raise HTTPException(status_code=500, detail=job.error or "judge error")

    resp = JudgeResponse(mode=job.req.mode)
    if job.verdict is not None:
        resp.verdict = verdict_to_out(job.verdict)
    if job.raw is not None:
        resp.raw = RawOut(
            stdout_b64=base64.b64encode(job.raw.stdout.encode("utf-8", "replace")).decode(),
            stderr_b64=base64.b64encode(job.raw.stderr.encode("utf-8", "replace")).decode(),
            returncode=job.raw.returncode,
        )
    return resp
