#!/usr/bin/env bash
set -euo pipefail

export HOME=/work
mkdir -p /work/problem

# Stage the read-only mounted problem into writable tmpfs so bt can
# write its cache and compiled validators.
cp -r /problem/. /work/problem/

# Stage submission file and any optional custom-case files.
if [[ -d /in ]]; then
    shopt -s nullglob
    for f in /in/*; do
        case "$(basename "$f")" in
            custom.in)
                cp "$f" /work/problem/data/sample/_custom.in
                ;;
            custom.ans)
                cp "$f" /work/problem/data/sample/_custom.ans
                ;;
            *)
                cp "$f" "/work/problem/$(basename "$f")"
                ;;
        esac
    done
fi

cd /work/problem
exec bt "$@"
