# kt-judge

The code-execution judge. It compiles and runs a student submission inside an
ephemeral, hardened Docker container and returns a verdict. It is its own
executable jar (`kt-judge.jar`).

## Running

Requires Java 21 and Docker.

```bash
java -jar kt-judge.jar
```

Build it with `./gradlew :kt-judge:bootJar` (output in `kt-judge/build/libs/`).
It listens on `judge.port` (default 8000), read from `application.properties` in
the run directory. `judge.port` is a dedicated key rather than `server.port`, so
the judge and backend can share one `application.properties` without their ports
colliding.

The host also needs:
- Docker running, with the `judge-sandbox:latest` image present. Build it from
  the bundled context: `docker build -t judge-sandbox:latest kt-judge/sandbox`
  (holds the `Dockerfile`, `entry.sh`, and the `seed-problem` used to pre-bake
  the default validator).
- The problem pool reachable on disk, readable by both the service user and the
  container uid (default 1000).

## How it works

Each request runs one `docker run` against the `judge-sandbox` image with the
problem package mounted read-only. The submission is staged to a temp file,
compiled and executed inside the container, and the per-testcase results are
parsed back into a verdict. Untrusted code only ever runs inside the container,
never on the host. Nothing is persisted; the caller is the system of record.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness, returns `{"status":"ok"}` |
| GET | `/ready` | readiness: Docker up and sandbox image present. 200 when ready, 503 otherwise (result cached ~5s) |
| GET | `/selftest` | grades a built-in known-good solution end to end and confirms AC. 200 when ok, 503 otherwise. Costs a container run; use on deploy / periodically, not for polling |
| POST | `/submit` | grade against all testcases (sample and secret) |
| POST | `/run` | run sample cases plus optional custom stdins, with full output |

Every request must include `pool_path`, the complete path to the problem pool;
the problem is resolved at `<pool_path>/<problem_id>`.

Status codes:

| Code | Meaning |
|---|---|
| 200 | verdict returned |
| 400 | bad request (missing `pool_path`, unknown problem, unsupported language, too many custom cases) |
| 429 | too many jobs in flight; retry later |
| 504 | admitted but the service is overloaded; safe to retry |
| 500 | judge or infrastructure error |

## Configuration

Read once at startup (prefix `judge.`); changing a value needs a restart. Put
any keys you want to change into `application.properties` in the run directory,
or pass a file with `--spring.config.additional-location=file:/path/to/application.properties`.
With no config present the judge uses these defaults (also in `JudgeProperties.kt`):

```properties
# HTTP port the judge listens on
judge.port=8000
# sandbox container image (must be built and present in the local Docker)
judge.image=judge-sandbox:latest
# concurrent containers. Defaults to the host CPU core count if left unset;
# pin it only if max-workers times sandbox.memory-mb exceeds about 80% of host RAM
#judge.concurrency.max-workers=8
# total jobs admitted (running plus waiting); past this new requests get 429
judge.concurrency.max-queue-size=100
# hard wall-clock kill per request in seconds (compile plus run all cases)
judge.timeouts.run-all-wall-seconds=60
# max custom stdins accepted on one /run; more returns 400
judge.limits.max-custom-cases=3
# per-container memory cap, MB
judge.sandbox.memory-mb=2560
# CPU cap per container
judge.sandbox.cpus=1.0
# max processes per container
judge.sandbox.pids-limit=256
# max single-file write, bytes (32 MB)
judge.sandbox.fsize-bytes=33554432
# size of the container /work tmpfs, MB
judge.sandbox.work-tmpfs-mb=512
# size of the container /tmp tmpfs, MB
judge.sandbox.tmp-tmpfs-mb=128
# uid the untrusted code runs as
judge.sandbox.uid=1000
# host group NAME the container runs as; its GID is resolved on this host at
# runtime (getent), so no numeric GID is hardcoded. Unset/unresolvable falls back
# to uid. e.g. judge.sandbox.group=cs30
judge.sandbox.group=
# accepted languages and their source file extensions
judge.languages.c=.c
judge.languages.cpp=.cpp
judge.languages.java=.java
judge.languages.python=.py
```

## Concurrency

`max-workers` is the number of containers allowed to run at the same time. Size
it so that `max-workers * sandbox.memory-mb` stays under about 80 percent of host
RAM. `max-queue-size` is the total number of jobs the service accepts before it
rejects new ones with 429.

Internally a fixed thread pool of `max-workers` runs the jobs (each blocks on its
own `docker run`), and a semaphore of `max-queue-size` is the admission gate. A
job that is admitted but does not finish within the wall timeout plus a small
margin returns 504; the underlying run is still killed at the wall timeout.

## Per-testcase time limit

The judge's `run-all-wall-seconds` is only an overall safety wall for the whole
container run. The real **per-testcase** limit, what decides AC vs TLE for each
case, comes from the **problem package**, enforced by `bt` (bapctools) inside the
sandbox. The judge passes no time flag; `bt` reads it from `problem.yaml`.

Set it in the problem's `problem.yaml`, in seconds:

```yaml
limits:
  time_limit: 8        # seconds (floats allowed, e.g. 2.5)
```

If `time_limit` is omitted, `bt` falls back to a default of **1 second** (and
`bt validate` warns). The submission is actually killed at a safety margin above
the limit (~2×), so an unset problem TLEs around ~2s.

Don't hand-pick the number, let `bt` derive it from the accepted solutions:

```bash
cd <problem>
bt time_limit     # measures AC solutions and sets a time_limit with margin
bt validate       # warns if time_limit is missing / package issues
```

A problem with no `time_limit` (or built with a mismatched `bt` version) is a
common cause of an accepted solution grading `TLE` or a lab problem coming back
`0/0`; setting `limits.time_limit` and regenerating with a matching `bt` fixes it.
