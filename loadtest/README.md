# Phase 1 load test: git-write concurrency (autosave, submit, logout)

Validates the per-repo git lock (`GitService.withRepoLock`) under concurrent synthetic students.
Scoped to the three call chains that actually touch that lock — autosave, submit, and the logout
activity-log commit. Run (`/api/code/run`) never touches git and is intentionally left out. See
`../.claude/plans/elegant-crunching-teacup.md` (or ask) for the full design rationale; this file is just
the runbook.

**Everything here targets a second, disposable app instance and a throwaway database — never the real
deployment serving actual students.**

There are two tiers, run in order: a **quick verification pass** (small scale, few minutes — confirms the
whole pipeline actually works) and the **full run** (100 students, 75-minute steady state). Don't skip
straight to the full run — there's no point running it for 75 minutes against a pipeline that's broken.

## Directory layout

Deliberately kept as two directories with **unrelated names** — an earlier round of this test used
`loadtest/` for both the tooling checkout *and* (as `--repo-base`'s default) the disposable git repos,
and an `scp -r` onto an existing directory of the same name nested them into each other. Use distinct
names so that mistake can't repeat:

```
~/cs30loadtest/                  # wherever the jar lives — adjust to your actual path
├── cs30-1.0-SNAPSHOT.jar
├── scripts/                     # static tooling — scp this whole directory's CONTENTS here once
│   ├── README.md, gen-fixtures.py, k6/git-write-phase1.js, .gitignore
│   └── loadtest-application.properties   # static config, doesn't change per run — lives HERE, not data/
└── data/                        # everything a test RUN produces: git repos + generated fixtures + logs
    ├── students/, problems/     (git repos — created by addcourse's GitService.initGitRepo)
    ├── loadtest-course.yaml, seed-sessions.sql, students.json, local-ips.txt   (from gen-fixtures.py)
    └── loadtest-backend.log
```

Deploy the tooling with the trailing `/.` on the source (copies *contents* into an existing directory,
not a nested copy of the directory itself):
```bash
ssh <server> mkdir -p ~/cs30loadtest/scripts ~/cs30loadtest/data
scp -r /path/to/cs30/loadtest/. <server>:~/cs30loadtest/scripts/
```

All commands below assume `~/cs30loadtest/scripts` and `~/cs30loadtest/data` — substitute your actual
paths if they differ, and prefer absolute paths throughout (a mix of relative paths and `cd`s is exactly
what caused the directory confusion last time).

## 1. New database on the same Postgres server
```bash
createdb -h localhost -U cs30 cs30_loadtest
```
Schema auto-creates on first connection (`spring.jpa.hibernate.ddl-auto=update`) — no manual migration.

## 2. Generate fixtures (course roster, seeded sessions, k6 IP list — one source of truth)
```bash
cd ~/cs30loadtest/scripts
python3 gen-fixtures.py --count 20 --course-id CS30-LOADTEST-QUICK \
  --repo-base ~/cs30loadtest/data --out-dir ~/cs30loadtest/data   # quick pass
# python3 gen-fixtures.py --count 100 --course-id CS30-LOADTEST \
#   --repo-base ~/cs30loadtest/data --out-dir ~/cs30loadtest/data  # full run, later
```
Produces `students.json`, `loadtest-course.yaml`, `seed-sessions.sql`, `local-ips.txt` in `~/cs30loadtest/data`
(all gitignored — regenerate per run, don't commit). **Always pass `--repo-base`/`--out-dir` explicitly**
— relying on the script's default (`/home/joshini/loadtest`, a leftover from earlier drafting) is exactly
how the path/database mismatch happened before.

- The course's lab active window is always **[now, now + 1 month)** regardless of `--count` or how long a
  given k6 run's `LAB_MINUTES` is set to — it just needs to stay open across however many quick/full runs
  happen over the testing period, not model a real class period itself.
- **Use a different `--course-id` for the quick pass than the full run** (`CS30-LOADTEST-QUICK` vs.
  `CS30-LOADTEST`) — re-running `addcourse` with the same course code as an already-seeded course is
  untested territory; distinct IDs sidestep any collision/duplicate risk entirely.
- Quick pass and full run share the same `--repo-base` by default, and both use the
  `loadtest-student-0001@...` naming pattern, so the first 20 students' commits will appear in both runs'
  git history for the same repo. That's harmless for what this test measures (lock behavior, not clean
  per-run history).

## 3. Seed the course into the new DB

**Don't use `addcourse`'s own `--db-url`/`--db-user`/`--db-pass` flags** — they're wired via
`SpringApplication.setDefaultProperties()` (`Main.kt`), Spring Boot's **lowest**-precedence property
source, so they get silently overridden by the datasource URL baked into the jar's bundled
`application.properties` (which points at the real production `cs30db`). That's exactly what happened
the first time this was run — the course landed in production, not `cs30_loadtest`.

**Also don't pass `--spring.config.location=...` as a program argument** — `addcourse` routes through
picocli, which strictly rejects any option its `@Command` doesn't declare (only `--course-file` is
declared), so it fails with `Unknown option`.

