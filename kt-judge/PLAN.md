# Kotlin Judge — Port Plan (Shape B)

Port the Python/FastAPI judge to Kotlin so the whole system ships as **one fat jar**,
while the judge stays a **separate service** (separate JVM/process/port/user) selected by
Spring profile. No more host `pip install` / uvicorn process.

```
java -jar cs30.jar --spring.profiles.active=backend   # web + frontend, :8443
java -jar cs30.jar --spring.profiles.active=judge      # judge only,    :8000
```

Two `systemctl` units point at the same jar (optionally a `cs30.target` to start both).

---

## What stays the same (NOT ported)
- **Docker** + the **`judge-sandbox` image** + **bapctools** — still required on the judge host.
- **`incontainer.py`** — runs *inside* the sandbox container (which has python+bt). It stays
  Python; we ship it as a resource and mount it read-only at `/in/orch.py` (no rebuild to update).
- The **HTTP contract** (`/submit`, `/run`, `/health`, request/response JSON, status codes) — kept
  byte-compatible so the backend's existing `JudgeService` client needs **no change**.
- `pool_path` is **required** (current behavior); resolution is `<pool_path>/<problem_id>`.

## Module / build layout
- New Gradle module **`:kt-judge`** at `kt-judge/` (`kotlin("jvm")` library, package `com.cs30.judge`).
  - deps: `spring-boot-starter-web`, `jackson-module-kotlin`, `:data` (if we share models).
- `settings.gradle`: `include(":kt-judge")`.
- `:backend` adds `implementation(project(":kt-judge"))` so `bootJar` includes the judge classes.
  The single `bootJar` already bundles the frontend wasm — unchanged.
- **Launcher selects the app by profile** (keeps backend beans/JPA out of the judge context):
  - `JudgeApplication` — `@SpringBootApplication(scanBasePackages=["com.cs30.judge"],
    exclude=[DataSourceAutoConfiguration, HibernateJpaAutoConfiguration])`.
  - existing `Application` — backend, untouched.
  - `mainClass` → a small launcher that reads the active profile and boots the right primary source
    (`SpringApplicationBuilder(JudgeApplication).run(args)` vs `runApplication<Application>(args)`).
- Profile config files:
  - `application-judge.properties` → `server.port=8000` + `judge.*` knobs.
  - `application-backend.properties` (or default) → `server.port=8443` + datasource etc.

## Python → Kotlin mapping
| Python | Kotlin (`com.cs30.judge`) | Notes |
|---|---|---|
| `config.py` + `config.yaml` | `JudgeProperties` `@ConfigurationProperties("judge")` | image, sandbox{memoryMb,cpus,pidsLimit,fsizeBytes,workTmpfsMb,tmpTmpfsMb,uid,gid}, concurrency{maxWorkers,maxQueueSize}, timeouts{runAllWallSeconds,customWallSeconds}, limits{maxCustomCases}, languages map. **No** problems_dir. |
| `schemas.py` | request/response data classes | `@JsonProperty` snake_case (`problem_id`,`pool_path`,`custom_stdins`,`wall_timeout`,`time_s`,`compile_output`,`max_time_s`). |
| `models.py` | `Status` enum + `PRECEDENCE`, internal result types | `Verdict`, `TestcaseResult`, `RunCase`, `SubmitCase`, `SubmitResult`, `RunResult`, `RawRun`. |
| `parser.py` | `JudgeParser` | port 4 regexes verbatim (`_PER_CASE_RE`, `_BT_NOISE_RE`, `_BT_PATH_RE`, `_MEMORY_ERR_RE`), `parseRunOutput`, `stripBtNoise`, `cleanCompileOutput`, `isMemoryError`, worst-by-PRECEDENCE, CE-vs-JE heuristic. **Highest-risk port → unit tests.** |
| `runner.py` | `JudgeRunner` | `dockerFlags()` from config; `invoke()` builds `docker run … -v …:/problem:ro …` via `ProcessBuilder`; UTF-8 capture w/ replacement; **wall-timeout kill** via `--name kt-judge-<uuid>` + `docker kill` on timeout (closes a container-leak gap the Python `subprocess.run(timeout=)` has). `runAll`/`runSubmit`/`runSamples`, `_parse_submit`/`_parse_samples` (Jackson over orchestrator JSON) + MLE relabel + sample/secret detail rules. |
| `incontainer.py` | **stays Python** | shipped at `kt-judge/src/main/resources/incontainer.py`; extracted to a temp file once at startup and bind-mounted `:ro`. |
| `service.py` | `JudgeController` `@RestController` | `POST /submit`,`/run`; `GET /health`. `@ExceptionHandler`: `QueueFull→429`, `SyncTimeout→504`, `JudgeError→400`, other→500. |
| `store.py` | `JudgeStore` `@Service` | bounded `ThreadPoolExecutor` (maxWorkers) + in-flight counter → `QueueFull` at maxQueueSize; `submitSync`/`runSync`; `resolveProblemDir(problemId,poolPath)` (required path, plain-name guard, `is_dir`+`problem.yaml`); `extFor`; `submissionFilename` (Java public-class regex → `X.java`/`Main.java`, else `submission.<ext>`); temp staging (`chmod 644`); custom-stdins list + `maxCustomCases` cap. Exceptions `JudgeError`/`QueueFull`/`SyncTimeout`. |
| `__main__.py` (local CLI) | **deferred** | the `python -m judge all <dir> <code>` dev tool; port later or skip. |

## Phased implementation (each phase verifiable)
0. **Skeleton + wiring**: `:kt-judge` module, settings include, backend dep, launcher + `JudgeApplication`, profiles, a trivial `/health`. Verify `bootJar` runs both roles on the right ports.
1. **Config + models + schemas** (data classes, `@ConfigurationProperties`).
2. **Parser + unit tests** — capture real bt outputs from the Python judge for AC/WA/TLE/RTE/MLE/CE/JE (sample+secret+custom) and assert parity.
3. **Runner** — docker invoke + timeout/kill + incontainer resource mount.
4. **Store** — concurrency/queue, resolution, staging.
5. **Controller + error mapping** — wire end-to-end.
6. **Parity test** — run kt-judge (temp port, e.g. :8001) beside the Python judge; diff `/submit` & `/run` verdicts via the existing benchmark across all pool problems.
7. **Cutover** — point the `judge` systemd unit at the jar (`--spring.profiles.active=judge`, :8000), retire the Python service. (Optional: port the local CLI.)

## Risks / watch-items
- **Parser parity** with bt's text format — mitigated by captured-sample unit tests (phase 2) before trusting it.
- **Profile isolation** — judge profile must not start JPA/datasource (exclude autoconfig in `JudgeApplication`); judge needs no DB.
- **Container leak on timeout** — handle with `--name` + `docker kill`; verify with an infinite-loop submission.
- **uid/permissions** — same as today: the jar (judge profile) runs as a host user that can read `pool_path`, and packages must be readable by the container uid.

## Open decisions (confirm before/early in implementation)
1. Ports: judge **:8000**, backend **:8443** — correct?
2. Run kt-judge on a **temporary port (:8001)** during phase 6 to A/B against the Python judge before cutover? (recommended)
3. Request/response models: keep **judge-local** in `:kt-judge`, or share via `:data`? (recommend judge-local to start)
4. Port the local `python -m judge` CLI now, or defer? (recommend defer)
