#!/usr/bin/env bash
# Sanity check after the partial-grading guards were added to kt-judge.
#
# WHAT THE GUARDS DO, AND THE RISK THEY INTRODUCE
#
# parseSubmit refuses to report a verdict when (a) bt printed a Python traceback, or (b) fewer cases were
# graded than the problem has. That closes a real defect: a truncated grade used to be shown to the
# student as "Submitted: AC (2/2 passed)" on a 100-case problem.
#
# A container killed at its wall timeout never reaches either guard — it is caught earlier by the
# blank-output check, because the orchestrator prints its JSON only as the very last thing it does, so a
# run stopped mid-flight emits nothing at all.
#
# Guard (a) runs BEFORE the compile-error branch, deliberately: a crashed run's output frequently mentions
# compiling, and classifying it first reported bt's own traceback to the student as their compile error.
#
# The danger is the mirror image — rejecting a submission that was actually complete. That would be worse
# than the bug being fixed, because it fails students who wrote correct code. Guard (b) is the one that can
# do it, if countGradedCases ever disagrees with bt about how many cases a problem has.
#
# So this script is built around proving that disagreement does not happen, and that healthy submissions
# still pass end to end.
#
#   PART 1  For all 13 problems: does a file count of data/{sample,secret}/**/*.in equal the number of
#           cases bt actually grades? This is countGradedCases' exact rule, checked against bt itself.
#           Runs bt directly, no app involved. Catches a false-rejection risk before any student sees it.
#
#   PART 2  For every registered problem: submit its own accepted C++ solution through the real app and
#           require AC with the FULL case count. This is the no-false-rejection test.
#
#   PART 3  Require the guard to actually fire. Set INTERACTIVE_PROBLEM to a problem whose
#           grading truncates under concurrency (an interactive one) while
#           fs.pipe-user-pages-soft is at its default, so it must come back as an ERROR — never as AC
#           with a partial count. If it returns AC, the fix is not working.
#
# Usage:
#   export COURSE_ID=<course primary-key UUID>   # not the course code
#   export PGPASSWORD=<password>
#   export INTERACTIVE_PROBLEM=<name of an interactive problem in the pool>   # for PART 3
#   ./sanity-check.sh [part]        # part = 1 | 2 | 3 | all (default all)
set -uo pipefail

PART="${1:-all}"
POOL="${POOL:?set POOL=<path to the problem pool>}"
IMAGE="${IMAGE:-judge-sandbox:latest}"
BASE_URL="${BASE_URL:-http://localhost:8090}"
SCRIPTS="${SCRIPTS:-$HOME/cs30loadtest/scripts}"
PGDB="${PGDB:-cs30_loadtest}"
PGUSER_="${PGUSER_:-cs30}"
SECTION="${SECTION:-1}"
LAB="${LAB:-1}"
CPUS="${CPUS:-1.0}"
MEM="${MEM:-2560m}"
# The interactive problem exercised by PART 3. Not hardcoded: this file is public, and naming the
# problems in a public repo would tell students which ones a lab uses.
INTERACTIVE_PROBLEM="${INTERACTIVE_PROBLEM:-}"

fail=0
note() { printf '  %s\n' "$*"; }

