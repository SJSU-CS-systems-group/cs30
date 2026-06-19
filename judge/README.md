# Judge Service API Contract

For the backend (Spring Boot) to integrate against. The judge compiles and runs
a submission in a sandboxed container and returns the result.

- **Base URL:** `http://<judge-host>:8000` (internal network; not public).
- **Content-Type:** `application/json` for request and response.
- **Auth:** none in v1 (service is internal-only — keep it firewalled to the
  backend; do not expose publicly).
- **Encoding:** **everything is plain JSON strings** — request inputs (`source`,
  `stdin`, `expected`) and program output (`stdout`, `stderr`). No base64. Build
  the body with a real JSON serializer (Jackson), never string concatenation; it
  escapes newlines/quotes/indentation in `source` for you.
- **Synchronous:** every call **blocks until judging finishes**, then returns the
  full result. No polling, no job ids. Set the client read timeout **above** the
  judge's `wall_timeout` (and well above for `/submit`, which runs every case).
- **Concurrency / overload:** the service runs a bounded pool of N worker slots
  (default = CPU cores) and queues the rest; many simultaneous calls are fine
  (synchronous does not mean one-at-a-time). When the in-flight count exceeds the
  configured limit it returns **429** — retry with backoff.

---

## Server configuration (`config.yaml`)

Read once at startup (path overridable via env `JUDGE_CONFIG`). Operational
knobs only — the backend doesn't set these, but they affect behavior:

| Key | Default | Meaning |
|---|---|---|
| `image` | `judge-sandbox:latest` | sandbox container image |
| `problems_dir` | `problems` | the problem pool: a flat dir holding one package per `problem_id` |
| `concurrency.max_workers` | CPU count | submissions run in parallel |
| `concurrency.max_queue_size` | `100` | total in-flight before `429` |
| `limits.max_custom_cases` | `10` | max custom stdins per `/run`; more → `400` |
| `timeouts.run_all_wall_seconds` | `60` | default per-run hard kill, seconds (the `wall_timeout` fallback) |
| `sandbox.memory_mb` | `1024` | per-run container memory cap |
| `sandbox.cpus` | `1.0` | CPU cap per run |
| `languages` | `c, cpp, java, python` | accepted `language` values |

The bapctools version is **not** here — it is baked into the image (Dockerfile
build arg); changing it needs an image rebuild.

