#!/usr/bin/env bash
#
# Create the CS30 service users and groups, and set the deploy-dir permissions they need.
#
# This is the base layer only. Teacher CLI access (the cs30 wrapper, its sudoers rule and the
# cs30-grant command) and repo access (problem pool / problem repos / student repos, granted by
# grant-pool-access.sh) are set up separately, after this script.
#
# Users:
#   - cs30backend : runs the backend + CLI; OWNS (and therefore writes) the problem pool
#                   and student repos.
#   - cs30judge   : runs the judge; only READS the pool; in the docker group.
#
# Groups (kept deliberately minimal: write is via ownership, groups are for readers):
#   - cs30problems : the pool READERS + the gid the judge sandbox container runs as. Members:
#                    cs30judge (backend owns the pool, so its membership is only a read fallback).
#                    judge.sandbox.group resolves this group's gid on the host at runtime.
#   - cs30teachers : humans allowed to run the CLI as cs30backend. Created here so the group
#                    exists; what it may run is granted by the teacher-access setup.
#
# Access model:
#   - deploy dir : root-owned, per-user ACLs granting cs30backend + cs30judge read only (no
#                  shared group needed for two static readers). DEPLOY_DIR (default /opt/cs30)
#                  holds the jars + application.properties.
#   - problem pool / problem repos / student repos : granted by grant-pool-access.sh, not here.
#
# Idempotent: safe to re-run. Must run as root (sudo).
#   sudo ./service-users-groups.sh
set -euo pipefail

BACKEND_USER="${BACKEND_USER:-cs30backend}"
JUDGE_USER="${JUDGE_USER:-cs30judge}"
POOL_GROUP="${POOL_GROUP:-${SHARED_GROUP:-cs30problems}}"   # SHARED_GROUP kept for back-compat
TEACHERS_GROUP="${TEACHERS_GROUP:-cs30teachers}"
DOCKER_GROUP="${DOCKER_GROUP:-docker}"

DEPLOY_DIR="${DEPLOY_DIR:-/opt/cs30}"

log()  { printf '  %s\n' "$*"; }
warn() { printf '  WARNING: %s\n' "$*" >&2; }
step() { printf '\n== %s ==\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { echo "Run as root (sudo $0)"; exit 1; }

HAVE_SETFACL=1
command -v setfacl >/dev/null 2>&1 || { HAVE_SETFACL=0; warn "setfacl not found, install the 'acl' package; ACL steps will be skipped"; }

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
ensure_group "$TEACHERS_GROUP"

step "Service users"
ensure_user "$BACKEND_USER"
ensure_user "$JUDGE_USER"

step "Group memberships"
# Judge is the pool READER (host + container gid). Backend OWNS the pool, so its membership is
# only a fallback for reading files it didn't create, harmless to include.
usermod -aG "$POOL_GROUP" "$JUDGE_USER";   log "$JUDGE_USER  -> $POOL_GROUP  (pool read + container gid)"
usermod -aG "$POOL_GROUP" "$BACKEND_USER"; log "$BACKEND_USER  -> $POOL_GROUP  (read fallback; writes as owner)"
# The judge shells out to `docker run`, so it needs the docker socket.
if getent group "$DOCKER_GROUP" >/dev/null; then
    usermod -aG "$DOCKER_GROUP" "$JUDGE_USER"
    log "$JUDGE_USER  -> $DOCKER_GROUP  (docker socket; note: root-equivalent on the host)"
else
    warn "group '$DOCKER_GROUP' not found, install Docker, then: usermod -aG $DOCKER_GROUP $JUDGE_USER"
fi

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
    warn "no setfacl, grant read another way, e.g. a shared group; jars would otherwise be root-only"
fi

POOL_GID="$(getent group "$POOL_GROUP" | cut -d: -f3)"
JUDGE_UID="$(id -u "$JUDGE_USER")"

cat <<EOF

== Done. Next steps (not automated) ==

1. systemd units: run each service as its user, deploy dir as WorkingDirectory:
     backend unit:  User=$BACKEND_USER   WorkingDirectory=$DEPLOY_DIR
     judge   unit:  User=$JUDGE_USER     WorkingDirectory=$DEPLOY_DIR
   then: sudo systemctl daemon-reload && sudo systemctl restart <unit>
   (group changes only take effect after the service is restarted.)

2. Judge config (in $DEPLOY_DIR/application.properties), so the container reads the pool:
     judge.sandbox.group=$POOL_GROUP   # judge resolves its GID ($POOL_GID) on the host at runtime
     judge.sandbox.uid=$JUDGE_UID        # uid of '$JUDGE_USER' (optional)

3. Repo access: run grant-pool-access.sh as whoever OWNS the repos, as themselves (no sudo needed
   to change ACLs on your own dirs):
     PROBLEM_POOL_DIR=... PROBLEM_REPO_DIR=... STUDENTS_DIR=... ./grant-pool-access.sh
   Point each course's studentGitRepo / problemGitRepo (in the DB) at those dirs.

4. Keep the judge internal (bound to 127.0.0.1); its docker-group membership is
   root-equivalent on the host, so it must not be publicly reachable.

5. Teachers: the '$TEACHERS_GROUP' group exists but grants nothing on its own. Install the teacher
   CLI access separately, then add members with:
     sudo usermod -aG $TEACHERS_GROUP <username>   (they must log out and back in)

6. Whatever account performs deploys needs write on $DEPLOY_DIR, which is root-owned here. Grant it
   explicitly, for example:
     sudo setfacl -m u:<deploy-user>:rwX -d -m u:<deploy-user>:rwX $DEPLOY_DIR
EOF