**Use `-D` JVM system properties instead** (before `-jar`, not part of the program's `args[]`) — these
outrank the bundled classpath `application.properties`, and picocli never sees them since they're
consumed by the JVM itself:
```bash
java -Dspring.config.location=file:/home/joshini/cs30loadtest/scripts/loadtest-application.properties \
     -jar ~/cs30loadtest/cs30-1.0-SNAPSHOT.jar addcourse \
     --course-file /home/joshini/cs30loadtest/data/loadtest-course.yaml
```
This also runs `GitService.initGitRepo` against `studentGitRepo`/`problemGitRepo`
(`~/cs30loadtest/data/students`, `~/cs30loadtest/data/problems`), auto-`git init`-ing them on first run
(idempotent — safe to re-run against an already-initialized repo).

**Verify it landed in the right database before continuing:**
```bash
psql -h localhost -U cs30 -d cs30_loadtest -c "SELECT code FROM courses;"
```

## 4. Apply the seeded sessions
```bash
psql -h localhost -U cs30 -d cs30_loadtest -f ~/cs30loadtest/data/seed-sessions.sql
```

## 5. Boot a second app instance — new DB, different port, judge unreachable, whitelist disabled
Same `-D` approach as step 3 — `serve` doesn't route through picocli (so `--config=` works fine there
too), but using `-Dspring.config.location=` uniformly for both commands means one config mechanism, not two:
```bash
java -Dspring.config.location=file:/home/joshini/cs30loadtest/scripts/loadtest-application.properties \
     -jar ~/cs30loadtest/cs30-1.0-SNAPSHOT.jar serve \
     > /home/joshini/cs30loadtest/data/loadtest-backend.log 2>&1 &
```
`loadtest-application.properties` is a static file that lives in `scripts/` (it doesn't change between
runs, so `gen-fixtures.py` doesn't generate it) — should set: `server.port=8090`, `server.ssl.enabled=false`,
`spring.datasource.url=jdbc:postgresql://localhost:5432/cs30_loadtest` (+ username/password),
`cs30.allowed-ips=` (empty — disables the whitelist for this instance only), `judge.url=http://localhost:1`
(makes every judge call fail fast, letting Phase 1 skip standing up a real judge — see the plan's "key
simplifying fact"). Logs redirected to `loadtest-backend.log` for grepping lock-wait warnings afterward.

Rebuild the jar first if you haven't already this session: `./gradlew :cli:bootJar` — an old jar won't
have this session's git-lock/claim-set changes.

## 6. IP diversity for k6 (loopback — k6 runs on this same server)
```bash
for i in $(seq 2 21); do sudo ip addr add 127.0.0.$i/8 dev lo; done    # 20 IPs, quick pass
# for i in $(seq 2 101); do sudo ip addr add 127.0.0.$i/8 dev lo; done  # 100 IPs, full run
```
Cleanup after each test:
```bash
for i in $(seq 2 21); do sudo ip addr del 127.0.0.$i/8 dev lo; done
```
(If k6 ever runs from a separate machine on the LAN instead, use real routable IPs from that network
instead of loopback — coordinate with whoever manages it first, since claiming addresses already in use
causes real ARP/duplicate-IP conflicts on shared infrastructure.)

## 7. Pre-flight data verification (do this before touching k6 at all)
Confirms the setup is actually correct instead of discovering a problem partway through a run. Table/
column names below are read directly from `Course.kt`/`ScheduledLab.kt`/`Problem.kt`/`LoginSession.kt`.

```sql
-- Course row + dates
SELECT id, code, section, start_date, end_date, student_git_repo, problem_git_repo
FROM courses WHERE code = 'CS30-LOADTEST-QUICK';

-- Lab window, and whether the server would consider it active RIGHT NOW
SELECT sl.lab_number, sl.start_date_time, sl.end_date_time,
       (now() BETWEEN sl.start_date_time AND sl.end_date_time) AS would_be_active
FROM scheduled_labs sl JOIN courses c ON c.id = sl.course_id
WHERE c.code = 'CS30-LOADTEST-QUICK';

-- Problem registered on that lab
SELECT p.name, p.language FROM problems p
JOIN scheduled_labs sl ON sl.id = p.lab_id JOIN courses c ON c.id = sl.course_id
WHERE c.code = 'CS30-LOADTEST-QUICK';

-- Enrollment count matches --count
SELECT count(*) FROM course_students cs
JOIN courses c ON c.id = cs.course_id WHERE c.code = 'CS30-LOADTEST-QUICK';

-- Seeded sessions: right count, none already stale/logged-out
SELECT count(*) AS total,
       count(*) FILTER (WHERE logged_out_at IS NOT NULL) AS already_logged_out,
       count(*) FILTER (WHERE last_heartbeat_at < now() - interval '2 minutes') AS already_stale
FROM login_sessions;
```

Then a **live** smoke check against the booted instance — more authoritative than the SQL above, since it
exercises the real `isActive`/session-resolution code path instead of re-deriving it by hand:
```bash
cd ~/cs30loadtest/data
TOKEN=$(python3 -c "import json; print(json.load(open('students.json'))[0]['token'])")
curl -s -X POST http://localhost:8090/api/check-session -H "Authorization: Bearer $TOKEN"
# expect: {"hasActiveSession": true, "email": "loadtest-student-0001@example.test"}
```

And confirm both disposable repos actually initialized:
```bash
git -C ~/cs30loadtest/data/students log --oneline -5
git -C ~/cs30loadtest/data/problems log --oneline -5
```

If any of the above looks wrong, fix it now — every failure mode here is cheap to diagnose before running
k6 and expensive to untangle from a 20- or 100-VU run's output afterward.

## 8. Quick verification pass (do this first)
Goal: confirm the whole pipeline actually works — auth, autosave, submit (with the judge deliberately
failing), logout, and real git commits landing in the disposable repo — under a little real concurrency,
in well under 10 minutes total.
```bash
cd ~/cs30loadtest/scripts/k6
k6 run --local-ips=$(cat ~/cs30loadtest/data/local-ips.txt) \
  -e BASE_URL=http://localhost:8090 -e COURSE_ID=CS30-LOADTEST-QUICK \
  -e STUDENTS_JSON_PATH=/home/joshini/cs30loadtest/data/students.json \
  -e LAB_MINUTES=5 -e STAGGER_WINDOW_S=20 \
  git-write-phase1.js
```
20 VUs, ~5-minute simulated session, staggered over just 20 seconds. `STUDENTS_JSON_PATH` is required now
that `scripts/` and `data/` are separate directories — the script no longer assumes `../students.json`.

**What "working as expected" means here, concretely:**
- k6's own summary: `autosave accepted (202)` and `submit git-write succeeded` checks both ~100%.
- `grep "Timed out waiting" ~/cs30loadtest/data/loadtest-backend.log` → zero hits.
- **Actually look at the disposable repo, not just HTTP responses** —
  `git -C ~/cs30loadtest/data/students log --oneline -20` should show real commits from the
  autosave/submit/logout paths (author emails matching the synthetic students, commit messages/content
  containing the `autosave mock`/`submit mock` markers with strictly increasing sequence numbers — proof
  every call produced a genuinely new commit, not a silently-skipped no-op).
- Logout succeeds (`/api/web-logout` → 200) for all 20 VUs at the end.

If anything here fails, stop and diagnose before touching the full run.

## 9. Full run (later, only after step 8 passes)
```bash
cd ~/cs30loadtest/scripts
python3 gen-fixtures.py --count 100 --course-id CS30-LOADTEST \
  --repo-base ~/cs30loadtest/data --out-dir ~/cs30loadtest/data   # re-seed with 100 students
# repeat steps 3-7 with the new fixtures (100 IPs instead of 20)
cd k6
k6 run --local-ips=$(cat ~/cs30loadtest/data/local-ips.txt) \
  -e BASE_URL=http://localhost:8090 -e COURSE_ID=CS30-LOADTEST \
  -e STUDENTS_JSON_PATH=/home/joshini/cs30loadtest/data/students.json \
  git-write-phase1.js
```
`LAB_MINUTES`/`STAGGER_WINDOW_S` default to 75/300 when omitted — no code changes needed between tiers.

## 10. After the run
```bash
grep "waited" ~/cs30loadtest/data/loadtest-backend.log       # lock-wait warnings (>2s) — how high, how often
grep "Timed out waiting" ~/cs30loadtest/data/loadtest-backend.log   # hard 30s lock timeouts — should be zero
```
Check k6's own summary output for the `submit git-write succeeded` and `autosave accepted` check pass
rates — both should be effectively 100%.

## Cleanup
- Kill the load-test app instance (job you started in step 5).
- Remove the IP aliases (step 6's cleanup command).
- `dropdb -h localhost -U cs30 cs30_loadtest` once you're done analyzing results.
- `rm -rf ~/cs30loadtest/data` (disposable git repos + fixtures) — leaves `scripts/` untouched, so you can
  regenerate everything in `data/` from scratch any time without re-deploying the tooling.

## Deferred — Phase 2 (not part of this round)
Judge/Run capacity characterization (kt-judge, the actual live judge — not the Python service the
README/PLAN.md describe) is a separate, later round. See the plan file's "Deferred" section.
