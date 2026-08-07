---
title: Local setup
parent: Development
grand_parent: Internal
nav_order: 1
---

# Local setup

How to build and run the pieces on your own machine.

## Prerequisites

- **JDK 21.** This is what CI uses and what the Gradle toolchain targets.
- **The Gradle wrapper.** Use `./gradlew`, which pins the Gradle version. Do not install Gradle separately.
- **Docker**, if you want to run the judge for real. The judge shells out to `docker run`, so without Docker you can build and start the judge but code execution will fail.
- **Ruby 3.1+ and Bundler**, only if you want to work on this documentation site (it is a separate Jekyll project under `docs/`). Build it with `cd docs && bundle install && bundle exec jekyll serve`. macOS system Ruby (2.6) is too old — install a newer one with Homebrew or a version manager.

You do not need to install PostgreSQL to run locally. The backend depends on an H2 in-memory database at runtime, so it can start without an external database.

## Building

Build everything and run the tests:

```bash
./gradlew build
./gradlew test
```

Build the shipping jar (backend + frontend + CLI in one):

```bash
./gradlew :cli:bootJar
# produces cli/build/libs/cs30-1.0-SNAPSHOT.jar
```

## Running the backend

The backend's main class is `com.cs30.server.app.ApplicationKt`. Run it with:

```bash
./gradlew :backend:bootRun
```

The `application.properties` bundled into the jars (copied from the repo root at build time) is intentionally empty — production supplies real config through an external file (`deploy/application.properties`, copied to `/opt/cs30` and loaded via `--spring.config.additional-location`). For local runs, `:backend:bootRun` activates the `local` Spring profile; put local-only database or OAuth settings in a gitignored `application-local.properties` overlay rather than committing them.

You can also run the built jar directly. The first argument selects the mode:

```bash
# start the server
java -jar cli/build/libs/cs30-1.0-SNAPSHOT.jar serve

# run an admin command instead
java -jar cli/build/libs/cs30-1.0-SNAPSHOT.jar validatecourse ...
```

## Running the frontend

The web frontend is normally exercised just by running the backend, which serves the compiled wasm bundle at its root. For a standalone frontend dev server, the Kotlin wasm browser target provides a dev-run task (`:frontend:wasmJsBrowserDevelopmentRun`). The desktop app is a Compose desktop application with main class `app.MainKt`:

```bash
./gradlew :frontend:run
```

If a task name has changed, list what is available with `./gradlew :frontend:tasks`.

### Packaging the desktop app

Students install a native package rather than running Gradle. The JRE is bundled, so they need no Java, no Gradle and no tunnel. `frontend/build.gradle.kts` declares three target formats:

```bash
./gradlew :frontend:packageDmg    # macOS  -> frontend/build/compose/binaries/main/dmg/
./gradlew :frontend:packageMsi    # Windows
./gradlew :frontend:packageDeb    # Linux
```

Build these on the OS you are targeting; jpackage does not cross-compile.

**The backend URL is baked in at build time.** The desktop app reads `cs30.backend.url` from `application.properties` when it is compiled, not when it runs, so a package built against a local URL will keep pointing at localhost no matter where it is installed. Set the production URL before building, and rebuild after any change to it.

## Running the judge

The judge is a separate Spring Boot service. Its main class is `com.cs30.judge.JudgeApplicationKt`, and it listens on `judge.port` (default 8000), set by a small customizer so it does not collide with the backend's port.

```bash
./gradlew :kt-judge:bootRun
# or build a jar
./gradlew :kt-judge:bootJar
```

For the judge to actually run code, Docker must be available and the sandbox image (`judge-sandbox:latest`) must be built. The image is built from `kt-judge/sandbox/`. CI builds and pushes it, but for local work you build it yourself from that directory.

## Putting it together locally

To exercise the full run/submit path locally you need: the backend running, the judge running with `judge.url` pointing at it, the sandbox image built, and a course loaded from YAML via the CLI so there is a problem to open. This is more setup than most changes need, so lean on the tests and run only the piece you are changing when you can.
