# kt-judge

The code-execution judge. It compiles and runs a student submission inside an
ephemeral, hardened Docker container and returns a verdict. It ships inside the
backend fat jar and runs as a separate service selected by a Spring profile.

## Running

Requires Java 21 and Docker.

```bash
java -jar <jar> --spring.profiles.active=judge --server.port=8000
```

`--server.port=8000` is passed explicitly because an `application.properties`
present in the run directory outranks the bundled `application-judge.properties`;
a command-line argument has the highest precedence and keeps the judge on its
own port.

The host also needs:
- Docker running, with the `judge-sandbox:latest` image built and present.
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

Read once at startup from `application-judge.properties` (prefix `judge.`).
Changing any value requires a restart. Defaults live in `JudgeProperties.kt`.

| Key | Default | Meaning |
|---|---|---|
| `judge.image` | `judge-sandbox:latest` | sandbox container image |
| `judge.concurrency.max-workers` | 8 | submissions allowed to run at once (concurrent containers) |
| `judge.concurrency.max-queue-size` | 100 | total jobs admitted (running plus waiting); past this returns 429 |
| `judge.timeouts.run-all-wall-seconds` | 60 | hard wall-clock kill per request (compile and run all cases) |
| `judge.timeouts.custom-wall-seconds` | 30 | wall-clock kill for a custom run |
| `judge.limits.max-custom-cases` | 3 | max custom stdins accepted on one `/run`; more returns 400 |
| `judge.sandbox.memory-mb` | 2560 | per-container memory cap |
| `judge.sandbox.cpus` | 1.0 | CPU cap per container |
| `judge.sandbox.pids-limit` | 256 | max processes per container |
| `judge.sandbox.fsize-bytes` | 33554432 | max single-file write (32 MB) |
| `judge.sandbox.work-tmpfs-mb` | 512 | size of the container `/work` tmpfs |
| `judge.sandbox.tmp-tmpfs-mb` | 128 | size of the container `/tmp` tmpfs |
| `judge.sandbox.uid`, `judge.sandbox.gid` | 1000 | uid and gid the untrusted code runs as |
| `judge.languages.*` | c, cpp, java, python | accepted `language` values and their file extensions |

## Concurrency

`max-workers` is the number of containers allowed to run at the same time. Size
it so that `max-workers * sandbox.memory-mb` stays under about 80 percent of host
RAM. `max-queue-size` is the total number of jobs the service accepts before it
rejects new ones with 429.

Internally a fixed thread pool of `max-workers` runs the jobs (each blocks on its
own `docker run`), and a semaphore of `max-queue-size` is the admission gate. A
job that is admitted but does not finish within the wall timeout plus a small
margin returns 504; the underlying run is still killed at the wall timeout.
