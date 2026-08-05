#!/usr/bin/env bash
# Run one load-test scenario and capture everything needed to interpret it later.
#
# WHY A WRAPPER
#
# Every earlier round lost evidence to the same four omissions, and each one invalidated conclusions:
#   1. Sessions weren't re-seeded, so runs started against expired tokens (ApiTokenStore enforces a
#      hardcoded 2-minute TTL from last_heartbeat_at).
#   2. No server-side resource trace, so degradation was attributed by guesswork — the same timings got
#      explained three different ways.
#   3. Output lived only in terminal scrollback, so nothing could be re-read or diffed.
#   4. Backend log lines from the run window were never separated from everything before it.
# This script does all four the same way every time, so results are comparable across runs.
#
# It captures, per run, into $RESULTS/<run-id>-*:
#   -summary.txt   k6's own summary (the human-readable numbers)
#   -raw.json      k6 raw metrics stream (for later re-analysis; do not read by eye)
#   -metrics.csv   server CPU / load / memory / container counts, sampled throughout
#   -backend.log   ONLY the backend lines emitted during this run
#   -meta.txt      what was run, against what, and the judge's state before and after
#
# Usage:
#   ./run-phase.sh <run-label> <k6-script> [extra -e args...]
# Example:
#   ./run-phase.sh burst-<problem>-100 problem-burst.js \
#       -e PROBLEM_SLUG=<problem> -e SOLUTION_FILE=<accepted answer> -e VUS=100
set -uo pipefail

LABEL="${1:?usage: run-phase.sh <run-label> <k6-script> [extra -e args...]}"
SCRIPT="${2:?usage: run-phase.sh <run-label> <k6-script> [extra -e args...]}"
shift 2

# Environment — override by exporting before calling.
BASE_URL="${BASE_URL:-http://localhost:8090}"
JUDGE_URL="${JUDGE_URL:-http://localhost:8000}"
COURSE_ID="${COURSE_ID:?export COURSE_ID=<course-uuid> first (the primary key, not the course code)}"
SCRIPTS="${SCRIPTS:-$HOME/cs30loadtest/scripts}"
RESULTS="${RESULTS:-$HOME/cs30loadtest/results}"
STUDENTS_JSON="${STUDENTS_JSON:-$SCRIPTS/students.json}"
BACKEND_LOG="${BACKEND_LOG:-$SCRIPTS/loadtest-backend.log}"
PGDB="${PGDB:-cs30_loadtest}"
PGUSER_="${PGUSER_:-cs30}"
USE_LOCAL_IPS="${USE_LOCAL_IPS:-true}"
SAMPLE_SECONDS="${SAMPLE_SECONDS:-2}"

RUN="${LABEL}-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$RESULTS"
META="$RESULTS/$RUN-meta.txt"

echo "run:     $RUN"
echo "script:  $SCRIPT"
echo "results: $RESULTS/$RUN-*"
echo

# ---- 1. Re-seed sessions -------------------------------------------------------------------------
# Immediately before the run, never earlier: the 2-minute TTL means even a short preflight can expire
# them, and an expired token produces 401s that look like an application failure.
echo "[1/5] re-seeding sessions"
psql -h localhost -U "$PGUSER_" -d "$PGDB" -q -c \
  "DELETE FROM login_sessions WHERE student_email LIKE 'loadtest-student-%';" || exit 1
psql -h localhost -U "$PGUSER_" -d "$PGDB" -q -f "$SCRIPTS/seed-sessions.sql" || exit 1

# ---- 2. Record starting state ---------------------------------------------------------------------
{
    echo "run_id:      $RUN"
    echo "started_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "script:      $SCRIPT"
    echo "args:        $*"
    echo "base_url:    $BASE_URL"
    echo "course_id:   $COURSE_ID"
    echo "host_cores:  $(nproc 2>/dev/null || echo '?')"
    echo "judge_before: $(curl -s "$JUDGE_URL/queue-status" 2>/dev/null)"
    echo "backend_pid: $(fuser 8090/tcp 2>/dev/null | tr -d ' ')"
} > "$META"

# Mark the log position so only this run's lines are extracted afterwards.
LOG_START=$(wc -l < "$BACKEND_LOG" 2>/dev/null || echo 0)

# ---- 3. Start the resource trace --------------------------------------------------------------------
echo "[2/5] starting resource capture"
"$SCRIPTS/capture-metrics.sh" "$RESULTS/$RUN-metrics.csv" "$SAMPLE_SECONDS" &
CAP=$!
trap 'kill $CAP 2>/dev/null' EXIT

# ---- 4. Run k6 ---------------------------------------------------------------------------------------
# --local-ips spreads source addresses across the seeded session IPs. Without it every request logs an
# [ip-mismatch] warning (StudentIdentityService only warns, never rejects) which is pure log volume and
# I/O inside a measured run.
LOCAL_IPS_ARG=()
if [ "$USE_LOCAL_IPS" = "true" ] && [ -f "$SCRIPTS/local-ips.txt" ]; then
    LOCAL_IPS_ARG=(--local-ips="$(cat "$SCRIPTS/local-ips.txt")")
fi

echo "[3/5] running k6"
k6 run "${LOCAL_IPS_ARG[@]}" \
    -e BASE_URL="$BASE_URL" \
    -e COURSE_ID="$COURSE_ID" \
    -e STUDENTS_JSON_PATH="$STUDENTS_JSON" \
    "$@" \
    --out json="$RESULTS/$RUN-raw.json" \
    "$SCRIPTS/k6/$SCRIPT" 2>&1 | tee "$RESULTS/$RUN-summary.txt"
K6_EXIT=${PIPESTATUS[0]}

