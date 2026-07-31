#!/usr/bin/env bash
#
# Grant the CS30 service accounts access to repos YOU own. Run it as yourself (the owner of the
# repos): changing ACLs on your own directories needs no root or sudo. It grants:
#   cs30backend        : read + WRITE + traverse (rwX). The backend service reads/writes here.
#   cs30problems group : read + traverse (rX). The judge reads problem data to grade.
#   the dir's owner    : read + WRITE + traverse (rwX), on all three dirs. cs30backend creates
#                        files as itself, so on anything it creates the owner entry (user::)
#                        belongs to IT, and you drop to other:: and lose access to your own
#                        tree. A named entry for you follows you no matter who creates a file.
# and, on ancestor directories you own, cs30backend traverse (x), so the service can reach the
# repos even when they live under your home.
#
# You name the dirs by TYPE via env vars, so the student repo is never exposed to the judge.
# All three dirs are required.
#
#   PROBLEM_POOL_DIR : the global problem pool.  -> cs30backend rwX only (judge gets NOTHING)
#   PROBLEM_REPO_DIR : a course's problem repo.  -> cs30backend rwX + cs30problems rX
#   STUDENTS_DIR     : student submissions.      -> cs30backend rwX only (judge gets NOTHING)
#
# The pool is backend-only staging: a problem is converted there and then copied into the
# course's problem repo, and that repo is the only path the judge ever mounts (the backend
# sends course.problemGitRepo as the judge's pool_path). So the judge needs no pool access.
#
# Applies to existing files AND future ones (default ACLs), so redeploys and newly added
# problems/submissions stay accessible without re-running.
#
# A dir that does not exist yet is created (owned by you, the caller) and then granted, so the
# backend never has to create it itself and inherit no ACLs.
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

# Apply one ACL entry to a whole tree: to what is there now, and as a default so anything added
# later inherits it.
set_acl() {
    setfacl -R    -m "$1" "$2"
    setfacl -R -d -m "$1" "$2"
}

# cs30backend: full read/write/traverse on existing files AND everything added later.
grant_backend() { set_acl u:"$BACKEND_USER":rwX "$1"; }

grant_judge() {
    getent group "$JUDGE_GROUP" >/dev/null || { warn "group '$JUDGE_GROUP' not found, judge read NOT granted"; return 1; }
    set_acl g:"$JUDGE_GROUP":rX "$1"
}

# The dir's owner, so the services cannot lock you out of your own tree (see the header note).
# Printed, not echoed, so apply() can put it on the one-line summary. Skipped when the owner is
# root (bypasses ACLs) or cs30backend (already granted above).
grant_owner() {
    local owner; owner="$(stat -c %U "$1")"
    case "$owner" in
        root|"$BACKEND_USER") printf 'none'; return 0 ;;
    esac
    set_acl u:"$owner":rwX "$1"
    printf '%s rwX' "$owner"
}

# Give cs30backend traverse (x) on ancestor dirs you own, so the service can reach the repo.
# Walk up from the repo's parent; stop at the first ancestor you do not own (e.g. /home), which
# must already be traversable by cs30backend. Prints how many it granted.
grant_traversal() {
    local p n=0
    p="$(dirname "$(realpath "$1")")"
    while [ "$p" != "/" ] && owns "$p"; do
        setfacl -m u:"$BACKEND_USER":x "$p"
        n=$((n + 1))
        p="$(dirname "$p")"
    done
    printf '%s' "$n"
}

# $1 = dir, $2 = label, $3 = "judge" to also grant the judge group.
apply() {
    # Create a missing dir rather than skipping it. The backend creates these at runtime if
    # they are absent (`mkdir -p <repo> && git init`), and it cannot do that without write on
    # the parent. Granting write on the parent instead would be worse: it opens a directory
    # wider than the one being granted, and the dir the backend then creates would inherit
    # nothing, leaving the judge unable to read the problem repo. Creating it here means the
    # grants below land on the real dir, and its default ACLs cover everything added later.
    # -e not -d: bail on a non-directory (e.g. a file at that path) instead of mkdir failing.
    local made=""
    if [ ! -e "$1" ]; then
        mkdir -p "$1" || { warn "cannot create '$1', skipped"; return 0; }
        made=" (created)"
    fi
    [ -d "$1" ] || { warn "'$1' is not a directory, skipped"; return 0; }
    local dir; dir="$(realpath "$1")"
    owns "$dir" || { warn "you do not own '$dir', skipped (run as its owner)"; return 0; }

    grant_backend "$dir"
    local owner; owner="$(grant_owner "$dir")"
    local judge="none"
    if [ "${3:-}" = judge ] && grant_judge "$dir"; then judge="rX"; fi
    local hops; hops="$(grant_traversal "$dir")"

    printf '%-13s %s%s\n' "$2" "$dir" "$made"
    printf '%-13s %s rwX, %s %s, owner %s, +x on %s parents\n' "" "$BACKEND_USER" "$JUDGE_GROUP" "$judge" "$owner" "$hops"
}

apply "$PROBLEM_POOL_DIR" "problem pool"
apply "$PROBLEM_REPO_DIR" "problem repo" judge
apply "$STUDENTS_DIR"     "student repo"

cat <<EOF

Done. Grants cover existing files and everything added later.
Only the problem repo is readable by '$JUDGE_GROUP'; the pool and student dirs are
$BACKEND_USER-only.
EOF
