# CS30 — Student Coding Lab Editor

A proctored coding-lab platform for university CS courses. Students sign in with Google, open a lab problem
in an editor, run their code against sample tests, and submit for automatic grading. Submissions execute in a
locked-down Docker sandbox, and a proctoring layer records activity during exams.

Kotlin throughout: a Spring Boot backend, a Compose Multiplatform frontend targeting desktop and the browser,
an instructor CLI, and a separate judge service.

**Documentation is at [cs30.app](https://cs30.app).** This file covers only how to build and run the code.

## Requirements

- **JDK 21.** What CI uses and what the Gradle toolchain targets.
- **The Gradle wrapper.** Use `./gradlew`; do not install Gradle separately.
- **Docker**, to run the judge. Without it the judge starts but cannot execute code.

You do not need PostgreSQL locally — the backend falls back to an in-memory H2 database.

## Build and run

```bash
./gradlew build                  # compile everything
./gradlew test                   # run the test suite; same command CI runs

./gradlew :backend:bootRun       # backend on :8080, serving the web frontend
./gradlew :frontend:run          # desktop app, in a second terminal
```

To run the judge as well, so Run and Submit actually execute code:

```bash
docker build -t judge-sandbox:latest kt-judge/sandbox
./gradlew :kt-judge:bootRun      # listens on judge.port, default 8000
```

The shipping jar bundles the backend, the web frontend and the CLI:

```bash
./gradlew :cli:bootJar           # -> cli/build/libs/cs30-1.0-SNAPSHOT.jar
java -jar cli/build/libs/cs30-1.0-SNAPSHOT.jar serve        # start the server
java -jar cli/build/libs/cs30-1.0-SNAPSHOT.jar --help       # or run an admin command
```

Full local setup, including OAuth credentials and loading a course so there is a problem to open:
[cs30.app/internal/development/setup](https://cs30.app/internal/development/setup/).

## Documentation

| Topic | |
|---|---|
| Everything, indexed | <https://cs30.app/> |
| **Architecture** — the system on one page | <https://cs30.app/internal/architecture/overview/> |
| Components, module by module | <https://cs30.app/internal/architecture/components/> |
| Request flows: login, run, submit, autosave, proctoring | <https://cs30.app/internal/architecture/data-flow/> |
| Data model | <https://cs30.app/internal/architecture/data-model/> |
| Backend API reference | <https://cs30.app/internal/api/> |
| **Deployment** — what runs where, and how a release gets there | <https://cs30.app/internal/deployment/overview/> |
| Configuration: every setting and where secrets come from | <https://cs30.app/internal/deployment/configuration/> |
| Runbook: operations, capacity, troubleshooting | <https://cs30.app/internal/deployment/runbook/> |
| CI/CD pipeline | <https://cs30.app/internal/cicd/> |
| **Local setup** | <https://cs30.app/internal/development/setup/> |
| Branches, PRs and what a merge triggers | <https://cs30.app/internal/development/workflow/> |
| Testing: what is covered and what is not | <https://cs30.app/internal/development/testing/> |
| **Getting started** for instructors and TAs | <https://cs30.app/external/getting-started/> |
| Setting up a course | <https://cs30.app/external/usage/> |
| CLI command reference | <https://cs30.app/external/cli-reference/> |
| Contributing | <https://cs30.app/external/contributing/> |

## Layout

```
data/        shared models and interfaces; Kotlin Multiplatform, no platform dependencies
backend/     Spring Boot server — controllers, services, JPA repositories
frontend/    Compose Multiplatform UI; desktop JVM and wasmJs from one source set
cli/         instructor CLI, and the composition root that builds the shipping jar
kt-judge/    the judge: its own service and jar, runs submissions in Docker
deploy/      systemd units and the production properties file
docs/        the documentation site published at cs30.app (Jekyll)
scripts/     one-time server setup and permission scripts
templates/   course and lab YAML templates
loadtest/    k6 load-test scripts and measured results
```

`:frontend` and `:backend` both depend on `:data` and never on each other; the frontend reaches the backend
over HTTP at runtime only.