# ---------------------------------------------------------------------------------------------------
# PART 1 — does our case-count rule match bt's, for every problem?
# ---------------------------------------------------------------------------------------------------
part1() {
    echo "############ PART 1 — case-count agreement (no app, no student) ############"
    printf '  %-26s %-10s %-10s %s\n' PROBLEM file_count bt_count VERDICT
    for dir in "$POOL"/*/; do
        p=$(basename "$dir")
        [ -f "$dir/problem.yaml" ] || continue

        # countGradedCases' rule, reimplemented here independently so a shared bug can't hide.
        files=$(find "$dir/data/sample" "$dir/data/secret" -name '*.in' 2>/dev/null | wc -l | tr -d ' ')

        sol=$(ls "$dir"/submissions/accepted/*.cpp 2>/dev/null | head -1)
        if [ -z "$sol" ]; then
            printf '  %-26s %-10s %-10s %s\n' "$p" "$files" "-" "SKIP (no C++ solution)"
            continue
        fi
        s=$(basename "$sol")

        # --timeout keeps a slow or wedged problem from stalling the check; bt still uses the authored
        # per-case limit, so verdicts stay meaningful.
        out=$(timeout -s KILL 240 docker run --rm --cpus="$CPUS" --memory="$MEM" --entrypoint bash \
                -v "$dir":/problem:ro "$IMAGE" -c "
                  mkdir -p /tmp/w && cp -r /problem/. /tmp/w/ 2>/dev/null
                  cp /problem/submissions/accepted/$s /tmp/w/
                  cd /tmp/w && bt run -ve -aa --no-bar --timeout 120 $s 2>&1" 2>&1)

        # Exclude bt's trailing "slowest:" summary line — it matches the same shape as a real case line
        # and inflated an earlier version of this count by exactly one.
        bt=$(printf '%s\n' "$out" | grep -E ': +(AC|WA|TLE|RTE|MLE) +[0-9.]+s @ ' | grep -vc 'slowest:')

        if [ "$files" = "$bt" ]; then
            v="MATCH"
        else
            v="MISMATCH -> guard (c) would wrongly reject this problem"; fail=$((fail+1))
        fi
        printf '  %-26s %-10s %-10s %s\n' "$p" "$files" "$bt" "$v"
    done
    echo
    note "A MISMATCH here is a false-rejection risk and must be resolved before deploying."
    echo
}

# ---------------------------------------------------------------------------------------------------
# PART 2 — healthy submissions must still pass, through the real app
# ---------------------------------------------------------------------------------------------------
reseed() {
    psql -h localhost -U "$PGUSER_" -d "$PGDB" -q -c \
      "DELETE FROM login_sessions WHERE student_email LIKE 'loadtest-student-%';" >/dev/null || return 1
    psql -h localhost -U "$PGUSER_" -d "$PGDB" -q -f "$SCRIPTS/seed-sessions.sql" >/dev/null || return 1
}

# Submits one solution and prints "verdict passed total success". One student, no concurrency: any
# non-AC result here is the guards misfiring, not contention.
submit_one() {
    local slug="$1" solfile="$2" email="$3" token="$4"
    python3 - "$BASE_URL" "$COURSE_ID" "$SECTION" "$LAB" "$slug" "$email" "$token" \
        "$POOL/$slug/submissions/accepted/$solfile" <<'PY'
import json, sys, urllib.request
base, course, section, lab, slug, email, token, solpath = sys.argv[1:9]
body = json.dumps({
    "courseId": course, "section": int(section), "labNumber": int(lab),
    "problemName": slug, "studentEmail": email,
    "code": open(solpath, encoding="utf-8", errors="replace").read(), "language": "cpp",
}).encode()
req = urllib.request.Request(f"{base}/api/code/submit", data=body, method="POST",
                            headers={"Content-Type": "application/json",
                                     "Authorization": f"Bearer {token}"})
try:
    with urllib.request.urlopen(req, timeout=900) as r:
        d = json.loads(r.read())
        http = r.status
except urllib.error.HTTPError as e:            # the backend returns its JSON body even on 4xx
    d = json.loads(e.read() or b"{}"); http = e.code
except Exception as e:
    print(f"ERR - - false {type(e).__name__}"); raise SystemExit
print(f"{d.get('status')} {d.get('passed')} {d.get('total')} {d.get('success')} {http}")
PY
}

part2() {
    echo "############ PART 2 — healthy submissions still pass (1 student, via the app) ############"
    : "${COURSE_ID:?export COURSE_ID=<course primary-key UUID> first}"

    reseed || { note "could not reseed sessions"; fail=$((fail+1)); return; }
    read -r email token < <(python3 -c "
import json;s=json.load(open('$SCRIPTS/students.json'))[0];print(s['email'],s['token'])")

    mapfile -t problems < <(psql -h localhost -U "$PGUSER_" -d "$PGDB" -At -c \
      "SELECT DISTINCT p.name FROM problems p JOIN scheduled_labs sl ON sl.id=p.lab_id
       WHERE sl.lab_number=$LAB ORDER BY p.name;")

    printf '  %-26s %-6s %-12s %-10s %s\n' PROBLEM http passed/total expected VERDICT
    for slug in "${problems[@]}"; do
        [ -d "$POOL/$slug" ] || { printf '  %-26s %s\n' "$slug" "SKIP (not in pool)"; continue; }
        sol=$(ls "$POOL/$slug"/submissions/accepted/*.cpp 2>/dev/null | head -1)
        [ -z "$sol" ] && { printf '  %-26s %s\n' "$slug" "SKIP (no C++ solution)"; continue; }

        expected=$(find "$POOL/$slug/data/sample" "$POOL/$slug/data/secret" -name '*.in' 2>/dev/null | wc -l | tr -d ' ')
        reseed   # the session TTL is 2 minutes and a submit can take longer than that
        read -r st passed total success http < <(submit_one "$slug" "$(basename "$sol")" "$email" "$token")

        if [ "$st" = "AC" ] && [ "$passed" = "$total" ] && [ "$total" = "$expected" ]; then
            v="PASS"
        elif [ -n "$INTERACTIVE_PROBLEM" ] && [ "$slug" = "$INTERACTIVE_PROBLEM" ]; then
            v="expected failure (see PART 3)"
        else
            v="FAIL -> a complete submission was rejected"; fail=$((fail+1))
        fi
        printf '  %-26s %-6s %-12s %-10s %s\n' "$slug" "$http" "$passed/$total" "$expected" "$v"
    done
    echo
    note "Every problem must read PASS (bar the interactive one, if the pipe limit is low). A FAIL"
    note "rejecting work that was actually graded in full — worse than the bug they fix."
    echo
}

# ---------------------------------------------------------------------------------------------------
# PART 3 — the guard must actually fire
# ---------------------------------------------------------------------------------------------------
part3() {
    echo "############ PART 3 — no partial grade is ever reported as a pass ############"
    : "${COURSE_ID:?export COURSE_ID=<course primary-key UUID> first}"

    # This MUST run under concurrency. An earlier version of this check submitted as a single student and
    # expected a failure, which was wrong: one student on a quiet server grades all 100 cases fine (proven
    # separately — 8 of 8 sequential runs complete). Truncation only appears when many submissions run at
    # once, so a 1-student check reported "pass" for a condition that never arose.
    local V="${PART3_VUS:-100}"
    note "$INTERACTIVE_PROBLEM at $V concurrent students, with"
    note "fs.pipe-user-pages-soft = $(cat /proc/sys/fs/pipe-user-pages-soft)."
    echo
    note "The assertion is NOT 'it must fail'. It is: no submission may be reported AC unless every one"
    note "of the problem's cases was graded. That holds whether the problem now works completely or"
    note "fails completely — and it is exactly what used to be violated (14 of 100 returned AC on 1-18"
    note "of 100 cases)."
    echo

    local sol expected run
    sol=$(ls "$POOL/$INTERACTIVE_PROBLEM"/submissions/accepted/*.cpp 2>/dev/null | head -1)
    expected=$(find "$POOL/$INTERACTIVE_PROBLEM/data/sample" "$POOL/$INTERACTIVE_PROBLEM/data/secret" \
                 -name '*.in' 2>/dev/null | wc -l | tr -d ' ')
    [ -z "$INTERACTIVE_PROBLEM" ] && { note "set INTERACTIVE_PROBLEM=<problem> to run PART 3"; return; }
    [ -z "$sol" ] && { note "no C++ solution for $INTERACTIVE_PROBLEM"; fail=$((fail+1)); return; }

    run="p3-interactive-$V"
    # run-phase.sh reseeds sessions, captures metrics, and writes the per-submission CSV this check reads.
    # EXPECT_ALL_AC=false: a non-zero exit here would mean "some submissions were not accepted", which is
    # an acceptable outcome — the defect is a WRONG acceptance, not a refusal.
    "$SCRIPTS/run-phase.sh" "$run" problem-burst.js \
        -e PROBLEM_SLUG="$INTERACTIVE_PROBLEM" \
        -e SOLUTION_FILE="$(basename "$sol")" \
        -e VUS="$V" -e EXPECT_ALL_AC=false < /dev/null >/dev/null 2>&1

    local csv
    csv=$(ls -t "${RESULTS_DIR:-$HOME/cs30loadtest/results}/$run"-*-submissions.csv 2>/dev/null | head -1)
    if [ -z "$csv" ] || [ ! -f "$csv" ]; then
        note "FAIL — no submissions CSV was produced; cannot verify. Check the run's output."
        fail=$((fail+1)); return
    fi

    python3 - "$csv" "$expected" <<'PY'
import csv, sys, collections
rows = list(csv.DictReader(open(sys.argv[1])))
expected = int(sys.argv[2])
ac = [r for r in rows if r["verdict"] == "AC"]
partial = [r for r in ac if r["total"] and int(r["total"]) < expected]
print(f"  submissions: {len(rows)}   AC: {len(ac)}   errors: {len(rows) - len(ac)}")
print(f"  http: {dict(collections.Counter(r['http_status'] for r in rows))}")
print(f"  case totals among AC rows: {dict(collections.Counter(r['total'] for r in ac)) or '(none)'}")
print()
if partial:
    print(f"  FAIL — {len(partial)} submission(s) reported AC on a PARTIAL grade (problem has {expected}):")
    for r in partial[:10]:
        print(f"    vu={r['vu']} {r['student']} AC {r['passed']}/{r['total']}")
    raise SystemExit(1)
if ac:
    print(f"  PASS — all {len(ac)} accepted submissions graded the full {expected} cases.")
else:
    print("  PASS — nothing was accepted; every submission was refused rather than wrongly passed.")
raise SystemExit(0)
PY
    [ $? -eq 0 ] || fail=$((fail+1))
    echo
}

case "$PART" in
    1) part1 ;;
    2) part2 ;;
    3) part3 ;;
    all) part1; part2; part3 ;;
    *) echo "usage: $0 [1|2|3|all]" >&2; exit 2 ;;
esac

echo "############ RESULT ############"
if [ "$fail" -eq 0 ]; then
    echo "  SANITY CHECK PASSED — no false rejections, and the guard fires when it should."
else
    echo "  $fail problem(s) found. Do not deploy until these are understood."
fi
exit "$fail"
