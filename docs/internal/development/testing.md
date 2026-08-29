---
title: Testing
parent: Development
grand_parent: Internal
nav_order: 3
---

# Testing

## Running the tests

```bash
./gradlew test
```

Runs the tests across all modules. CI runs the same command; it's a required check for merging to `main`.

## What is tested today

The suite is small — being honest about that is more useful than implying coverage. What exists:

| Module | Tests | Roughly what they cover |
| --- | --- | --- |
| `backend` | `CourseServiceTest`, `CodeServiceTest`, `LabServiceTest`, `ProblemServiceTest`, `LabHealthServiceTest`, `StudentIdentityServiceTest`, `ApiTokenStoreTest`, `CanvasSyncServiceTest` | Service-layer logic; the Canvas one also reads a submission tree from a temp dir and refuses a problem name that is not in the lab |
| `backend` | `controller/HealthControllerTest`, `controller/CanvasSyncControllerTest` | The `/health` endpoint, and the CLI-token-gated Canvas endpoints: who gets in, 404 bodies, and the JSON shape the CLI parses (MockMvc slices) |
| `cli` | `CliTest`, `CliApplicationGatingTest`, `MainArgsParsingTest` | CLI command behavior with a fixture properties file and sample course YAML; the CLI token gate; global option parsing |
| `cli` | `CanvasCliSpecTest`, `CanvasAssignmentNameTest`, `CanvasCommentTest`, `CanvasMirrorTest` | The Canvas commands: option wiring, assignment naming, comment text, and the whole flow against a mocked server and a mocked Canvas |
| `cli` | `Cs30ApiClientTest`, `RemoteSettingsTest` | The CLI's client for the server against a stand-in HTTP server, and how the remote commands find their settings without Spring |
| `kt-judge` | `JudgeParserTest` | Parsing judge output into verdicts, including refusing a verdict when the run was cut short |
| `kt-judge` | `JudgeRunnerTest` | Counting a problem's graded testcases from its `data/` directory |
| `kt-judge` | `JudgeControllerTest` | The judge HTTP endpoints |
| `frontend` | `ContrastRatioTest` | Theme color-contrast logic (common test) |

`data` has no tests.

## How the tests are set up

- All JVM modules use JUnit 5 (`useJUnitPlatform()`).
- Backend and CLI tests use **MockK** and an **H2** in-memory database instead of PostgreSQL.
- `kt-judge` uses **springmockk** (`@MockkBean`); `HealthControllerTest`, `CanvasSyncControllerTest` (MockK beans from a `@TestConfiguration`) and `JudgeControllerTest` use `@WebMvcTest` + MockMvc.
- Frontend tests are Kotlin Multiplatform common tests (`kotlin.test`), so they run without a browser or desktop window.
- `JudgeSelfTest` in the judge's main source is not a unit test — it backs the `/selftest` endpoint, which grades a known-good solution end to end to prove the judge can actually run a job.

## Where the gaps are

No test exercises the full run/submit path (client → backend → judge → git). The session lifecycle, `GitService` storage, and the OAuth flow aren't directly covered either. If you change any of those, verify by running the affected piece.
