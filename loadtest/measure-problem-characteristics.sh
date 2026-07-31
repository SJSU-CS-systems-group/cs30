#!/usr/bin/env bash
# Measure every pool problem's real headroom: how long its FASTEST accepted C++ solution takes against
# the author's own time limit, with no load on the machine.
#
# WHY THIS RUNS BEFORE ANY LOAD TEST
#
# A load test that reports TLE tells you nothing unless you already know the problem's idle margin. If a
# problem's reference solution uses 90% of its limit with the box quiet, a TLE under load is expected and
# uninteresting; if it uses 0.1%, the same TLE is a real finding. Only one problem has ever been measured
# (its fastest accepted answer). Every other problem is unknown, so any conclusion drawn from them would be guesswork.
#
# It also surfaces which problems are unusable before you spend a 75-minute run finding out: a package
# that grades 0 cases, or whose accepted solution does not pass, is reported rather than silently skipped.
#
# METHOD
#
#   - Times EVERY accepted C++ solution, then reports the fastest. Picking the first alphabetically was a
#     real mistake earlier: it chose one problem's approximation variant (4.327s) over its actual reference
#     (0.379s) and made a healthy problem look broken by a factor of 10.
#   - Runs under the same --cpus / --memory the judge sandbox uses, so the numbers transfer.
#   - Uses the authored limit from problem.yaml with NO -t override. Passing -t was another earlier error:
#     it overrode the real limit and invalidated the comparison.
#   - Uses --timeout so a solution that exceeds the limit still reports its true elapsed time instead of
#     being cut off at the kill threshold, which leaves you unable to size anything.
#
# Usage:
#   ./measure-problem-characteristics.sh [pool-dir] [report.csv]
# Example:
#   ./measure-problem-characteristics.sh <pool-dir> <report.csv>
set -uo pipefail

POOL="${1:?usage: measure-problem-characteristics.sh <pool-dir> [report.csv]}"
REPORT="${2:-$HOME/cs30loadtest/results/problem-margins.csv}"
IMAGE="${IMAGE:-judge-sandbox:latest}"
CPUS="${CPUS:-1.0}"        # mirrors judge.sandbox.cpus
MEM="${MEM:-2560m}"        # mirrors judge.sandbox.memory-mb
KILL_TIMEOUT="${KILL_TIMEOUT:-120}"

[ -d "$POOL" ] || { echo "pool not found: $POOL" >&2; exit 1; }
mkdir -p "$(dirname "$REPORT")"

echo "problem,validation,time_limit_s,cases,fastest_cpp_solution,slowest_case_s,margin_x,all_cpp_accepted_pass,note" > "$REPORT"
printf 'pool:   %s\nreport: %s\n\n' "$POOL" "$REPORT"

for dir in "$POOL"/*/; do
    p=$(basename "$dir")
    [ -f "$dir/problem.yaml" ] || continue
    printf '=== %-24s ' "$p"

    # 3-line window: one problem's limits block carries time_multipliers above time_limit, and a 2-line
    # window silently returns ac_to_time_limit instead.
    limit=$(grep -A3 '^limits:' "$dir/problem.yaml" 2>/dev/null \
            | grep -oE '^[ ]+time_limit:[ ]*[0-9.]+' | grep -oE '[0-9.]+' | head -1)
    [ -z "$limit" ] && limit="1.0"

    if [ -d "$dir/output_validator" ]; then
        validation="custom"
        grep -qiE '^(validation|type):.*interactive' "$dir/problem.yaml" && validation="interactive"
    else
        validation="diff"
    fi

    sols=$(ls "$dir"/submissions/accepted/*.cpp 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')
    if [ -z "$sols" ]; then
        echo "NO C++ ACCEPTED SOLUTION"
        echo "$p,$validation,$limit,,,,,,no C++ accepted solution" >> "$REPORT"
        continue
    fi

    out=$(docker run --rm --cpus="$CPUS" --memory="$MEM" --entrypoint bash \
            -v "$dir":/problem:ro "$IMAGE" -c "
              mkdir -p /tmp/w && cp -r /problem/. /tmp/w/ 2>/dev/null
              cp /problem/submissions/accepted/*.cpp /tmp/w/ 2>/dev/null
              cd /tmp/w && bt run -ve -aa --no-bar --timeout $KILL_TIMEOUT $sols 2>&1" 2>&1)

    eval "$(printf '%s' "$out" | python3 -c '
import re, sys
worst, bad, cases = {}, set(), {}
for line in sys.stdin:
    m = re.match(r"\s*(\S+):\s+(AC|WA|TLE|RTE|MLE)\s+([0-9.]+)s @ ", line)
    if not m:
        continue
    # bt ends each solution with a summary line that repeats its slowest case:
    #   "answer.cpp:  AC 0.005s @ secret/x  slowest:  AC 0.005s @ secret/x"
    # It matches the same pattern as a real case line, so counting it inflated every case count by
    # exactly one (one problem read 34 where the judge actually grades 33). The timing is a duplicate
    # of the max, so margins were never affected — only the case count.
    if "slowest:" in line:
        continue
    sol, verdict, t = m.group(1), m.group(2), float(m.group(3))
    worst[sol] = max(worst.get(sol, 0.0), t)
    cases[sol] = cases.get(sol, 0) + 1
    if verdict != "AC":
        bad.add(sol)
if worst:
    best = min(worst, key=lambda s: worst[s])
    print("FASTEST=" + best)
    print("SLOWEST=%.3f" % worst[best])
    print("CASES=%d" % cases[best])
    print("ALLPASS=" + ("no" if bad else "yes"))
else:
    print("FASTEST="); print("SLOWEST="); print("CASES=0"); print("ALLPASS=")
' 2>/dev/null)"

    if [ -z "${SLOWEST:-}" ]; then
        fatal=$(printf '%s' "$out" | grep -iE "FATAL ERROR|not a file|No language detected|Traceback" | head -1 | tr -d ',')
        echo "GRADED 0 CASES — ${fatal:-unknown}"
        echo "$p,$validation,$limit,0,,,,,${fatal:-graded 0 cases}" >> "$REPORT"
        unset FASTEST SLOWEST CASES ALLPASS
        continue
    fi

    margin=$(awk -v l="$limit" -v s="$SLOWEST" 'BEGIN{ if (s>0) printf "%.1f", l/s; else print "" }')
    printf '%-9s limit=%-5s cases=%-3s fastest=%-28s slowest=%-7s margin=%sx  allpass=%s\n' \
        "$validation" "${limit}s" "$CASES" "$FASTEST" "${SLOWEST}s" "$margin" "$ALLPASS"
    echo "$p,$validation,$limit,$CASES,$FASTEST,$SLOWEST,$margin,$ALLPASS," >> "$REPORT"
    unset FASTEST SLOWEST CASES ALLPASS
done

printf '\n=== %s ===\n' "$REPORT"
column -s, -t < "$REPORT"

cat <<'EOF'

HOW TO READ THIS

  margin_x = authored limit / slowest case of the fastest accepted C++ solution, measured idle.

  A load test can only meaningfully claim "contention caused this TLE" for a problem whose idle margin
  is wide. For a problem near 1x, TLE under load is the expected outcome and proves nothing about the
  system. Pick scenario problems deliberately from this table, and quote the margin next to every result.

  all_cpp_accepted_pass=no means an accepted solution fails at the authored limit with the machine idle.
  That is a problem-package defect, not a judge or capacity finding — exclude it from load scenarios or
  the run measures the defect instead of the system.

  fastest_cpp_solution is the exact filename to pass as -e SOLUTION_FILE to problem-burst.js.
EOF
