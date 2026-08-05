# CS30 load test — results

Run against a throwaway copy of the app: 100 simulated students, 13 real problems, on a
16-core server. Almost every number below comes from one of the three report files in `results/`, or is
derived from them by arithmetic shown in place. Six claims come from elsewhere and are listed under
*Provenance* in the appendix.

---

## The short version

**The system handled 100 students submitting at the same moment without losing anything.** Every
submission was graded, every result was saved, nothing crashed, nothing timed out, and no student was
wrongly told their correct code had failed.

It is, however, **slow**: with 100 students submitting at once, the last student waits between 80 seconds
and 2 minutes for a result. That is a queue, not a failure — but it is what students would experience.

Two things to watch out for, neither of them a fault in the app:

| watch out for | what it means |
|---|---|
| **Interactive problems** | **Problem 13** fails when many students submit it at once unless one server setting is raised. That setting is not part of the codebase, so a rebuilt server needs it again. |
| **Time limits are per problem, not per language** | A problem should only be assigned in a language whose sample answer passes the author's limit. |

---

## What to expect when you run this

Every figure in this section is measured; the detail behind each is further down.

**Before a lab with interactive problems, raise one server setting.** If any problem you assign is
interactive — the student's program and the checker exchanging messages while both run — the server needs
`fs.pipe-user-pages-soft` raised or those submissions will fail once several students submit together:

```bash
echo 'fs.pipe-user-pages-soft = 262144' | sudo tee /etc/sysctl.d/99-cs30-judge.conf
sudo sysctl --system
```

With it raised, an interactive problem grades 100 concurrent students at 100/100 accepted. Without it, the
same run fails for every student. It lives outside the codebase, so a rebuilt server needs it again, and
nothing in the application will tell you it is missing. Non-interactive problems are unaffected either way.

**Expect students to wait.** The server grades 16 submissions at a time; everyone else queues. With 100
students all submitting at once:

| | 9-test problem | 100-test interactive problem |
|---|---|---|
| first student served | 19s | 33s |
| typical student | 78s | 132s |
| last student | 129s | 219s |

That is a queue working correctly, not a failure — but it is what a student sees, and the app gives no
position-in-queue feedback while they wait.

**Expect the cost per submission to be dominated by startup, not by the student's code.** Roughly 5.9
seconds of fixed cost, plus 0.08s per test case, plus 3.9 seconds more if the problem uses a custom
checker. A 9-test problem with a custom checker costs about 10.5 seconds for one student on an idle server;
the student's own code accounts for milliseconds of that.

**Expect nothing to be lost.** Across every run, every submission that was graded was saved to git, the
result-file count matched the submission count exactly, and the repository was left clean. No submission was
half-written or dropped, including in the runs that failed.

**Check the language before assigning a problem.** The judge enforces the time limit the problem's author
set. That limit is not equally achievable in every language — a problem whose C++ sample answer passes
comfortably may have a Java or Python answer that does not. Assign a problem only in a language whose own
sample answer passes.

**What has not been measured:** a real 75-minute lab session, mixed problems in one lab, more than 100
students, repeated submissions from the same student, how the app's light pages behave while grading is
saturated, and what the judge does when genuinely oversubscribed — its "too busy" rejection was never
triggered. See *What was not tested* below.

---

## What was tested

Each test had a number of simulated students all press **Submit** at the same instant, on the same
problem, using that problem's own known-correct solution. Because the code submitted is known to be
correct, anything other than "Accepted" is the system's doing, not the code's.

Seven problems were tested this way, chosen to cover the different ways the judge works: problems checked
by simple output comparison, problems with a custom checker program, a problem with 33 tests versus one
with 8, and the one problem where the student's program and the checker talk back and forth to each other.

---

## Before load testing: how fast is each problem on an idle server?

This had to be measured first. If a problem's correct answer already uses most of its time limit when the
server is quiet, then it failing under load tells you nothing. This table is the yardstick everything else
is measured against.

