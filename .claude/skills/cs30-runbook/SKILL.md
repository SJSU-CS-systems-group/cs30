---
name: cs30-runbook
description: Launch the CS30 backend (Spring Boot), frontend (desktop JVM or wasmJs web), or the unified CLI+backend jar. Use when asked to run, start, build, or screenshot the app, or to bring up any tier for manual testing.
---

# Running CS30

The project has five Gradle modules (`settings.gradle.kts`): `:frontend`, `:backend`, `:data`, `:cli`, `:kt-judge`.
`:cli` and `:kt-judge` are real modules with their own `build.gradle.kts`/`src` — they are **not** excluded from
the build.

- `:backend` — Spring Boot server. Serves the Google OAuth flow **and** the bundled wasmJs frontend. `processResources`
  copies the production wasm distribution (`:frontend:wasmJsBrowserDistribution`) into `classpath:/static`; both
  `bootJar` and `bootRun` consume `processResources`, so the artifact is self-contained.
- `:cli` — Wraps `:backend` plus instructor tooling (course/section/student/problem management) into one unified
  jar (`cli/build/libs/cs30-1.0-SNAPSHOT.jar`), built via `:cli:bootJar`. `java -jar ... serve` starts the same
  server `:backend:bootRun` would; other subcommands (`addcourse`, etc.) run without starting a web server. This is
  the actual production deploy artifact — see the root `README.md` "Unified Jar" section for the full deploy/redeploy
  flow. **Name note:** the module is called `:cli` for historical reasons (it started as just the instructor CLI
  tool); `serve` was added later as one more subcommand rather than a separate module, so `:cli` is now the
  composition root for the whole product, not only where the instructor commands live.
- `:frontend` — Compose Multiplatform UI. Targets `desktop` (JVM/Swing) and `wasmJs` (browser).
- `:data` — shared Kotlin models.
- `:kt-judge` — code-execution judge integration (separate from the Python/FastAPI judge service mentioned in
  the root README; see `kt-judge/README.md` or `kt-judge/build.gradle.kts` for how to run it independently — not covered by this skill).

## Configuration — one root-level `application.properties`, not env vars

All configuration lives in a single `application.properties` at the **repo root**, read via Spring `@Value` in the
backend, at Gradle build time by the frontend, and at startup by the CLI. There is a `application-local.properties`
too for local overrides (both are gitignored — never commit real credentials into either). See the root
`README.md` "Configuration" section for the full property list and explanations; the essentials to get OAuth
working locally:

```properties
google.client-id=<from Google Cloud Console>
google.client-secret=<from Google Cloud Console>
google.redirect-uri=http://localhost:8080/callback   # must be registered in the Google OAuth console
spring.datasource.url=<jdbc-url>                       # PostgreSQL, MySQL, H2, etc. — any Spring JPA-compatible DB
```

There is **no `CS30_PROBLEMS_PATH`/`CS30_COURSE_ID`/`CS30_LAB_ID`/`CS30_ACTIVITY_LOG_DIR` env-var flow** — those
don't exist in the current backend at all. Problems are always resolved from the database, via the authenticated
student's course enrollment (`CourseRepository.findByStudentEmail`) — there's no local-disk bypass for problem
content. The only environment variable the backend actually reads is `SPRING_PROFILES_ACTIVE`
(`backend/src/main/app/Application.kt`), and `./gradlew :backend:bootRun` already sets `spring.profiles.active=local`
for you (`backend/build.gradle.kts`).

## Web flow (backend serves wasm frontend)

```bash
./gradlew :backend:bootRun
```

Then open `http://localhost:8080` (or whatever `server.port` is set to). This bundles and serves the wasm build
at `/` and handles OAuth at `/login`, `/callback`, `/logout`. Alternatively, `./gradlew :cli:bootRun` runs the
same server through the unified CLI+backend module (per the root README's "local web testing" note) — either
works for local dev; `:backend:bootRun` is the lighter loop if you aren't touching CLI code.

## Desktop flow

```bash
./gradlew :backend:bootRun      # terminal 1
./gradlew :frontend:run         # terminal 2
```

The desktop app reads `cs30.backend.url` from `application.properties` at **build time**. For local testing, set these three properties in `application.properties` (or `application-local.properties`):
```properties
cs30.backend.url=http://localhost:8080
google.redirect-uri=http://localhost:8080/callback
cs30.kiosk-secret=          # leave empty to disable the kiosk gate locally
```

## Frontend-only web dev server (no backend, no auth)

```bash
./gradlew :frontend:wasmJsBrowserDevelopmentRun
```

Opens on its own port (usually `:8081`). Use this only when iterating on UI without touching the backend — `/login`
and any backend-data screen (e.g. the problem list) will not work.

## Production build & deploy

The unified jar is the real deploy artifact. Full deploy steps (build, SCP to server, systemd restart, verify) are in `docs/internal/deployment/runbook.md` or at `https://cs30.app/internal/deployment/runbook/`.

```bash
./gradlew :cli:bootJar
# -> cli/build/libs/cs30-1.0-SNAPSHOT.jar   (backend + CLI + bundled production wasm frontend)
```

```bash
java -jar cs30-1.0-SNAPSHOT.jar serve --config=./application.properties
```

## Backend Dependencies

- Any Spring JPA-compatible database (PostgreSQL, MySQL, H2 — see `spring.datasource.*` in `application.properties`)
- SSH access to a git server (problem statements + student autosave/activity-log commits)
- Google OAuth credentials
- A running judge service (`judge.url` in `application.properties`) for actual code execution

If SSH/git isn't configured, the backend still starts, but operations that write to git (autosave, activity logs)
fail and log a warning rather than crashing the request.

## Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| Backend starts, `/login` fails | Missing/wrong `google.client-id`/`google.client-secret`/`google.redirect-uri` | Set them in `application.properties` (or `application-local.properties`), matching a redirect URI registered in the Google OAuth console |
| Frontend can't reach backend | Backend not running / wrong port | Run `:backend:bootRun` first; check `server.port` in `application.properties` |
| Problem list is empty / 404 | Student not enrolled in any course in the DB | Enroll the student via the CLI (`addcourse`/enrollment commands) — there's no local-disk fallback |
| Activity logs / autosave not saved | No SSH config for the git server | Set `git.server.ssh-host`/`git.server.ssh-user` in `application.properties` |
| Web login succeeds but API calls 401 | Session cookie not round-tripping (host/scheme mismatch) | OAuth must run same-origin on the host the browser used — `google.redirect-uri` must match that exact host and be registered in the Google console |

## Gradle Tasks

```bash
# Clean & build all modules
./gradlew clean build

# Just the frontend (both targets)
./gradlew :frontend:build

# Just the desktop target
./gradlew :frontend:desktopMainClasses

# Just the web target
./gradlew :frontend:compileKotlinWasmJs

# Just the backend
./gradlew :backend:build

# Unified backend+CLI production jar
./gradlew :cli:bootJar

# Run tests
./gradlew test
```
