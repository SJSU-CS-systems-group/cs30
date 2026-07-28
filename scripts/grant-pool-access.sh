#!/usr/bin/env bash
#
# Grant the CS30 service accounts access to the repos they use — the problem pool,
# the problem repo, and the student repo. Uses ACLs, so it works even when the repos
# are owned by someone else (root, the CI/deploy user, etc.) without re-chowning.
#
#   - cs30backend : read + WRITE + traverse (rwX). It owns/manages these — adds
#                   problems, generates statement HTML, writes student submissions,
#                   and commits. Needs full access to all of them.
#   - cs30problems (group): read + traverse (rX) — the judge container reads the
#                   PROBLEM POOL to grade. Enabled by default; set JUDGE_GROUP=""
#                   to skip it (e.g. for the student repo, which the judge never
#                   reads and shouldn't).
#
# Applies to existing files AND future ones (default ACLs), so redeploys and newly
# added problems/submissions stay accessible without re-running.
#
# Usage:
#   sudo ./grant-pool-access.sh /path/to/problem-pool /path/to/problem-repo ...
#   sudo JUDGE_GROUP="" ./grant-pool-access.sh /path/to/student-repo    # backend only
# Env:
#   BACKEND_USER (default cs30backend)   the user that owns/writes the repos
#   JUDGE_GROUP  (default cs30problems)  group the judge reads the pool as; "" to skip
set -euo pipefail

BACKEND_USER="${BACKEND_USER:-cs30backend}"
JUDGE_GROUP="${JUDGE_GROUP:-cs30problems}"

die()  { echo "ERROR: $*" >&2; exit 1; }
warn() { echo "  WARNING: $*" >&2; }

[ "$(id -u)" -eq 0 ] || die "run as root:  sudo $0 <dir> [more-dirs...]"
[ "$#" -ge 1 ]       || die "usage: sudo $0 /path/to/dir [more-dirs...]"
command -v setfacl >/dev/null 2>&1 || die "setfacl not found — install the 'acl' package"
id "$BACKEND_USER" >/dev/null 2>&1 || die "user '$BACKEND_USER' does not exist"

for arg in "$@"; do
    [ -d "$arg" ] || { warn "'$arg' is not a directory — skipping"; continue; }
    dir="$(realpath "$arg")"
    printf '\n== %s ==\n' "$dir"

    # cs30backend: full read/write/traverse on existing files AND everything added later.
    # rwX = write on files, and x (traverse) on directories only — not executable files.
    setfacl -R  -m u:"$BACKEND_USER":rwX "$dir"
    setfacl -R -d -m u:"$BACKEND_USER":rwX "$dir"
    echo "  $BACKEND_USER : rwX  (existing + inherited)"

    # Judge read on the problem pool. Skip for the student repo with JUDGE_GROUP="".
    if [ -n "$JUDGE_GROUP" ]; then
        if getent group "$JUDGE_GROUP" >/dev/null; then
            setfacl -R  -m g:"$JUDGE_GROUP":rX "$dir"
            setfacl -R -d -m g:"$JUDGE_GROUP":rX "$dir"
            echo "  $JUDGE_GROUP (group) : rX  (existing + inherited)"
        else
            warn "group '$JUDGE_GROUP' not found — skipping judge read grant"
        fi
    fi
done

cat <<EOF

Done.
  - $BACKEND_USER can read/write these paths (add problems, write submissions, commit).
  - the judge reads the problem pool via group '${JUDGE_GROUP:-<disabled>}'.

Note: $BACKEND_USER also needs execute (x) on every PARENT directory to reach these
paths. Keep the repos out of other users' homes (use e.g. /srv/cs30/...) or grant
traversal on the ancestors:  sudo setfacl -m u:$BACKEND_USER:x /each/parent
EOF