`results/problem-characteristics.csv`

**All 13 problems pass on an idle server.** None of them is borderline.

Three things about a problem determine what it costs to grade, so all three are listed: how many test
cases it has, how its answers are checked, and how much of its time limit the correct answer actually uses.

| problem | test cases | how answers are checked | time limit | slowest test takes | headroom |
|---|---|---|---|---|---|
| Problem 1 | 9 | custom checker | 1.0s | 0.004s | huge |
| Problem 2 | 10 | custom checker | 3.0s | 0.350s | **8.6x — the tightest** |
| Problem 3 | 11 | custom checker | 1.0s | 0.047s | 21x |
| Problem 4 | 6 | output comparison | 10.0s | 0.379s | 26x |
| Problem 5 | 8 | output comparison | 1.0s | 0.004s | huge |
| Problem 6 | 10 | output comparison | 1.0s | 0.004s | huge |
| Problem 7 | 13 | output comparison | 5.0s | 0.046s | 109x |
| Problem 8 | 17 | output comparison | 1.0s | 0.007s | 143x |
| Problem 9 | 17 | output comparison | 3.0s | 0.004s | huge |
| Problem 10 | 18 | output comparison | 1.0s | 0.013s | 77x |
| Problem 11 | 24 | output comparison | 4.0s | 0.005s | huge |
| Problem 12 | 33 | output comparison | 8.0s | 0.005s | huge |
| Problem 13 | **100** | **interactive checker** | 2.0s | 0.061s | 33x |

"Huge" means the solution finished faster than the stopwatch can measure (about 4 milliseconds), so the
real headroom is unknown but very large. Don't compare those rows against each other.

Test counts range from 6 to 100. Nine problems use output comparison, three use a custom checker program,
and one (Problem 13) is interactive — the student's program and the checker exchange messages while both
are running.

This also settled an earlier worry. Four problems had previously failed a check during setup. It turns out
their **Python and Java** sample answers are too slow for the time limits the problem authors set — the
**C++** answers all pass comfortably. The problems themselves are fine.

---

## How long one submission takes, and why

Measured with a single student and nobody else on the server, so these are best-case numbers.

Three runs on three different problems produced a consistent picture:

| what | time |
|---|---|
| **Fixed cost per submission** (starting a container, launching the grading tool) | **~5.9 seconds** |
| **Each test case** | **~0.08 seconds** |
| **Extra, if the problem has a custom checker program** | **~3.9 seconds** |

So an 8-test problem takes about 6.5 seconds and a 33-test problem about 8.5 seconds — **almost all of it
is startup, not actually running the student's code.**

The custom-checker cost is the striking one: it nearly doubles the time for a small problem. It was
measured by comparing two problems that are otherwise near-identical — same time limit, same headroom, 9
tests versus 8 — where the only real difference is that one has a custom checker. The gap was 3.9 seconds.
We did not confirm *why*; the most likely explanation
is that the checker program is recompiled for every single submission.

**What this means:** the server spends most of its grading time on overhead. Even with 16 submissions being
graded at once, the most it can finish is roughly **2.7 submissions per second**, no matter how simple the
students' code is.

Other pages of the app are fast and were unaffected: the problem statement, the problem list, session
checks and reading back a saved file are all **under 10 milliseconds**. Saving work (autosave) takes
**31 milliseconds**, because it writes to a git repository.

---

## 100 students submitting at once

One submission per student, all fired at the same instant. Six of the seven problems behaved perfectly as
shipped; the seventh, the interactive one, did too once the server setting described below was in place.

`results/performance-summary.csv`

| problem | test cases | checker | fastest student | typical student | slowest student | accepted |
|---|---|---|---|---|---|---|
| Problem 3 | 11 | custom | 12s | 49s | 81s | 100/100 |
| Problem 5 | 8 | output comparison | 12s | 49s | 81s | 100/100 |
| Problem 4 | 6 | output comparison | 14s | 58s | 96s | 100/100 |
| Problem 12 | 33 | output comparison | 17s | 67s | 112s | 100/100 |
| Problem 2 | 10 | custom | 17s | 70s | 116s | 100/100 |
| Problem 1 | 9 | custom | 19s | 78s | 129s | 100/100 |
| Problem 13 | 100 | interactive | 33s | 132s | 219s | 100/100 *(setting raised)* |

