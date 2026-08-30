---
title: Components
parent: Architecture
grand_parent: Internal
nav_order: 2
---

# Components

This page describes each part of the system and what it is responsible for. File paths are relative to the repo root.

## Frontend (`frontend/`)

Compose Multiplatform. One codebase, two build targets:

- **Web (`wasmJs`)**: compiled to WebAssembly and bundled into the backend jar as static files. This is what a student uses in the browser. It is served from the backend root.
- **Desktop (`jvm`)**: a native desktop app the student installs and runs.

The shared code lives in `frontend/src/commonMain/kotlin/`. Platform-specific pieces use Kotlin's `expect`/`actual` mechanism, with implementations under `desktopMain/` and `wasmJsMain/`. The main areas are the code editor, the problem viewer, login/auth, the API client, and the lockdown (proctoring) controller.

The two targets differ mainly in login. The web app is same-origin with the backend, so after OAuth the backend redirects back to `/` with the token in the URL. The desktop app opens the system browser for OAuth and catches the redirect on a temporary `localhost` callback server, then uses the same Bearer token for API calls.

## Backend (`backend/`)

Spring Boot 3, Kotlin. Serves the REST API and the web frontend. The entry point is `backend/src/main/app/Application.kt`.

### Controllers

The controllers are in `backend/src/main/controller/`.

| Controller | Base path | What it does |
| --- | --- | --- |
| `OAuthController` | `/login`, `/callback`, `/api/*` | Student Google OAuth login and callback, logout, and the session heartbeat (`/api/check-session`) |
| `TaOAuthController` | `/ta/login`, `/ta/callback`, `/api/ta/*` | TA Google OAuth, verified against the course's TA email |
| `ProblemController` | `/api/problems` | Lists the problems in the active lab and serves problem statements (HTML, CSS, assets) |
| `LabController` | `/api/labs` | Lists a student's labs and reports remaining time for a lab |
| `CodeController` | `/api/code` | `run`, `submit`, and listing past submissions |
| `AutosaveController` | `/api/autosave` | Saves and reads the student's in-progress code |
| `ActivityController` | `/api/activity` | Receives proctoring events and commits the activity log |
| `TaController` | `/api/ta` | TA dashboard: sections, active sessions, stats, labs, and per-student activity |
| `LabHealthController` | `/api/admin/lab-health` | A readiness check for TAs before a lab |
| `HealthController` | `/health` | Liveness for the CI deploy gate; returns `{"status":"ok"}` |

### Services

The services in `backend/src/main/service/` hold the logic.

#### Identity and sessions

- **`StudentIdentityService`** resolves who is making a request. It reads the `Authorization: Bearer` header and nothing else. It never trusts an email in the request body or query. It also does a log-only check of whether the token is being used from the same IP it was issued to; a mismatch is logged but never blocks the request.
- **`CourseAccessService`** decides who may use the student app for a course and when. A member is an enrolled student or the course's TA (`Course.taEmail`), derived from the Course row on every request. A student is held to the lab window; the TA is not, so they can try any lab of their course at any time. Every student-facing gate — problem list and content, run, submit, autosave, lab list, activity log — asks this class rather than checking enrollment or `lab.isActive` itself.
- **`ApiTokenStore`** is the session store. Despite the name it is not an in-memory map. It is backed by the `login_sessions` table through `LoginSessionRepository`. It issues tokens (one row per login), enforces one active session per student, refreshes the TTL on heartbeat, and ends sessions. It runs a scheduled sweep every 60 seconds to end sessions that stopped heartbeating. All the ways a session can end (explicit logout, heartbeat finding it expired, background sweep) funnel through one private `endSession` method, which publishes a `LogoutEvent` before marking the row logged out.

#### Code execution

- **`CodeService`** validates membership and the lab window through `CourseAccessService`, maps the language name to a file extension and a judge language code, and orchestrates the judge call plus the git save. It uses an atomic per-student lock so a double-click cannot start two runs for the same student at once. On submit it calls the judge first, then saves the code and result together so they share one timestamp.
- **`JudgeService`** is the HTTP client to the judge. It sends JSON to `POST /run` and `POST /submit` at `judge.url`, and reads `GET /queue-status` to size each call's timeout and to report a queue count to students. It pins HTTP/1.1.

