#!/usr/bin/env bash
# Server-side resource capture for the duration of a load-test run.
#
# Why this exists: every load test run before this one was judged purely on k6's client-side numbers.
# When a run degraded we had no way to tell whether the host was saturated, how many grading
# containers were actually running, or whether memory was under pressure — so "contention" was a
# guess rather than a measurement. One run's per-case timings were misread three separate ways for
# exactly this reason.
#
# Writes CSV so a run can be analysed after the fact instead of living in terminal scrollback.
#
# Usage (start BEFORE k6, stop after):
#   ./capture-metrics.sh ~/cs30loadtest/results/<run-name>-metrics.csv 2 &
#   CAP=$!
#   ... run k6 ...
#   kill $CAP
#
# Deliberately dependency-free: CPU comes from /proc/stat deltas rather than mpstat/sysstat, so this
# works on a bare server without installing anything.
set -uo pipefail

OUT="${1:?usage: capture-metrics.sh <output.csv> [interval_seconds]}"
INTERVAL="${2:-2}"

mkdir -p "$(dirname "$OUT")"

# cpu_busy_pct is computed across the whole machine (all cores aggregated): 100 - idle%.
# containers_total is every running container; containers_judge counts only grading sandboxes, which
# is the number that should plateau at judge.concurrency.max-workers under load.
echo "timestamp,cpu_busy_pct,load1,load5,mem_total_kb,mem_available_kb,mem_used_pct,containers_total,containers_judge" > "$OUT"

read_cpu() {
    # /proc/stat first line: cpu user nice system idle iowait irq softirq steal guest guest_nice
    local line
    line=$(head -1 /proc/stat)
    set -- $line
    shift # drop the literal "cpu"
    local idle_all=$(( $4 + $5 ))          # idle + iowait
    local total=0 v
    for v in "$@"; do total=$(( total + v )); done
    echo "$total $idle_all"
}

prev=$(read_cpu)
prev_total=${prev% *}
prev_idle=${prev#* }

trap 'exit 0' TERM INT

while true; do
    sleep "$INTERVAL"

    cur=$(read_cpu)
    cur_total=${cur% *}
    cur_idle=${cur#* }
    d_total=$(( cur_total - prev_total ))
    d_idle=$(( cur_idle - prev_idle ))
    if [ "$d_total" -gt 0 ]; then
        cpu_busy=$(awk -v t="$d_total" -v i="$d_idle" 'BEGIN{printf "%.1f", 100*(t-i)/t}')
    else
        cpu_busy="0.0"
    fi
    prev_total=$cur_total
    prev_idle=$cur_idle

    read -r load1 load5 _ < /proc/loadavg

    mem_total=$(awk '/^MemTotal:/{print $2}' /proc/meminfo)
    mem_avail=$(awk '/^MemAvailable:/{print $2}' /proc/meminfo)
    mem_used_pct=$(awk -v t="$mem_total" -v a="$mem_avail" 'BEGIN{ if (t>0) printf "%.1f", 100*(t-a)/t; else print "0.0" }')

    # `|| echo 0` so a docker hiccup degrades one sample instead of killing the capture.
    c_total=$(docker ps -q 2>/dev/null | wc -l | tr -d ' ' || echo 0)
    c_judge=$(docker ps --filter "name=kt-judge" -q 2>/dev/null | wc -l | tr -d ' ' || echo 0)

    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$cpu_busy,$load1,$load5,$mem_total,$mem_avail,$mem_used_pct,$c_total,$c_judge" >> "$OUT"
done