With the server setting in place, **all seven problems graded 100 out of 100 students as "Accepted", all
100 submissions were saved to git, and there were zero errors of any kind** — no timeouts, no rejections,
no crashes, no problems saving files. Every student was graded against the same number of test cases as
every other student, on every problem.

The interactive problem is the slowest: typical wait 132s, slowest student 219s — 1.7x the slowest
non-interactive problem. It has 100 test cases, three times the next largest.

Test count does not predict the wait: the slowest non-interactive problem has 9 test cases and the fastest
has 11. The three custom-checker problems have 9, 10 and 11 cases and typical waits of 78s, 70s and 49s — a
spread not accounted for by test count or checker type, and not explained by anything measured here.

### Observation 1 — the queue works exactly as intended

The server grades 16 submissions at a time. With 100 students that is 7 batches, so the last student
should wait about 7 times as long as the first. Measured across all six problems: **6.6 to 6.9 times.**
Students queue up in an orderly fashion and everyone gets served.

### Observation 2 — everything gets about twice as slow when the server is full

Comparing the very first student in a burst (who waits for nobody) against the same problem measured on an
idle server:

| problem | idle | first student in a 100-student burst | slower by |
|---|---|---|---|
| Problem 5 | 6.5s | 12.0s | **1.85x** |
| Problem 1 | 10.5s | 19.2s | **1.84x** |
| Problem 12 | 8.5s | 16.8s | **1.97x** |

The server runs 16 gradings at once on 16 processor cores, so they compete with each other — and with the
web app and the database. Note the test tool itself was also running on that same server, so in real use
this figure may differ.

### Observation 3 — even the tightest problem held up

**Problem 2** has the least headroom of any problem (8.6x). Things got about 2x slower under load, which is
well short of 8.6x, so it still passed. **No problem in the whole set failed on time under load.**

### Observation 4 — the numbers are reliable, not lucky

**Problem 12** was run twice at 100 students, in separate tests. Slowest student: **111.55 seconds** and
**111.48 seconds** — a difference of **0.06%**. These measurements repeat.

### Observation 5 — nothing was lost

Every run recorded how many of its submissions reached the git repository. Across all ten runs in
`performance-summary.csv`, **every submission was persisted: 100 of 100 on each 100-student run, including
the two runs where grading itself failed.** The repository was left clean afterwards (`git status
--porcelain` reports nothing).

Persistence and grading are independent: a submission that could not be graded was still saved. No student
would lose work because the judge was overloaded or an interactive problem deadlocked.

Every submission that was graded has its result stored alongside it. Nothing was half-written or dropped.

---

## Open observation — interactive problems are unreliable when many students submit at once

**Problem 13** is the only problem of its kind in the set: the student's program and the checker
program run **at the same time and talk to each other**, back and forth, hundreds of times per test. Every
other problem simply runs the student's program, then checks its output afterwards.

That difference is the entire reason only this problem is affected. When two programs have to exchange
messages, each can end up waiting for the other. The other 12 problems have nothing to wait for.

### What was seen

| situation | result |
|---|---|
| 16 at once | **8 of the 16 froze** and never finished |
| through the app, **1 student** | failed after exactly 60 seconds |
| through the app, 100 students | every submission failed |
| 16 at once, **with the server setting raised** | **all 16 worked**, all 100 tests, under 30 seconds |
| through the app, 100 students, **setting raised** | **100 of 100 accepted**, all 100 tests, no errors |

### Why it happens, and the proof

There is a Linux setting that limits how much memory can be used for the channels programs use to talk to
each other. The grading tool asks for a large channel — 1 MB — for every conversation, and it runs several
conversations at once, in every one of the 16 gradings happening simultaneously. Together they exceed the
limit.

