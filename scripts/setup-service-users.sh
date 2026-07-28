#!/usr/bin/env bash
#
# Set up separate, least-privilege service users, groups, and directory permissions
# for the CS30 backend and judge.
#
# Users:
#   - cs30backend : runs the backend + CLI; OWNS (and therefore writes) the problem pool
#                   and student repos.
#   - cs30judge   : runs the judge; only READS the pool; in the docker group.
#
# Groups (kept deliberately minimal — write is via ownership, groups are for readers):
#   - cs30problems : the pool READERS + the gid the judge sandbox container runs as. Members:
#                    cs30judge (backend owns the pool, so its membership is only a read fallback).
#                    judge.sandbox.group resolves this group's gid on the host at runtime.
#
# Access model:
#   - problem pool  : owner cs30backend (rwx=write), group cs30problems (r-x=judge read+traverse,
#                     container gid), setgid, other=--- (NOT world-readable → secrets safe).
#   - student repos : owner cs30backend (rwx=write), mode 2700, no group (single writer).
#   - deploy dir    : root-owned, per-user ACLs granting cs30backend + cs30judge read only
#                     (no shared group needed for two static readers).
#
# Directory permissions are applied when the matching *_DIR is set:
#   DEPLOY_DIR   (default /opt/cs30) : jars + application.properties, services read-only.
#   POOL_DIR     (unset by default)  : problem pool.
#   STUDENTS_DIR (unset by default)  : student repos.
#
# Idempotent: safe to re-run. Must run as root (sudo).
#   sudo POOL_DIR=/srv/cs30/problems STUDENTS_DIR=/srv/cs30/students ./setup-service-users.sh
set -euo pipefail

BACKEND_USER="${BACKEND_USER:-cs30backend}"
JUDGE_USER="${JUDGE_USER:-cs30judge}"
POOL_GROUP="${POOL_GROUP:-${SHARED_GROUP:-cs30problems}}"   # SHARED_GROUP kept for back-compat
DOCKER_GROUP="${DOCKER_GROUP:-docker}"

DEPLOY_DIR="${DEPLOY_DIR:-/opt/cs30}"
POOL_DIR="${POOL_DIR:-}"
STUDENTS_DIR="${STUDENTS_DIR:-}"