# ---- 5. Stop capture, slice the log, record end state ------------------------------------------------
echo "[4/5] stopping resource capture"
kill $CAP 2>/dev/null; trap - EXIT

echo "[5/5] slicing backend log and recording end state"
if [ -f "$BACKEND_LOG" ]; then
    tail -n +"$((LOG_START + 1))" "$BACKEND_LOG" > "$RESULTS/$RUN-backend.log"
fi

# ---- per-submission rows -----------------------------------------------------------------------
# problem-burst.js logs one "ROW|..." line per submission. k6 wraps console output as
# msg="<text>", so the row is everything between ROW| and the closing quote. Aggregates cannot answer
# "which student's submission failed, and how long did that student wait" — this file can.
SUBS="$RESULTS/$RUN-submissions.csv"
if grep -q 'ROW|' "$RESULTS/$RUN-summary.txt" 2>/dev/null; then
    echo "vu,student,problem,http_status,verdict,passed,total,duration_ms,git_saved,fully_accepted,error" > "$SUBS"
    grep -o 'ROW|[^"]*' "$RESULTS/$RUN-summary.txt" | sed 's/^ROW|//' | sort -t, -k1 -n >> "$SUBS"
    echo "  wrote $(($(wc -l < "$SUBS") - 1)) submission rows -> $SUBS"
fi

{
    echo "ended_utc:   $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "k6_exit:     $K6_EXIT   (non-zero = a threshold was crossed, which may be the intended result)"
    echo "judge_after: $(curl -s "$JUDGE_URL/queue-status" 2>/dev/null)"
    echo ""
    echo "backend log counts for this run only:"
    if [ -f "$RESULTS/$RUN-backend.log" ]; then
        printf "  judge_errors:      %s\n" "$(grep -c 'Judge error' "$RESULTS/$RUN-backend.log")"
        printf "  git_persist_fails: %s\n" "$(grep -c 'Failed to save submission' "$RESULTS/$RUN-backend.log")"
        printf "  repo_lock_timeouts:%s\n" "$(grep -c 'Timed out waiting for git repo lock' "$RESULTS/$RUN-backend.log")"
        printf "  ip_mismatch_warns: %s\n" "$(grep -c 'ip-mismatch' "$RESULTS/$RUN-backend.log")"
        printf "  exceptions:        %s\n" "$(grep -cE 'Exception|ERROR' "$RESULTS/$RUN-backend.log")"
    fi
    if [ -f "$SUBS" ]; then
        echo ""
        echo "per-submission breakdown:"
        python3 - "$SUBS" <<'PY'
import csv, sys, collections
rows = list(csv.DictReader(open(sys.argv[1])))
if not rows:
    print("  (no rows)"); raise SystemExit
n = len(rows)
verdicts = collections.Counter(r['verdict'] for r in rows)
https    = collections.Counter(r['http_status'] for r in rows)
acc      = sum(1 for r in rows if r['fully_accepted'] == 'yes')
saved    = sum(1 for r in rows if r['git_saved'] == 'yes')
durs     = sorted(int(r['duration_ms']) for r in rows if r['duration_ms'].isdigit())

def pct(p):
    return durs[min(int(len(durs) * p / 100), len(durs) - 1)] if durs else 0

print(f"  submissions:     {n}")
print(f"  fully accepted:  {acc}/{n}  ({100*acc/n:.1f}%)")
print(f"  persisted to git:{saved}/{n}")
print(f"  verdicts:        {dict(verdicts)}")
print(f"  http statuses:   {dict(https)}")
if durs:
    print(f"  duration ms:     min={durs[0]} p50={pct(50)} p95={pct(95)} max={durs[-1]}")
    # Spread between first and last finisher is the queueing effect: with N submissions and
    # maxWorkers workers, later arrivals wait for a free slot, so max/min approximates the depth.
    print(f"  slowest/fastest: {durs[-1]/durs[0]:.1f}x  (queue depth effect)")

# Test-case totals must agree across students; a differing total means a student was graded
# against a different set of cases, which would be a correctness problem, not a load one.
totals = collections.Counter(r['total'] for r in rows if r['total'])
if len(totals) > 1:
    print(f"  !! INCONSISTENT case totals across students: {dict(totals)}")
else:
    for t in totals:
        print(f"  cases per submission: {t} (identical for every student)")

bad = [r for r in rows if r['fully_accepted'] != 'yes']
if bad:
    print(f"  --- {len(bad)} submission(s) NOT fully accepted ---")
    for r in bad[:15]:
        print(f"    vu={r['vu']} {r['student']} http={r['http_status']} verdict={r['verdict']} "
              f"passed={r['passed']}/{r['total']} {r['duration_ms']}ms git={r['git_saved']} {r['error']}")
    if len(bad) > 15:
        print(f"    ... and {len(bad)-15} more (see {sys.argv[1]})")
PY
    fi
    if [ -f "$RESULTS/$RUN-metrics.csv" ]; then
        echo ""
        echo "peak host load during run:"
        python3 - "$RESULTS/$RUN-metrics.csv" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
if not rows:
    print("  (no samples)"); raise SystemExit
def peak(col):
    vals = [float(r[col]) for r in rows if r.get(col) not in (None, '', 'NA')]
    return f"{max(vals):.1f}" if vals else "?"
for c in rows[0]:
    if c != 'timestamp':
        print(f"  {c}: peak={peak(c)}")
PY
    fi
} >> "$META"

echo
echo "=== $RUN ==="
cat "$META"
echo
echo "artifacts:"
ls -1 "$RESULTS/$RUN"-* | sed 's/^/  /'
exit "$K6_EXIT"