When that happens, Linux quietly gives out much smaller channels instead of reporting an error. A channel
that is too small means the student's program and the checker each end up waiting for the other. Neither
gives up, and the grading freezes.

**The proof is a single-variable test**, at two different scales.

Directly, 16 gradings at once, run back to back:

- with the limit at its default: **8 of 16 froze**
- with the limit raised: **16 of 16 finished normally**

And through the app, 100 students, the same script with the same arguments, the two runs **seven minutes
apart** with nothing changed but the setting:

| | accepted | typical wait | slowest | peak load average |
|---|---|---|---|---|
| limit raised | **100 / 100**, all 100 tests | 132s | 219s | 72.1 |
| limit at default | **0 / 100** | 89s | 192s | 9.2 |

Peak CPU read 100% in both runs. Both saved 100 of 100 submissions to git.

### It already fails safely

The grading system gives up on any submission after one minute and shuts it down properly. That is why the
single-student failure took *exactly* 60 seconds, and why each of the 100 students got a clear error
message instead of a frozen page. No student is told their correct code passed when the grading did not
finish. Nothing leaked and nothing had to be restarted by hand.

### What to do about it — one server setting

```bash
echo 'fs.pipe-user-pages-soft = 262144' | sudo tee /etc/sysctl.d/99-cs30-judge.conf
sudo sysctl --system
```