#### Storage and content

- **`GitService`** does all filesystem and git work: writing autosaves, submissions, and activity logs into the student repo and committing them, and building problem statements into the problem repo. It serializes writes per repository with a lock because the whole working tree and a single `.git/index.lock` cannot be shared by concurrent git processes.
- **`ProblemService`** reads problem statements (`index.html`, `problem.css`, assets) from the problem git repo, with path-traversal guards, and rewrites relative image URLs to go through the asset endpoint.

#### Other services

`CourseService` and `LabService` back the CLI admin commands. `ActivityLogService` formats proctoring events as CSV rows. `DatabaseBackupService` takes a daily database backup. `LabHealthService` backs the TA readiness check. `LogoutActivityLogHook` listens for the `LogoutEvent` and commits the activity log when a session ends.

### Config

- **`IpWhitelistFilter`** (`backend/src/main/config/`) blocks requests from IPs outside an allowed list. The list comes from `cs30.allowed-ips`. If that setting is empty, the filter allows everything. When it blocks a request it returns a 403 with a styled "Access Restricted" page.
- **`KioskGateFilter`** (`backend/src/main/config/`) requires proof that a request came from a lab kiosk, and runs immediately after the IP filter. The secret comes from `cs30.kiosk-secret`; empty disables it. See below.
- **`WebConfig`** wires up static file serving for the web app, and builds both filters' settings from configuration.

#### Kiosk attestation

Lab workstations run CS30 through a dedicated kiosk account. Nothing otherwise stops a student logging into the same workstation under their own account and reaching the app, escaping the kiosk environment and its lockdown enforcement. `IpWhitelistFilter` cannot catch that — it sees the network, not which OS account made the request, and both accounts share the machine's IP.

`KioskGateFilter` accepts a shared secret through two carriers, and the choice follows the client:

- **Windows lab, web app.** The launcher opens the kiosk browser at `/?kiosk=<secret>`. The filter matches the param, sets an `HttpOnly` `cs30_kiosk` cookie, and redirects to the same path without the secret, so it never lingers in the URL bar or history. The browser then sends that cookie on every subsequent request — page, bundle, every API call, the heartbeat, the logout beacon — and the filter re-verifies each one. No frontend web code participates.
- **Linux lab, desktop app.** The launcher exports the secret into the app's environment; `KioskSecretDesktop` reads it and every desktop HTTP call sends it as the `X-CS30-Kiosk` header. No cookie is involved.

**This is an environment attestation, not an identity.** It answers "did this come from a lab kiosk?", never "which student is this?". Identity still comes only from the Bearer token via `StudentIdentityService`, so the two layers are independent: a valid token with no attestation gets 403, and valid attestation with no token gets 401.

Two invariants worth knowing before changing this:

- **`/login` is exempt, and must stay exempt.** The desktop app opens Google OAuth in a *separate* system browser that holds no cookie and cannot send a header. Gating `/login` breaks desktop login outright, and the obvious workaround — appending the secret to the login URL — would hand that browser an attestation cookie and with it full web-app access outside the desktop lockdown. Every backend URL that browser visits (`/login`, `/callback`) is exempt, and its last hop is the desktop app's own localhost socket, so it never needs attestation and is never given any.
- **`/api/**` other than `/api/ta/**` must stay gated.** A hand-crafted Google auth URL can mint a real token through the exempt `/callback`; the gate is what stops that token being usable.

The secret must never reach `frontend/commonMain` (it also compiles to wasmJs, where page JavaScript could read it) and must never go in the `# Frontend properties` block of `application.properties`, which is read at Gradle build time and would compile it into the wasm bundle and the shared desktop installer.

It is a deterrent, not an authentication boundary: a browser cannot keep a secret from the person operating it, so one extraction defeats it until the secret is rotated. It depends on lab-image hardening outside this repo — the secret file readable only by the kiosk account, no student access to that account, and DevTools disabled in the kiosk browser.

## CLI (`cli/`)

Picocli. The entry point is `cli/src/main/Main.kt`. This module is what produces the shipping jar (`cs30-1.0-SNAPSHOT.jar`).

