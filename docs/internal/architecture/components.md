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
- **`ApiTokenStore`** is the session store. Despite the name it is not an in-memory map. It is backed by the `login_sessions` table through `LoginSessionRepository`. It issues tokens (one row per login), enforces one active session per student, refreshes the TTL on heartbeat, and ends sessions. It runs a scheduled sweep every 60 seconds to end sessions that stopped heartbeating. All the ways a session can end (explicit logout, heartbeat finding it expired, background sweep) funnel through one private `endSession` method, which publishes a `LogoutEvent` before marking the row logged out.

#### Code execution

- **`CodeService`** validates enrollment and the lab window, maps the language name to a file extension and a judge language code, and orchestrates the judge call plus the git save. It uses an atomic per-student lock so a double-click cannot start two runs for the same student at once. On submit it calls the judge first, then saves the code and result together so they share one timestamp.
- **`JudgeService`** is the HTTP client to the judge. It sends JSON to `POST /run` and `POST /submit` at `judge.url`. It pins HTTP/1.1 (a leftover from the Python judge, which ran on uvicorn).

#### Storage and content

- **`GitService`** does all filesystem and git work: writing autosaves, submissions, and activity logs into the student repo and committing them, and building problem statements into the problem repo. It serializes writes per repository with a lock because the whole working tree and a single `.git/index.lock` cannot be shared by concurrent git processes.
- **`ProblemService`** reads problem statements (`index.html`, `problem.css`, assets) from the problem git repo, with path-traversal guards, and rewrites relative image URLs to go through the asset endpoint.

#### Other services

`CourseService` and `LabService` back the CLI admin commands. `ActivityLogService` formats proctoring events as CSV rows. `DatabaseBackupService` takes a daily database backup. `LabHealthService` backs the TA readiness check. `LogoutActivityLogHook` listens for the `LogoutEvent` and commits the activity log when a session ends.

### Config

- **`IpWhitelistFilter`** (`backend/src/main/config/`) blocks requests from IPs outside an allowed list. The list comes from `cs30.allowed-ips`. If that setting is empty, the filter allows everything. When it blocks a request it returns a 403 with a styled "Access Restricted" page.
- **`WebConfig`** wires up static file serving for the web app.

## CLI (`cli/`)

Picocli. The entry point is `cli/src/main/Main.kt`. This module is what produces the shipping jar (`cs30-1.0-SNAPSHOT.jar`).

The first argument decides the mode. `serve` starts the backend web server. Anything else is treated as an admin command with the web server disabled. The admin subcommands include `addcourse`, `addstudent`, `removecourse`, `setta`, `addproblem`, `addproblems`, `removeproblem`, `updateproblemlanguage`, `cancellab`, `validatecourse`, and the Canvas pair `course2canvas` and `submissions2canvas`. `addcourse` reads a course definition from YAML. The CLI shares Spring beans, models, and repositories with the backend, which is why it can be in the same jar.

The Canvas commands are built on two backend services: `CanvasSyncService` reads the course, lab, problems, and students in one transaction and returns plain DTOs (the CLI has no open Hibernate session, so entities must not escape), and `CanvasClient` calls the Canvas REST API over the JDK HTTP client. They only ever write to Canvas.

## Data (`data/`)

Kotlin Multiplatform. Holds the serializable DTOs that both the frontend and backend use, so the two sides stay in agreement about request and response shapes. Examples include `SubmissionInfo` and the lockdown event types.

## Judge (`kt-judge/`)

Spring Boot 3, Kotlin. A standalone service. Its controller (`JudgeController`) exposes:

- `POST /run` and `POST /submit` to execute code
- `GET /health`, `GET /ready`, and `GET /selftest` for health and readiness checks

`JudgeRunner` builds and runs the hardened `docker run` command for one job. Each job runs in a throwaway container with `--network=none`, `--cap-drop=ALL`, `--security-opt=no-new-privileges`, a read-only root, tmpfs mounts for `/work` and `/tmp`, and limits on memory, CPU, process count, and file size. It runs as a non-root user (uid 1000). The student's source and a Python orchestrator (`incontainer.py`) are mounted read-only into the container. The orchestrator drives `bapctools` to compile once and run the test cases, then prints a JSON result that `JudgeRunner` parses back into per-testcase verdicts (AC, WA, TLE, RTE, MLE, CE). Sandbox limits and concurrency are configured through `JudgeProperties` (prefix `judge.` in the properties file).

The older Python judge in `judge/` implements the same HTTP contract and the same sandbox flags. It is not the one we build in CI.
