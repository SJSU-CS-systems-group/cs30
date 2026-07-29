#!/usr/bin/env bash
#
# Grant the CS30 service accounts access to repos YOU own. Run it as yourself (the owner of the
# repos): changing ACLs on your own directories needs no root or sudo. It grants:
#   cs30backend        : read + WRITE + traverse (rwX). The backend service reads/writes here.
#   cs30problems group : read + traverse (rX). The judge reads problem data to grade.
# and, on ancestor directories you own, cs30backend traverse (x), so the service can reach the
# repos even when they live under your home.
#
# You name the dirs by TYPE via env vars, so the student repo is never exposed to the judge.
# All three dirs are required.
#
#   PROBLEM_POOL_DIR : the global problem pool.  -> cs30backend rwX + cs30problems rX
#   PROBLEM_REPO_DIR : a course's problem repo.  -> cs30backend rwX + cs30problems rX
#   STUDENTS_DIR     : student submissions.      -> cs30backend rwX only (judge gets NOTHING)
#
# Applies to existing files AND future ones (default ACLs), so redeploys and newly added
# problems/submissions stay accessible without re-running.
#
# Usage (run as the owner of the dirs; all three required):
#   PROBLEM_POOL_DIR=/home/me/cs30/problems-pool \
#   PROBLEM_REPO_DIR=/home/me/cs30/repos/problems \
#   STUDENTS_DIR=/home/me/cs30/repos/students \
#   ./grant-pool-access.sh
#
# The beneficiary user (cs30backend) and judge group (cs30problems) are FIXED below.
set -euo pipefail

readonly BACKEND_USER="cs30backend"
readonly JUDGE_GROUP="cs30problems"
PROBLEM_POOL_DIR="${PROBLEM_POOL_DIR:-}"
PROBLEM_REPO_DIR="${PROBLEM_REPO_DIR:-}"
STUDENTS_DIR="${STUDENTS_DIR:-}"

die()  { echo "ERROR: $*" >&2; exit 1; }
warn() { echo "  WARNING: $*" >&2; }

command -v setfacl >/dev/null 2>&1 || die "setfacl not found: install the 'acl' package"
id "$BACKEND_USER" >/dev/null 2>&1 || die "user '$BACKEND_USER' does not exist (run setup-service-users.sh first)"

# All three dirs are required; report every one that's missing.
missing=()
[ -n "$PROBLEM_POOL_DIR" ] || missing+=(PROBLEM_POOL_DIR)
[ -n "$PROBLEM_REPO_DIR" ] || missing+=(PROBLEM_REPO_DIR)
[ -n "$STUDENTS_DIR" ]     || missing+=(STUDENTS_DIR)
[ "${#missing[@]}" -eq 0 ] \
    || die "missing required env var(s): ${missing[*]}  (all of PROBLEM_POOL_DIR, PROBLEM_REPO_DIR, STUDENTS_DIR must be set)"

# You must own a directory to change its ACLs (or be root).
owns() { [ -O "$1" ] || [ "$(id -u)" -eq 0 ]; }

# cs30backend: full read/write/traverse on existing files AND everything added later.
grant_backend() {
    setfacl -R    -m u:"$BACKEND_USER":rwX "$1"
    setfacl -R -d -m u:"$BACKEND_USER":rwX "$1"
    echo "  $BACKEND_USER : rwX  (existing + inherited)"
}

grant_judge() {
    if getent group "$JUDGE_GROUP" >/dev/null; then
        setfacl -R    -m g:"$JUDGE_GROUP":rX "$1"
        setfacl -R -d -m g:"$JUDGE_GROUP":rX "$1"
        echo "  $JUDGE_GROUP (group) : rX  (existing + inherited)"
    else
        warn "group '$JUDGE_GROUP' not found, skipping judge read grant"
    fi
}

# Give cs30backend traverse (x) on ancestor dirs you own, so the service can reach the repo.
# Walk up from the repo's parent; stop at the first ancestor you do not own (e.g. /home), which
# must already be traversable by cs30backend.
grant_traversal() {
    local p; p="$(dirname "$(realpath "$1")")"
    while [ "$p" != "/" ] && owns "$p"; do
        setfacl -m u:"$BACKEND_USER":x "$p"
        echo "  $BACKEND_USER : --x on ancestor $p"
        p="$(dirname "$p")"
    done
}

# $1 = dir, $2 = label, $3 = "judge" to also grant the judge group.
apply() {
    [ -d "$1" ] || { warn "'$1' is not a directory (or unreachable), skipping"; return 0; }
    local dir; dir="$(realpath "$1")"
    if ! owns "$dir"; then
        warn "you do not own '$dir', so you cannot grant access on it; run as its owner or root; skipping"
        return 0
    fi
    printf '\n== %s  (%s) ==\n' "$dir" "$2"
    grant_backend "$dir"
    if [ "${3:-}" = judge ]; then grant_judge "$dir"
    else echo "  judge : none  (student submissions stay private)"; fi
    grant_traversal "$dir"
}

apply "$PROBLEM_POOL_DIR" "problem pool" judge
apply "$PROBLEM_REPO_DIR" "problem repo" judge
apply "$STUDENTS_DIR"     "student repo"

cat <<EOF

Done.
  - $BACKEND_USER can read/write the granted repos and traverse the dirs you own to reach them.
  - the judge reads only PROBLEM_POOL_DIR / PROBLEM_REPO_DIR, via group '$JUDGE_GROUP'.
  - STUDENTS_DIR is backend-only; the judge has no access to student submissions.

Run this as the user who owns the repos; changing ACLs on your own dirs needs no root or sudo.
EOF
