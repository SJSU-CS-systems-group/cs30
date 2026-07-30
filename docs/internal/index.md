---
title: Internal
nav_order: 2
has_children: true
---

# CS30 internal docs

These are the docs for the people who build and run CS30. If you are a student or an outside contributor, see the [external docs]({% link external/index.md %}) instead.

## What CS30 is

CS30 is a proctored online coding-lab platform for university CS courses. A student logs in with their SJSU Google account, opens a lab problem during its scheduled window, writes code in an editor, runs it against sample tests, and submits it for grading. Submitted code runs inside a locked-down Docker sandbox so untrusted student code cannot touch the host. During a lab the client also records proctoring activity (focus loss, clipboard use, fullscreen exit) to a log.

## The codebase in one screen

CS30 is a single Gradle monorepo with five modules.

| Module | What it is | Language / framework |
| --- | --- | --- |
| `backend/` | REST API, Google OAuth, JPA persistence, and it also serves the web UI | Kotlin, Spring Boot 3 |
| `frontend/` | The student UI, built once and run in two places: browser (wasm) and desktop (JVM) | Kotlin, Compose Multiplatform |
| `cli/` | Admin commands for courses and problems. Also the module that packages the shipping jar | Kotlin, Picocli |
| `data/` | Serializable DTOs shared between frontend and backend | Kotlin Multiplatform |
| `kt-judge/` | The judge service that compiles and runs submissions in a Docker sandbox | Kotlin, Spring Boot 3 |

There is also a `judge/` directory containing an older Python version of the judge. `kt-judge` is the one the CI pipeline builds and the one we treat as current.

A key thing to understand up front: `backend`, `frontend`, and `cli` are compiled into **one fat jar**. The CLI is the entry point. Running it with the `serve` argument starts the backend, which serves the compiled web frontend as static files. Any other argument runs an admin command instead.

## How to navigate these docs

- **[Architecture]({% link internal/architecture/overview.md %})** — the components, how a request flows through them, and the data model.
- **[Deployment]({% link internal/deployment/overview.md %})** — how the jar gets onto the server and runs, plus an operational runbook.
- **[CI/CD]({% link internal/cicd.md %})** — what the GitHub Actions pipeline does.
- **[Development]({% link internal/development/setup.md %})** — how to build and run everything locally.
- **[Backend API reference]({% link internal/api.md %})** — every HTTP endpoint the backend exposes.

## A note on accuracy

These pages were written by reading the source, not by copying the older docs in the repo. Some of those older docs have drifted from the code. If a page here disagrees with the code, the code wins, and the page is a bug. Please fix it or open an issue.
