#!/usr/bin/env bash
# Reproduce and diagnose bt hanging on an interactive problem under concurrency.
#
# WHAT IS KNOWN GOING IN (measured, not assumed)
#   - 8/8 sequential `bt run` on the interactive problem complete all 100 cases.
#   - 16 concurrent runs left 9 containers wedged for 25 minutes; they exited only when killed.
#   - One solo run died with PermissionError on fcntl(F_SETPIPE_SZ) after ~9s.
#   - Container has pipe-max-size 1048576 and CapEff 0000000000000000 (no CAP_SYS_RESOURCE).
#   - bapctools sets pipesize=BUFFER_SIZE at 4 sites, all in interactive.py.
# The mechanism is NOT established. This script exists to establish it.
#
# HOW IT DIAGNOSES A HANG
#   Containers are started detached and named, so a wedged one can be inspected while still wedged
#   rather than post-mortem. For each container still running after the grace period it captures:
#     - every process in the container's PID namespace, with its scheduler state and wait channel
#       (read from /proc directly — `ps` may not exist in the image)
#     - open file descriptors of the bt process, which shows the pipes it is blocked on
#     - a full Python traceback of every thread, forced by sending SIGABRT to bt with
#       PYTHONFAULTHANDLER=1 set. This is the part that names the exact line it is stuck on.
#
# Everything is bounded and cleaned up: no container outlives the script. That matters because the
# earlier 16-way attempt left 9 containers running for 25 minutes, each holding a 2560m memory cap.
#
# Usage:
#   ./debug-interactive-hang.sh [concurrency] [grace-seconds] [problem] [solution]
# Example:
#   ./debug-interactive-hang.sh 16 90
set -uo pipefail

N="${1:-16}"
GRACE="${2:-90}"
PROBLEM="${3:-the interactive problem}"
SOLUTION="${4:-<accepted-answer>.cpp}"
POOL="${POOL:?set POOL=<path to the problem pool>}"
IMAGE="${IMAGE:-judge-sandbox:latest}"
OUT="${OUT:-/tmp/interactive-debug}"
PREFIX="ihang"

D="$POOL/$PROBLEM"
[ -d "$D" ] || { echo "problem not found: $D" >&2; exit 1; }

rm -rf "$OUT"; mkdir -p "$OUT"

cleanup() {
    echo
    echo "[cleanup] removing any surviving containers"
    for i in $(seq 1 "$N"); do docker rm -f "$PREFIX$i" >/dev/null 2>&1; done
    docker ps --filter "name=$PREFIX" --format '  still up: {{.Names}}'
}
trap cleanup EXIT

# PYTHONFAULTHANDLER is the whole point: with it set, SIGABRT makes CPython print a traceback for
# every thread to stderr instead of dying silently. Without it a hung bt tells us nothing.
RUN="mkdir -p /tmp/w && cp -r /problem/. /tmp/w/ 2>/dev/null
     cp /problem/submissions/accepted/$SOLUTION /tmp/w/
     cd /tmp/w && exec bt run -ve -aa --no-bar $SOLUTION"

printf 'problem=%s solution=%s concurrency=%s grace=%ss\n\n' "$PROBLEM" "$SOLUTION" "$N" "$GRACE"

echo "[1/4] launching $N detached containers"
for i in $(seq 1 "$N"); do
    docker run -d --name "$PREFIX$i" \
        -e PYTHONFAULTHANDLER=1 -e PYTHONUNBUFFERED=1 \
        --cpus=1.0 --memory=2560m --entrypoint bash \
        -v "$D":/problem:ro "$IMAGE" -c "$RUN" >/dev/null 2>&1 \
        || echo "  failed to start $PREFIX$i"
done
started=$(docker ps -q --filter "name=$PREFIX" | wc -l | tr -d ' ')
echo "  started: $started"

echo "[2/4] waiting up to ${GRACE}s, sampling how many are still alive"
for t in $(seq 10 10 "$GRACE"); do
    sleep 10
    alive=$(docker ps -q --filter "name=$PREFIX" | wc -l | tr -d ' ')
    printf '  t=%-4s alive=%s\n' "${t}s" "$alive"
    [ "$alive" = "0" ] && break
done