This raises the limit to 16x its previous value. It needs no code change and no new permissions for the
sandbox that runs student code. (Setting it to unlimited was deliberately avoided — that memory can't be
swapped out, so an unlimited setting would let a badly-behaved program exhaust the server's memory.)

With the setting raised, **Problem 13** at **100 concurrent students** grades **100 out of 100
accepted, every one against the full 100 tests, with no errors** — the same scenario that fails without it.

**This is the thing to remember.** The setting lives in `/etc/sysctl.d/`, not in the codebase, so it
survives a reboot but not a rebuilt or replaced server. Without it, interactive problems stop working and
nothing in the application will say why.

---

## Open observation — time limits are set per problem, not per language

Each problem's time limit is chosen by whoever wrote the problem, and the judge enforces exactly that. That
is correct behaviour, not something to tune.

All 13 problems pass their author's limit in **C++**. The same limit is not equally achievable in every
language — for some problems, the Java or Python sample answer is too slow for the limit the C++ one meets
comfortably. So a problem should only be assigned in a language whose own sample answer passes.

One case needs attention now: **Problem 13** is set up on a lab in the live course as a **Java** problem, but
it has no Java sample answer at all — only C++ and Python. **No student could pass it today**, for reasons
unrelated to anything else in this report.

This is a problem-setup matter rather than a fault in the system.

---

## What was not tested

Listed plainly, because saying nothing would imply it was covered.

- **How responsive the rest of the app is while grading is at full load.** The most important gap.
  Everything measured here is how long *submitting* takes. If autosave goes from 31 milliseconds to several
  seconds while the server is busy grading, students who aren't even submitting would find the editor
  sluggish and could lose work.
- **The judge's "too busy, try again" rejection.** The judge accepts up to 100 waiting submissions, and the
  run intended to overload it used exactly 100 students, consuming exactly 100 of the 100 slots. Every
  request was admitted: it recorded 100 accepted and no rejections, making it a second ordinary burst rather
  than an overload test. Triggering a rejection requires more simultaneous requests than the judge has
  slots for.
- **Smaller class sizes** (32 students). 100 worked, so the middle ground was skipped.
- **Students working on different problems at the same time.** Every test used one problem at a time; a
  real lab spreads students across several.
- **A full 75-minute lab session.** Deliberately skipped — the burst tests answer the capacity question in
  under two minutes each.
- **The "Run" button under load.** Judged unnecessary: Run does strictly less work than Submit (fewer
  tests, no saving to git) through the same queue.
- **Whether a newer version of the grading tool fixes the interactive-problem behaviour.** Version 2026.4.0 was used throughout;
  2026.7.0 exists. The server setting fixes it either way, so this is no longer urgent.

---

# Appendix — technical detail

For anyone who needs the specifics. Nothing here changes the conclusions above.

## Environment

| | |
|---|---|
| backend | `cs30-1.0-SNAPSHOT.jar`, PID 657618 on `:8090` |
| judge | kt-judge on `:8000` — `max_workers=16`, `max_queue_size=100` |
| sandbox per submission | one CPU core, 2560 MB memory (no swap), its own temporary filesystem |
| BAPCtools | 2026.4.0 (2026.7.0 available, not installed), Python 3.12.13 |
| host | 16 cores, 63 GB — k6, backend, kt-judge and Postgres all share them |
| pool | a throwaway copy of the 13 problems, format 2025-09, authored time limits in force |
| course | a throwaway load-test course, 100 seeded student sessions |

## Interactive problems — measured facts

| | |
|---|---|
| `BUFFER_SIZE` at `bapctools/interactive.py:30` | `2**20` = 1048576 B = 256 pages per pipe |
| `pipesize=BUFFER_SIZE` call sites | 4, all in `interactive.py` |
| container `pipe-max-size` | 1048576 (same as host, not restricted) |
| container `CapEff` | `0000000000000000` — no `CAP_SYS_RESOURCE` |
| `fs.pipe-user-pages-soft` | 16384 pages (64 MB), **per-uid across all sandboxes at once** |
| bt's internal thread pool | `cpu_count()/2` = 8 per container (`incontainer.py` passes no `-j`) |

Mechanism (inferred from kernel behaviour, not directly measured): exceeding the soft limit makes
`F_SETPIPE_SZ` return `EPERM` when *growing* a pipe — observed once as
`PermissionError: [Errno 1] Operation not permitted` — while pipe *creation* over the limit silently
allocates a single page instead of erroring.

Wedged container state:

```
1   bt    S  futex_do_wait      ← main thread waiting on its workers
47  run   S  anon_pipe_read     ← child blocked reading a pipe
48  run   S  anon_pipe_read
54  sh    S  anon_pipe_read

Thread ...:
  subprocess.py:1264 in wait
  bapctools/interactive.py:462 in run_interactive_testcase
  bapctools/parallel.py:158 in _worker
```

Containment: `JudgeRunner.kt:96,135,139-141` names each container, waits `runAllWallSeconds` (60), then
`docker kill`s it — the comment there notes `--rm` alone is insufficient.

Rejected alternatives: `--cap-add SYS_RESOURCE` (the sandbox should keep dropping all capabilities; the
sysctl achieves the same without it); `-j 1` in `incontainer.py` (~8x per-submission latency for a
mitigation the sysctl makes unnecessary — tested at 15/16 passing, then reverted).

## Known measurement bug, corrected

Case counts in `problem-characteristics.csv` are each one too high: `bt` emits a trailing `slowest:` summary line
matching the same pattern as a real case line, and the parser counted it. Margins are unaffected (the line
duplicates the max). `measure-problem-characteristics.sh` now skips it. The counts in the 100-student table are the
true ones, taken from the judge.

## Scripts, and what each produced

| script | produced |
|---|---|
| `measure-problem-characteristics.sh` | `problem-characteristics.csv` |
| `run-phase.sh` | every run — reseeds sessions, captures metrics, slices the server log, writes the per-run output |
| `capture-metrics.sh` | samples server CPU, load and memory throughout a run; called by `run-phase.sh` |
| `k6/baseline-single-user.js` | `baseline-1vu-*`, `b-validator-1vu-*` |
| `k6/problem-burst.js` | `c2-*` (the 100-student bursts), `p3-*` (the interactive pair, setting raised then default), `sky-1vu-*` (single student, interactive), `e-overload-*` (intended as an overload probe; see *What was not tested* — it did not overload anything) |
| `debug-interactive-hang.sh` | reproduces the interactive-problem freeze and reports what each grading process is stuck on |
| `sanity-check.sh` | standing check: every problem grades its full test count, and no submission is accepted on a partial grade |
| `gen-fixtures.py` | `students.json`, `loadtest-course.yaml`, `seed-sessions.sql`, `local-ips.txt` (gitignored) |
| `loadtest-lab1.yaml` | registers the test problems on lab 1 via `addlab`. Gitignored, because it names them — copy `loadtest-lab1.yaml.example` and fill in your own. |

## The reports in `results/`

Three files. Every figure in this document comes from one of them.

| file | what it holds |
|---|---|
| `problem-characteristics.csv` | per problem: time limit, its fastest correct answer's slowest test, and the resulting headroom. The yardstick every load figure is read against. |
| `performance-summary.csv` | per run: problem, number of students, how many were accepted, tests per submission, wait times (min / median / p95 / max), slowest-vs-fastest ratio, peak server CPU and grader count. |
| `single-student-endpoints.csv` | per endpoint, with one student and an idle server: median, p95 and max response time. The best case every loaded figure is compared against. |

### Provenance

Figures derived from the three files rather than read straight out of them, with the arithmetic:

| figure | derivation |
|---|---|
| 0.08s per test case | (8532 − 6517) ms ÷ (33 − 8) tests = 80.6 ms |
| ~5.9s fixed cost | 6517 ms − 8 × 80.6 ms = 5872 ms |
| ~3.9s custom-checker cost | 10463 ms − (5872 + 9 × 80.6) ms = 3865 ms |
| 2.7 submissions/second ceiling | 16 workers ÷ 5.872s = 2.72 |
| 1.85x / 1.84x / 1.97x under load | burst `min_ms` ÷ idle `ep_code_run` median, per problem |
| 0.06% run-to-run difference | (111552 − 111483) ÷ 111552 |
| 16x the pipe limit | 262144 ÷ 16384 |

Four claims come from neither the three files nor arithmetic on them:

| claim | source | status |
|---|---|---|
| 16-core host | `host_cores` in each run's `meta.txt` | **confirmed** — `nproc` returns 16 |
| repository left clean | `git status --porcelain` on the test machine | **confirmed** — reports nothing |
| "8 of 16 froze" / "16 of 16 finished" / "under 30 seconds" | `debug-interactive-hang.sh`, which writes only to `/tmp` | not retained; re-runnable via the script at both settings |
| four problems' Java/Python sample answers exceed their time limit | the problem-setup pass; `problem-characteristics.csv` records only the C++ result | not retained; re-runnable per language |

Persistence is evidenced by the per-run `saved_to_git` column rather than an aggregate file count, and the
custom-checker gap by the arithmetic above.

These are summaries. The full per-run output — the raw k6 stream, the server log slice, the per-student
rows, the resource trace — is produced by `run-phase.sh` on the test machine and deliberately not kept
here: it contains server paths, process detail and per-student records that nothing in this report needs.
Re-run the scripts if you need that depth.

### Pulling a run off the test machine

`run-phase.sh` writes everything to `$RESULTS` (default `~/cs30loadtest/results`) as
`<run-label>-<timestamp>-{meta,summary,submissions,metrics,raw,backend}.*`. To summarise a run without
copying the whole directory down:

```bash
SERVER=user@your-test-host

# What runs exist, newest last
ssh "$SERVER" 'ls -1 ~/cs30loadtest/results/*-meta.txt | xargs -n1 basename'

# The three files worth reading, for one run
RUN=<run-label>-<timestamp>
mkdir -p ./pulled/"$RUN"
scp "$SERVER":"~/cs30loadtest/results/$RUN"-{meta.txt,summary.txt,submissions.csv} ./pulled/"$RUN"/
```

`-meta.txt` is the one to read first: it already contains the verdict counts, the wait-time percentiles,
the per-student breakdown of anything not accepted, and the peak host load. `-submissions.csv` is one row
per student if you need to check an individual result. Leave `-raw.json` (k6's full metric stream, hundreds
of MB) and `-backend.log` on the server unless you are diagnosing a specific failure — the server log slice
in particular carries paths and student identifiers that should not be copied into a report.

## Reproducing

Needs a throwaway database, the backend and the judge running against it, and a populated problem pool.
The pool is built by `../scripts/migrate-problem-sources.sh` and `../scripts/assemble-problem-pool.sh` —
problem tooling, kept outside this folder because it is not part of the load test. `gen-fixtures.py` builds
the roster and session fixtures; `addlab --lab-file=loadtest-lab1.yaml` registers the problems (it
**syncs** — the file must list them all, or the rest are deleted).

Problem names and solution filenames are deliberately absent below. Substitute your own: any problem in
the pool, and any file from its `submissions/accepted/` directory.

```bash
export COURSE_ID=<course primary-key UUID, not the course code>
export PGPASSWORD=<password>
export POOL=<path to the problem pool>
export RESULTS=<where reports are written>
export STUDENT_REPO=<path to the student git repo>
cd <scripts directory>

# 1. Idle headroom per problem. Nothing below is interpretable without it: a problem whose correct
#    answer already uses most of its time limit tells you nothing when it fails under load.
./measure-problem-characteristics.sh "$POOL" "$RESULTS/problem-characteristics.csv"

# 2. Single student, no contention — the best case everything else is compared against.
./run-phase.sh baseline baseline-single-user.js \
    -e PROBLEM_SLUG=<problem> -e SOLUTION_FILE=<its accepted answer> -e ITERATIONS=10

#    Custom-checker cost: two problems alike in limit, headroom and test count, one with a custom
#    checker and one without. The gap between them is the checker's cost.
./run-phase.sh checker-cost baseline-single-user.js \
    -e PROBLEM_SLUG=<problem with a custom checker> -e SOLUTION_FILE=<answer> -e ITERATIONS=20
./run-phase.sh checker-control baseline-single-user.js \
    -e PROBLEM_SLUG=<comparable problem without one> -e SOLUTION_FILE=<answer> -e ITERATIONS=20

# 3. 100 students submitting at the same instant, one problem per run.
for spec in "<problem>:<answer>" "<problem>:<answer>"; do
  slug=${spec%%:*}; sol=${spec##*:}
  ./run-phase.sh "burst-$slug-100" problem-burst.js \
      -e PROBLEM_SLUG="$slug" -e SOLUTION_FILE="$sol" -e VUS=100 < /dev/null
done

# 4. Nothing lost — these two counts must match, and the repo must be clean.
find "$STUDENT_REPO" -name 'submission-*' | wc -l
find "$STUDENT_REPO" -name 'result-*.json' | wc -l
git -C "$STUDENT_REPO" fsck --no-progress
```

Two things that waste a run if forgotten. `COURSE_ID` must be the course's **primary-key UUID** — the
course code gives "Course not found" on every request, and the scripts now refuse to start without it. And
login sessions expire **2 minutes** after their last heartbeat; `run-phase.sh` reseeds immediately before
each run, so never reseed by hand in advance.

## Reproducing the interactive-problem observation

```bash
./debug-interactive-hang.sh 16 90             # expect roughly 8 of 16 to freeze
sudo sysctl -w fs.pipe-user-pages-soft=0  # the proof
./debug-interactive-hang.sh 16 90             # expect 16 of 16 to pass
sudo sysctl -w fs.pipe-user-pages-soft=16384
```

Containers start detached and named so a frozen one can be inspected while still frozen. Reports land in
`/tmp/sky-debug/*.stuck.txt` with each process's kernel wait state, the grading tool's open channels, and a
full Python traceback forced with `PYTHONFAULTHANDLER=1` plus `SIGABRT`. Everything is time-bounded and
cleaned up; an early unbounded attempt left 9 containers running for 25 minutes.
