# kt-judge

Kotlin version of the code-execution judge. It compiles and runs student submissions
inside an ephemeral, hardened Docker container and returns a verdict.

It ships inside the backend fat jar and runs as a separate service selected by
Spring profile.

## Running

Requires Java 21

```bash
J=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
$J -jar backend-1.0-SNAPSHOT.jar --spring.profiles.active=judge --server.port=8000
```

Why `--server.port=8000` is passed explicitly: an external `application.properties`
in the run directory (the backend's config) outranks the bundled
`application-judge.properties`, so its port would otherwise win. A command-line
arg has the highest precedence.

Also required on the host:
- Docker running, with the `judge-sandbox:latest` image present.
- The problem pool reachable at the `pool_path` the backend sends, readable by
  both the service user and the container uid.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | liveness, returns `{"status":"ok"}` |
| POST | `/submit` | grade against all testcases (sample + secret) |
| POST | `/run` | run sample cases plus optional custom stdins, with full output |

Status codes: 400 (bad request, e.g. missing `pool_path` or unknown problem),
429 (too many jobs in flight), 504 (admitted but overloaded, safe to retry),
500 (judge/infra error).

Requests must include `pool_path` (the complete path to the problem pool); the
problem is resolved at `<pool_path>/<problem_id>`.

## Configuration

Read from `application-judge.properties` (prefix `judge.`). Defaults live in
`JudgeProperties.kt`. Current values:

| Key | Default | Meaning |
|---|---|---|
| `judge.image` | `judge-sandbox:latest` | sandbox container image |
| `judge.concurrency.max-workers` | 8 | max submissions running at once (that is, concurrent `docker run` containers) |
| `judge.concurrency.max-queue-size` | 100 | max jobs admitted (running + waiting); past this returns 429 |
| `judge.timeouts.run-all-wall-seconds` | 60 | hard wall-clock kill per request (compile + run all cases) |
| `judge.timeouts.custom-wall-seconds` | 30 | wall-clock kill for a custom run |
| `judge.limits.max-custom-cases` | 3 | max custom stdins per `/run`; more returns 400 |
| `judge.sandbox.memory-mb` | 2560 | per-container memory cap |
| `judge.sandbox.cpus` | 1.0 | CPU cap per container |
| `judge.sandbox.pids-limit` | 256 | max processes per container |
| `judge.sandbox.fsize-bytes` | 33554432 | max single-file write (32 MB) |
| `judge.sandbox.work-tmpfs-mb` | 512 | size of the container's `/work` tmpfs |
| `judge.sandbox.tmp-tmpfs-mb` | 128 | size of the container's `/tmp` tmpfs |
| `judge.sandbox.uid` / `gid` | 1000 | uid/gid the untrusted code runs as |
| `judge.languages.*` | c, cpp, java, python | accepted `language` values and their file extensions |

### Concurrency

`max-workers` is the number of containers allowed to run at the same time
(size it so `max-workers * memory-mb` stays under about 80 percent of host RAM).
`max-queue-size` is the total number of jobs the service will accept before it
starts rejecting with 429. A fixed thread pool of `max-workers` runs the jobs; a
semaphore of `max-queue-size` is the admission gate. A job that is admitted but
cannot finish within the wall timeout plus a margin returns 504.

Config is read once at startup, so changing any of these needs a restart.

## Notes

- `incontainer.py` stays Python. It runs inside the sandbox container (which has
  Python and bt), is shipped as a resource, and is mounted read-only per run.
- The old Python judge under `judge/` still exists. Cutover (retire it, point the
  systemd unit at the jar) is not done yet. See `PLAN.md`.