The first argument decides the mode. `serve` starts the backend web server. `doctor` and the Canvas pair run on their own, without a Spring application. Anything else is treated as an admin command with the web server disabled. The admin subcommands include `addcourse`, `addstudent`, `removecourse`, `setta`, `addproblem`, `addproblems`, `removeproblem`, `updateproblemlanguage`, `cancellab`, `validatecourse`, and the Canvas pair `course2canvas` and `submissions2canvas`. `addcourse` reads a course definition from YAML. The CLI shares Spring beans, models, and repositories with the backend, which is why it can be in the same jar.

The Canvas commands need no database or repository access, so they run from any machine: they read the lab plan and each student's best submission from the server through `Cs30ApiClient` (`GET /api/admin/canvas/lab` and `/lab/submissions` on `CanvasSyncController`, authenticated with the CLI token), and write to Canvas through `CanvasClient`, a JDK-HTTP-client wrapper for the Canvas REST API that lives in the CLI module. On the server, `CanvasSyncService` reads the course, lab, problems, and students in one transaction and returns plain DTOs (`backend/src/main/dto/CanvasDtos.kt`); the repo path never leaves it. They only ever write to Canvas.

## Data (`data/`)

Kotlin Multiplatform. Holds the serializable DTOs that both the frontend and backend use, so the two sides stay in agreement about request and response shapes. Examples include `SubmissionInfo` and the lockdown event types.

## Judge (`kt-judge/`)

Spring Boot 3, Kotlin. A standalone service with its own jar (`kt-judge.jar`) and its own port. It compiles and runs one student submission inside a throwaway Docker container and returns a verdict. Nothing is persisted — the caller is the system of record.

### Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/submit` | Grade against all testcases, sample and secret |
| `POST` | `/run` | Run sample cases plus optional custom stdins, with full output |
| `GET` | `/health` | Liveness. Returns `{"status":"ok"}` |
| `GET` | `/ready` | Readiness: Docker up and the sandbox image present. 200 when ready, 503 otherwise. Result cached about 5s |
| `GET` | `/selftest` | Grades a built-in known-good solution end to end and confirms AC. 200 when ok, 503 otherwise. Costs a container run, so use it on deploy or periodically, never for polling |
| `GET` | `/queue-status` | Load snapshot: `in_flight`, `max_workers`, `max_queue_size`. The backend calls this to size each submit/run timeout and to show students a queue count |

Every request carries `pool_path`, the full path to the problem pool. The problem is resolved at `<pool_path>/<problem_id>`.

| Code | Meaning |
| --- | --- |
| 200 | Verdict returned |
| 400 | Bad request: missing `pool_path`, unknown problem, unsupported language, too many custom cases |
| 429 | Queue full. Retry later |
| 500 | Judge or infrastructure error |
| 504 | Admitted, but the service is overloaded. Safe to retry |

### How one job runs

`JudgeRunner` builds a hardened `docker run` per job. The container gets `--network=none`, `--cap-drop=ALL`, `--security-opt=no-new-privileges`, a read-only root, tmpfs mounts for `/work` and `/tmp`, and caps on memory, CPU, process count and file size. It runs as uid 1000, not root. The problem package is mounted read-only; the submission is staged to a temp file and mounted alongside a Python orchestrator (`incontainer.py`). The orchestrator drives `bapctools` to compile once and run the cases, then prints JSON that `JudgeRunner` parses into per-testcase verdicts (AC, WA, TLE, RTE, MLE, CE). Untrusted code never runs on the host.

### Concurrency

A fixed thread pool of `judge.concurrency.max-workers` runs the jobs; each thread blocks on its own `docker run`. A semaphore of `judge.concurrency.max-queue-size` is the admission gate — past it, requests get 429.

A job that is admitted but does not finish within the wall timeout plus a small margin returns 504. The underlying container is still killed at `judge.timeouts.run-all-wall-seconds`, so a 504 does not leave work running.

Limits are read once at startup, so changing any of them needs a restart. See [configuration]({% link internal/deployment/configuration.md %}) for the keys and [the runbook]({% link internal/deployment/runbook.md %}#capacity) for how to size them.