STUCK=$(docker ps --filter "name=$PREFIX" --format '{{.Names}}')
if [ -z "$STUCK" ]; then
    echo
    echo "[3/4] NOTHING HUNG — all $started containers exited within ${GRACE}s."
    echo "      Per-container outcome:"
    for i in $(seq 1 "$N"); do
        log="$OUT/$PREFIX$i.log"
        docker logs "$PREFIX$i" > "$log" 2>&1
        printf '  %-12s exit=%-4s cases=%-4s eperm=%s\n' "$PREFIX$i" \
            "$(docker inspect -f '{{.State.ExitCode}}' "$PREFIX$i" 2>/dev/null)" \
            "$(grep -cE ': +(AC|WA|TLE|RTE|MLE) ' "$log")" \
            "$(grep -c 'Operation not permitted' "$log")"
    done
    echo
    echo "  The hang did not reproduce at concurrency=$N. Try a higher value, or run this while"
    echo "  the judge is also busy — the earlier 9/16 hang happened on a machine that had just"
    echo "  been running load tests."
    exit 0
fi

echo
echo "[3/4] STUCK CONTAINERS: $(printf '%s' "$STUCK" | wc -w | tr -d ' ') of $started"
printf '%s\n' "$STUCK" | sed 's/^/  /'

for c in $STUCK; do
    rpt="$OUT/$c.stuck.txt"
    {
        echo "################ $c ################"
        echo "--- uptime / status ---"
        docker inspect -f 'started={{.State.StartedAt}} running={{.State.Running}} pid={{.State.Pid}}' "$c" 2>/dev/null

        # /proc rather than ps: the sandbox image may not ship procps, and /proc always works.
        # State: R running, S interruptible sleep, D uninterruptible (blocked in kernel), Z zombie.
        # wchan names the kernel function it is parked in — for a pipe block this says pipe_read/write.
        echo "--- processes (pid comm state wchan) ---"
        docker exec "$c" sh -c '
            for p in /proc/[0-9]*; do
              pid=${p#/proc/}
              [ -r "$p/stat" ] || continue
              st=$(awk "{print \$3}" "$p/stat" 2>/dev/null)
              printf "  %-6s %-20s %-3s %s\n" "$pid" "$(cat $p/comm 2>/dev/null)" "$st" "$(cat $p/wchan 2>/dev/null)"
            done' 2>&1

        echo "--- bt process open fds (pipes it is holding) ---"
        BTPID=$(docker exec "$c" sh -c '
            for p in /proc/[0-9]*; do
              grep -qs "bt" "$p/comm" 2>/dev/null && { echo ${p#/proc/}; break; }
            done
            # fall back to any python process
            for p in /proc/[0-9]*; do
              grep -qs "python" "$p/comm" 2>/dev/null && { echo ${p#/proc/}; break; }
            done' 2>/dev/null | head -1)
        echo "  bt/python pid = ${BTPID:-not found}"
        if [ -n "$BTPID" ]; then
            docker exec "$c" sh -c "ls -l /proc/$BTPID/fd 2>&1 | head -30" 2>&1 | sed 's/^/  /'
            echo "--- /proc/$BTPID/status (State, Threads) ---"
            docker exec "$c" sh -c "grep -E '^(State|Threads|SigBlk)' /proc/$BTPID/status" 2>&1 | sed 's/^/  /'

            # THE KEY STEP: PYTHONFAULTHANDLER makes SIGABRT dump every thread's Python stack to
            # stderr. That output lands in `docker logs`, which is collected below.
            echo "--- sending SIGABRT to force a Python traceback ---"
            docker exec "$c" sh -c "kill -ABRT $BTPID" 2>&1 | sed 's/^/  /'
            sleep 3
        fi

        echo "--- container log (last 60 lines, includes the faulthandler dump) ---"
        docker logs --tail 60 "$c" 2>&1 | sed 's/^/  /'
        echo "--- cases graded before it wedged ---"
        docker logs "$c" 2>&1 | grep -cE ': +(AC|WA|TLE|RTE|MLE) ' | sed 's/^/  /'
        echo "--- last case it reached ---"
        docker logs "$c" 2>&1 | grep -oE '@ [^ ]+' | tail -3 | sed 's/^/  /'
    } > "$rpt" 2>&1
    echo "  wrote $rpt"
done

echo
echo "[4/4] host-side pipe accounting right now (while wedged)"
{
    echo "pipe-max-size       $(cat /proc/sys/fs/pipe-max-size)"
    echo "pipe-user-pages-soft $(cat /proc/sys/fs/pipe-user-pages-soft)"
    echo "pipe-user-pages-hard $(cat /proc/sys/fs/pipe-user-pages-hard)"
    echo "total pipes open on host: $(ls -l /proc/*/fd 2>/dev/null | grep -c 'pipe:')"
} | sed 's/^/  /' | tee "$OUT/host-pipes.txt"

echo
echo "=== reports in $OUT ==="
ls -1 "$OUT"
echo
echo "Read the faulthandler traceback first — it names the exact line bt is parked on."
echo "Then the wchan column: pipe_read/pipe_write means blocked on a pipe; if the state is D it is"
echo "blocked in the kernel and not merely waiting on a child."
