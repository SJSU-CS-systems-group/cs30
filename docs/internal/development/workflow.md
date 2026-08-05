---
title: Workflow
parent: Development
grand_parent: Internal
nav_order: 2
---

# Development workflow

## Branches and pull requests

Work happens on feature branches. `main` is protected:

- Changes reach `main` only through a pull request.
- A PR needs at least one approval. You cannot approve your own PR.
- The "Secret scan" and "Build & test" status checks must pass.

A merge to `main` triggers a production deploy. Treat merging to main as shipping. See [CI/CD]({% link internal/cicd.md %}).

## What happens on your branch

Every push that touches anything outside `docs/`, on any branch, runs the secret scan and the build-and-test job, and rebuilds the judge sandbox image if you touched `kt-judge/sandbox/`. Feature branches do not deploy.

Changes confined to `docs/` skip that pipeline entirely (`ci.yml` sets `paths-ignore: ['docs/**']`, so a docs typo cannot restart production) and instead run the `Docs` workflow, which builds the Jekyll site and, on `main`, publishes it to GitHub Pages.

If you want to try your branch's build on a real machine, download the `cs30-<sha>` jar artifact from the branch's Actions run and run it yourself, rather than deploying.

## Before you push

- Run `./gradlew test` locally. The same tests gate the merge.
- Do not commit secrets. The secret scan runs over full history, so a committed secret is a problem even if you remove it in a later commit. If you leak one, rotate it, do not just delete the commit.
- Keep `deploy/application.properties` free of real secret values. It uses `${...}` placeholders that are filled from the environment in production. See [configuration]({% link internal/deployment/configuration.md %}).

## Touching the judge sandbox

The sandbox image is only rebuilt when files under `kt-judge/sandbox/` change, and only pushed to GHCR from `main`. If you change how the sandbox is built (the Dockerfile, the entry script, the baked tool versions), expect the `docker-judge` job to build it. On a feature branch it builds to validate but does not push; on `main` it pushes `judge-sandbox:latest` and `:<sha>`, and the next deploy pulls `:latest` — that's the image the judge runs (`judge.image`).

### The versions it builds with

The compilers and tools baked into the image are build arguments at the top of `kt-judge/sandbox/Dockerfile`:

| Setting | Default | What it is |
| --- | --- | --- |
| `PYTHON_VERSION` | `3.12` | The Python interpreter, and the Debian base image |
| `JAVA_VERSION` | `21` | OpenJDK — `java` and `javac` |
| `CPP_STD` / `C_STD` | `gnu++23` / `gnu23` | The C++/C standard `g++` and `gcc` compile with |
| `BT_VERSION` | `2026.4.0` | bapctools. If you bump it, re-test `parser.py` |
| `BT_PIPE_BUFFER_BYTES` | `65536` | Pipe buffer bapctools requests per interactive channel — see below |

CI passes no `--build-arg`, so these defaults are what ships. To try a different version locally:

```bash
docker build --build-arg JAVA_VERSION=17 -t judge-sandbox:latest kt-judge/sandbox
```

The C/C++ standards are patched into bapctools' own `languages.yaml` while the image builds, so every grade uses the same standard and no problem carries its own compiler config. The build asserts that bapctools still defaults to `-std=gnu++20` before rewriting it, so a bapctools release that changes its default fails the build instead of silently compiling with the wrong standard.

`BT_PIPE_BUFFER_BYTES` is patched into bapctools' `interactive.py` the same way, and exists to keep interactive problems working under concurrency. bapctools asks for a 1 MiB pipe per interactive channel; the kernel refuses that with `EPERM` once the sandbox **uid's** total pipe pages cross `fs.pipe-user-pages-soft`. That budget is shared by every sandbox running at once, since they all run as the same uid without `CAP_SYS_RESOURCE`, so it is reached by concurrency rather than by any single grading. bapctools does not catch the error: `bt` either aborts, or hangs waiting on children that are already deadlocked, and in both cases discards the cases it had graded.

The budget has three multipliers, not two. `bt` also grades `cpu_count()/2` cases in parallel — its `-j` default, which the orchestrator deliberately leaves alone so grading stays parallel. On a 16-core host that is 16 containers × 8 jobs × roughly 4 pipes per interactive test, and the 16384-page kernel default therefore caps a single pipe at 32 pages:

| `BT_PIPE_BUFFER_BYTES` | pages | total pages | vs 16384 |
| --- | --- | --- | --- |
| 1 MiB (bapctools' own default) | 256 | 131072 | 8× over — fails |
| 256 KiB | 64 | 32768 | 2× over — still fails |
| **64 KiB** | 16 | 8192 | 2× headroom |

64 KiB is also Linux's default pipe size, so this asks the kernel for no increase at all — the same size bapctools' newer `get_pipe_size()` falls back to when `F_SETPIPE_SZ` is refused.

Patching the image rather than raising `fs.pipe-user-pages-soft` on the host keeps the fix working after a server rebuild, and avoids granting the sandbox `CAP_SYS_RESOURCE`. If `judge.concurrency.max-workers` changes, re-check that `max-workers × (cpu_count()/2) × 4 × (BT_PIPE_BUFFER_BYTES / 4096)` still fits inside the host limit.

**There is no setting for gcc/g++.** The compiler arrives with the base image, so changing `PYTHON_VERSION` can change the C/C++ compiler as a side effect — the `python:3.12-slim` tag follows Debian releases on its own, and is Debian 13 with gcc 14 today where it was Debian 12 with gcc 12 before. `CPP_STD` and `C_STD` only pick the standard that compiler is told to target, not the compiler itself. Note that gcc 14 does not implement all of C++23: `<flat_map>`, `<mdspan>` and `= delete("reason")` are missing, while everything else in common use works.

## Changing the database schema

There is no migration tool. The schema is whatever Hibernate derives from the entities, applied with `ddl-auto=update`. That makes adding columns easy but makes rollbacks risky: an older jar may not match a newer schema. If you change an entity in a way that changes the schema, call it out in the PR, because it affects whether a deploy can be safely rolled back.