log()  { printf '  %s\n' "$*"; }
warn() { printf '  WARNING: %s\n' "$*" >&2; }
step() { printf '\n== %s ==\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { echo "Run as root (sudo $0)"; exit 1; }

HAVE_SETFACL=1
command -v setfacl >/dev/null 2>&1 || { HAVE_SETFACL=0; warn "setfacl not found — install the 'acl' package; ACL steps will be skipped"; }

ensure_group() {
    local g="$1" flag="${2:-}"
    if getent group "$g" >/dev/null; then log "group '$g' already exists"
    else groupadd $flag "$g"; log "created group '$g'"; fi
}
ensure_user() {
    local u="$1"
    if id "$u" >/dev/null 2>&1; then log "user '$u' already exists"
    else useradd --system --create-home --shell /usr/sbin/nologin "$u"; log "created user '$u'"; fi
}

step "Groups"
ensure_group "$POOL_GROUP" --system

step "Service users"
ensure_user "$BACKEND_USER"
ensure_user "$JUDGE_USER"

step "Group memberships"
# Judge is the pool READER (host + container gid). Backend OWNS the pool, so its membership is
# only a fallback for reading files it didn't create — harmless to include.
usermod -aG "$POOL_GROUP" "$JUDGE_USER";   log "$JUDGE_USER  -> $POOL_GROUP  (pool read + container gid)"
usermod -aG "$POOL_GROUP" "$BACKEND_USER"; log "$BACKEND_USER  -> $POOL_GROUP  (read fallback; writes as owner)"
# The judge shells out to `docker run`, so it needs the docker socket.
if getent group "$DOCKER_GROUP" >/dev/null; then
    usermod -aG "$DOCKER_GROUP" "$JUDGE_USER"
    log "$JUDGE_USER  -> $DOCKER_GROUP  (docker socket; note: root-equivalent on the host)"
else
    warn "group '$DOCKER_GROUP' not found — install Docker, then: usermod -aG $DOCKER_GROUP $JUDGE_USER"
fi

# --- directory permissions ------------------------------------------------

step "Deploy dir: $DEPLOY_DIR  (jars + config; both services read-only, per-user)"
mkdir -p "$DEPLOY_DIR"
chown root:root "$DEPLOY_DIR"
chmod 750 "$DEPLOY_DIR"
if [ "$HAVE_SETFACL" = 1 ]; then
    setfacl    -m u:"$BACKEND_USER":rX -m u:"$JUDGE_USER":rX "$DEPLOY_DIR"
    setfacl -d -m u:"$BACKEND_USER":rX -m u:"$JUDGE_USER":rX "$DEPLOY_DIR"   # files copied in inherit read
    if compgen -G "$DEPLOY_DIR/*" >/dev/null 2>&1; then
        find "$DEPLOY_DIR" -mindepth 1 -exec setfacl -m u:"$BACKEND_USER":rX -m u:"$JUDGE_USER":rX {} +
        find "$DEPLOY_DIR" -mindepth 1 -type f -exec chmod 640 {} +
    fi
    log "root-owned; ACL grants $BACKEND_USER + $JUDGE_USER read only; other = none (not world-readable)"
else
    warn "no setfacl — grant read another way, e.g. a shared group; jars would otherwise be root-only"
fi

if [ -n "$POOL_DIR" ]; then
    step "Problem pool: $POOL_DIR  (backend owns/writes; judge + container read; NOT world-readable)"
    if [ -d "$POOL_DIR" ]; then
        chown -R "$BACKEND_USER":"$POOL_GROUP" "$POOL_DIR"
        find "$POOL_DIR" -type d -exec chmod 2750 {} +   # setgid; owner rwx, group r-x, other ---
        find "$POOL_DIR" -type f -exec chmod 640 {} +
        if [ "$HAVE_SETFACL" = 1 ]; then
            # new problems (created by backend) stay group-readable for judge; backend keeps write
            setfacl -R -d -m g:"$POOL_GROUP":rX -m u:"$BACKEND_USER":rwX "$POOL_DIR"
        fi
        log "owner $BACKEND_USER (write), group $POOL_GROUP (judge/container read), setgid, other none"
    else
        warn "POOL_DIR '$POOL_DIR' does not exist — create it first, then re-run"
    fi
else
    log "(set POOL_DIR=/path to apply problem-pool permissions)"
fi

if [ -n "$STUDENTS_DIR" ]; then
    step "Student repos: $STUDENTS_DIR  (backend owns/writes; single writer, no group)"
    if [ -d "$STUDENTS_DIR" ]; then
        chown -R "$BACKEND_USER":"$BACKEND_USER" "$STUDENTS_DIR"
        find "$STUDENTS_DIR" -type d -exec chmod 2700 {} +   # setgid; owner-only, other/group none
        find "$STUDENTS_DIR" -type f -exec chmod 600 {} +
        log "owner $BACKEND_USER only; add a read group/ACL later if graders need access"
    else
        warn "STUDENTS_DIR '$STUDENTS_DIR' does not exist — create it first, then re-run"
    fi
else
    log "(set STUDENTS_DIR=/path to apply student-repo permissions)"
fi

# --- summary / next steps -------------------------------------------------
POOL_GID="$(getent group "$POOL_GROUP" | cut -d: -f3)"
JUDGE_UID="$(id -u "$JUDGE_USER")"

cat <<EOF

== Done. Next steps (not automated) ==

1. systemd units — run each service as its user, deploy dir as WorkingDirectory:
     backend unit:  User=$BACKEND_USER   WorkingDirectory=$DEPLOY_DIR
     judge   unit:  User=$JUDGE_USER     WorkingDirectory=$DEPLOY_DIR
   then: sudo systemctl daemon-reload && sudo systemctl restart <unit>
   (group changes only take effect after the service is restarted.)

2. Judge config (in $DEPLOY_DIR/application.properties) — so the container reads the pool:
     judge.sandbox.group=$POOL_GROUP   # judge resolves its GID ($POOL_GID) on the host at runtime
     judge.sandbox.uid=$JUDGE_UID        # uid of '$JUDGE_USER' (optional)

3. Point each course's studentGitRepo / problemGitRepo (in the DB) at the dirs you set up.
   Keep them OUT of personal homes — re-run with POOL_DIR=... STUDENTS_DIR=... to (re)apply perms.

4. Keep the judge internal (bound to 127.0.0.1); its docker-group membership is
   root-equivalent on the host, so it must not be publicly reachable.
EOF