---

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/submit` | **Grade** against ALL testcases (sample + secret). |
| `POST` | `/run` | **Self-test**: sample testcases + optional custom cases, full detail. |
| `GET`  | `/health` | Liveness -> `{"status":"ok"}` |
| `GET`  | `/openapi.json`, `/docs` | OpenAPI schema + browsable UI (auto-generated). |

A `2xx` means the judge ran successfully; a **student-side failure
(WA/TLE/RTE/MLE/CE) is also `200`** with the verdict in the body. Only *system*
problems are non-2xx (see [Errors](#errors)).

---

## `POST /submit`

Grade a submission against all testcases.

### Request body

| Field | Type | Required | Description |
|---|---|---|---|
| `problem_id` | string | yes | Problem directory name in the pool, e.g. `"babyshark"`. No `/`, `\`, or leading `.`. |
| `language` | string | yes | One of: `c`, `cpp`, `java`, `python`. |
| `source` | string | yes | Source code, plain text. |
| `wall_timeout` | integer | optional | Per-run hard ceiling in seconds (1–300). Defaults to `60`. Exceeding it returns `500` (the run is aborted), so set it generously for `/submit`. |

### Response `200` — `SubmitResponse`

```json
{
  "status": "WA",
  "passed": 7,
  "total": 9,
  "max_time_s": 0.21,
  "testcases": [
    { "name": "sample/1", "status": "AC", "time_s": 0.05,
      "input": "6 2\n2 2 4 4 0 0\n", "expected": "2 1 1 2 2 1\n",
      "stdout": "2 1 1 2 2 1\n", "stderr": "" },
    { "name": "secret/3", "status": "WA", "time_s": 0.04,
      "input": null, "expected": null, "stdout": null, "stderr": null }
  ],
  "compile_output": null
}
```

| Field | Type | Description |
|---|---|---|
| `status` | string | Overall verdict (worst across cases). Enum below. |
| `passed` / `total` | integer | Cases that got `AC` / total cases run. |
| `max_time_s` | number | Slowest case runtime, seconds. |
| `testcases[]` | array | One entry per case. |
| `testcases[].name` | string | e.g. `"sample/1"`, `"secret/3"`. |
| `testcases[].status` | string | Per-case verdict (enum below). |
| `testcases[].time_s` | number | Case runtime, seconds. |
| `testcases[].input` | string \| null | **Sample only.** `null` for secret. |
| `testcases[].expected` | string \| null | **Sample only.** `null` for secret. |
| `testcases[].stdout` | string \| null | Program output. Sample: always. Secret: only on `RTE` (else `null`). |
| `testcases[].stderr` | string \| null | Program stderr. Sample: always. Secret: only on `RTE` (else `null`). |
| `compile_output` | string \| null | Compiler diagnostic when `status == "CE"` (then `testcases` is empty); `null` otherwise. |

**Disclosure rule (important for the UI):**
- **Sample** cases -> full detail (`input`, `expected`, `stdout`, `stderr`).
- **Secret** cases -> `name`, `status`, `time_s` only; `input`/`expected` are
  **always `null`** (hidden test data). *Exception:* a secret case that **errors
  (`RTE`)** also returns `stdout`/`stderr` so students can debug a hidden-case
  crash — `input`/`expected` stay `null`. (Lab setting; for high-stakes exams
  this should be turned off.)

---

## `POST /run`

Run the **sample** testcases (always) plus optional **custom** cases (one per
entry in `custom_stdins`), with full per-case detail. For student self-testing.

### Request body

| Field | Type | Required | Description |
|---|---|---|---|
| `problem_id` | string | yes | Problem directory name in the pool. |
| `language` | string | yes | `c`, `cpp`, `java`, `python`. |
| `source` | string | yes | Source code, plain text. |
| `custom_stdins` | string[] | optional | Custom inputs; each adds an ungraded `"custom/N"` case (no expected). Over `limits.max_custom_cases` → `400`. |
| `stdin` | string | optional | **Deprecated:** single custom input (prefer `custom_stdins`); becomes `"custom/1"`. |
| `wall_timeout` | integer | optional | Per-run ceiling in seconds (1–300). Defaults to `60`. |

### Response `200` — `RunResponse`

```json
{
  "testcases": [
    { "name": "sample/1", "status": "AC", "time_s": 0.05,
      "input": "6 2\n2 2 4 4 0 0\n", "expected": "2 1 1 2 2 1\n",
      "stdout": "2 1 1 2 2 1\n", "stderr": "" },
    { "name": "custom/1", "status": null, "time_s": null,
      "input": "10 2\n", "expected": null,
      "stdout": "...\n", "stderr": "" }
  ],
  "compile_output": null
}
```

| Field | Type | Description |
|---|---|---|
| `testcases[].name` | string | `"sample/1"`, … or `"custom/1"`, `"custom/2"`, … |
| `testcases[].status` | string \| null | Verdict; **`null`** for custom cases (ungraded — no expected). |
| `testcases[].time_s` | number \| null | Runtime; `null` when not judged. |
| `testcases[].input` | string \| null | The case input. |
| `testcases[].expected` | string \| null | The answer; `null` when none. |
| `testcases[].stdout` | string | Program output ("got"). |
| `testcases[].stderr` | string | Program stderr (debug/traceback; bt chatter stripped, empty on clean run). |
| `compile_output` | string \| null | Set on a build failure (then `testcases` is empty); `null` otherwise. |

(`/run` only runs sample + custom cases — all public — so it always returns full
detail.)

---

## Verdict status enum

`status` (overall and per-case) is one of:

| Value | Meaning |
|---|---|
| `AC` | Accepted |
| `WA` | Wrong answer |
| `TLE` | Time limit exceeded |
| `RTE` | Runtime error (crash) |
| `MLE` | Memory limit exceeded |
| `CE` | Compile error (no cases run; see `compile_output`) |

`MLE` is reported when a crash is identifiably out-of-memory (Python
`MemoryError`, C++ `bad_alloc`, Java `OutOfMemoryError`); other OOMs may surface
as `RTE`. `JE` (judge/internal error) is **never** a case status — it surfaces as
HTTP `500`.

---

## Errors

Non-2xx responses use FastAPI's shape: `{ "detail": "<message>" }`.

| Status | Meaning | Retry? |
|---|---|---|
| `400 Bad Request` | invalid `problem_id`, unsupported `language`, or too many `custom_stdins` (over `limits.max_custom_cases`) | no — fix the request |
| `422 Unprocessable Entity` | malformed body / wrong types / missing required field | no — fix the request |
| `429 Too Many Requests` | judge at capacity (max in-flight reached) | **yes**, with backoff |
| `500 Internal Server Error` | judge/infra failure (`JE`) — container/orchestrator error | maybe (likely a bug to report) |
| `504 Gateway Timeout` | run didn't finish within the judge's sync window (overloaded) | **yes**, with backoff |

A student-side failure (`WA`/`TLE`/`RTE`/`MLE`/`CE`) is **`200`**, not an error.

---

## Examples

```bash
# Submit (grade)
curl -s -X POST http://localhost:8000/submit \
  -H 'Content-Type: application/json' \
  -d '{"problem_id":"babyshark","language":"python","source":"print(input())\n"}'

# Run on custom inputs
curl -s -X POST http://localhost:8000/run \
  -H 'Content-Type: application/json' \
  -d '{"problem_id":"babyshark","language":"python","custom_stdins":["6 2\n2 2 4 4 0 0\n"],"source":"print(input())\n"}'
```

(Build the JSON with a serializer in real code — see `curl.md` for `jq`-based
examples that escape multi-line source safely.)

---

## Integration notes (backend)

- **Read timeout:** set it above `wall_timeout`; for `/submit` allow generous
  headroom — it runs every testcase, and a TLE submission runs them all to the
  limit (e.g. tens of seconds on problems with many cases).
- **Map verdicts:** `AC` = accepted; `WA/TLE/RTE/MLE/CE` = not accepted; use
  `passed/total` for partial scoring (counts are complete and consistent).
- **`compile_output`:** when `status == "CE"`, show this to the student;
  `testcases` will be empty.
- **`language`, not a filename** — the file extension is derived from `language`.
- **`problem_id` is a pool entry.** The backend sends the problem slug; the judge
  resolves it against `problems_dir` (a flat pool, one package dir per slug). The
  pool must be present on the judge host's local filesystem.
- **Persistence:** the judge stores nothing durably. The backend is the system of
  record — persist the returned result against the student/assignment.
- **Retries:** judging has no judge-side side effects, so retrying `429`/`504`/
  `5xx` is safe and idempotent.
- **Concurrency:** the service runs several submissions in parallel and returns
  `429` past capacity; many simultaneous calls are fine (synchronous !=
  one-at-a-time).
