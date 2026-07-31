#!/usr/bin/env bash
#
# Set up the least-privilege service users, groups, deploy-dir permissions, and teacher CLI
# access for the CS30 backend and judge. Repo access (problem pool / problem repos / student
# repos) is granted separately by grant-pool-access.sh, run after this script.
#
# Users:
#   - cs30backend : runs the backend + CLI; OWNS (and therefore writes) the problem pool
#                   and student repos.
#   - cs30judge   : runs the judge; only READS the pool; in the docker group.
#
# Not created here, but granted access: the CI deploy user (RUNNER_USER, default
# github-runner). It writes the jars, application.properties and the secrets env file
# into DEPLOY_DIR, so it needs write there while the services stay read-only.
#
# Groups (kept deliberately minimal — write is via ownership, groups are for readers):
#   - cs30problems : the pool READERS + the gid the judge sandbox container runs as. Members:
#                    cs30judge (backend owns the pool, so its membership is only a read fallback).
#                    judge.sandbox.group resolves this group's gid on the host at runtime.
#   - cs30teachers : humans allowed to run the CLI as cs30backend (see "Teacher CLI access").
#
# Teacher access (group cs30teachers). Two commands are installed on PATH:
#   /usr/local/bin/cs30       : runs the cs30 CLI jar as cs30backend (course mgmt, canvas sync).
#     The CLI needs cs30backend to reach the DB + owner-only student repos, so it goes through a
#     narrow sudoers rule (/etc/sudoers.d/cs30teachers), NOT a general shell. It allows ALL
#     subcommands on any course, so add only trusted staff. Per-course restriction is not
#     possible through sudoers; it needs an authenticated backend endpoint.
#   /usr/local/bin/cs30-grant : runs grant-pool-access.sh as the CALLER (no sudo). It only
#     changes ACLs on dirs the caller already owns, granting cs30backend + cs30problems access,
#     so it needs no privilege and cannot escalate.
#
# Access model:
#   - deploy dir : root-owned, per-user ACLs granting cs30backend + cs30judge read only
#                  (no shared group needed for two static readers). DEPLOY_DIR (default
#                  /opt/cs30) holds the jars + application.properties. RUNNER_USER gets
#                  rwX on the dir itself, since it writes the release there.
#   - problem pool / problem repos / student repos : granted by grant-pool-access.sh, not here.
#
# Idempotent: safe to re-run. Must run as root (sudo).
#   sudo ./setup-service-users.sh
set -euo pipefail

BACKEND_USER="${BACKEND_USER:-cs30backend}"
JUDGE_USER="${JUDGE_USER:-cs30judge}"
POOL_GROUP="${POOL_GROUP:-${SHARED_GROUP:-cs30problems}}"   # SHARED_GROUP kept for back-compat
DOCKER_GROUP="${DOCKER_GROUP:-docker}"

DEPLOY_DIR="${DEPLOY_DIR:-/opt/cs30}"

# The CI deploy user. It writes the jars + config into DEPLOY_DIR, so it is granted rwX there.
# Set RUNNER_USER= (empty) to skip that grant, e.g. if deploys run as root.
RUNNER_USER="${RUNNER_USER:-github-runner}"

# Teacher access. Override JAVA_BIN / CLI_JAR / GRANT_SCRIPT if they live elsewhere.
TEACHERS_GROUP="${TEACHERS_GROUP:-cs30teachers}"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
CLI_JAR="${CLI_JAR:-$DEPLOY_DIR/current/cs30.jar}"
WRAPPER_PATH="${WRAPPER_PATH:-/usr/local/bin/cs30}"
SUDOERS_FILE="${SUDOERS_FILE:-/etc/sudoers.d/cs30teachers}"
# grant-pool-access.sh exposed on PATH as 'cs30-grant', a plain command run as the caller.
GRANT_SCRIPT="${GRANT_SCRIPT:-$DEPLOY_DIR/scripts/grant-pool-access.sh}"
GRANT_COMMAND="${GRANT_COMMAND:-/usr/local/bin/cs30-grant}"

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
ensure_group "$TEACHERS_GROUP"

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

# --- deploy dir -----------------------------------------------------------

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

    # CI deploys as $RUNNER_USER: it copies application.properties + the env file in, creates
    # releases/<sha> and moves the 'current' symlink, so it needs write on the dir. Granted on
    # the dir only; the default entry covers everything it creates inside.
    # This must run AFTER the chmods above: chmod rewrites the ACL mask, which would otherwise
    # cap these entries back to read-only (the mask applies to named users, not to the owner).
    if [ -z "$RUNNER_USER" ]; then
        log "RUNNER_USER empty; no deploy-user ACL (deploys must run as root)"
    elif id "$RUNNER_USER" >/dev/null 2>&1; then
        setfacl    -m u:"$RUNNER_USER":rwX "$DEPLOY_DIR"
        setfacl -d -m u:"$RUNNER_USER":rwX "$DEPLOY_DIR"
        log "$RUNNER_USER -> rwX on $DEPLOY_DIR (deploy writes; access + default ACL)"
    else
        warn "deploy user '$RUNNER_USER' not found, so CI cannot write $DEPLOY_DIR; re-run with RUNNER_USER=<name>, or RUNNER_USER= to skip"
    fi
else
    warn "no setfacl — grant read another way, e.g. a shared group; jars would otherwise be root-only"
fi

# Repo access (problem pool / problem repos / student repos) is granted by grant-pool-access.sh,
# run after this script; it is not applied here.

# --- teacher CLI access ---------------------------------------------------

step "Teacher CLI: '$TEACHERS_GROUP' members run tools as $BACKEND_USER via wrappers"
# Wrapper so teachers type `cs30 <subcommand>` instead of the full sudo/java line. It runs the
# CLI as $BACKEND_USER, so a teacher's file argument may be unreadable to that user; for the
# single-file options the wrapper stages a readable copy (as the current user) first. The
# install-time paths are baked in by the first (expanded) heredoc; the logic follows verbatim
# in the quoted heredoc so its own $vars are not expanded here. It must invoke java with the
# same string the sudoers rule pins below, so both use $JAVA_BIN.
{
  cat <<EOF
#!/usr/bin/env bash
# Run the cs30 CLI as $BACKEND_USER, the user that owns the student + problem repos.
# Installed by setup-service-users.sh; '$TEACHERS_GROUP' members are allowed via $SUDOERS_FILE.
BACKEND_USER="$BACKEND_USER"
JAVA_BIN="$JAVA_BIN"
CLI_JAR="$CLI_JAR"
EOF
  cat <<'EOF'
set -euo pipefail

# The sudoers rule pins the jar and ends in ' *', so it requires at least one argument: a bare
# `cs30` would be denied by sudo instead of printing usage. Default to --help rather than
# widening the rule, since dropping the args from it would allow java to run any jar.
[ "$#" -gt 0 ] || set -- --help

# Options whose value is a single file the CLI reads. The JVM runs as BACKEND_USER, so a
# teacher's file may be unreadable to it; copy each (as the current user, who can read it) to a
# temp file BACKEND_USER can read, then rewrite the arg. Directory options (--problem-dir,
# --problems-dir) are NOT staged: place those under a dir BACKEND_USER can read.
FILE_OPTS=" --course-file --lab-file "

tmpfiles=()
cleanup() { [ "${#tmpfiles[@]}" -eq 0 ] || rm -f "${tmpfiles[@]}"; }
trap cleanup EXIT

stage() {
    local src="$1" tmp
    tmp="$(mktemp "${TMPDIR:-/tmp}/cs30-stage.XXXXXX")"
    cat -- "$src" > "$tmp"   # read as the current user, who can read their own file
    chmod 0644 "$tmp"        # BACKEND_USER can now read it
    tmpfiles+=("$tmp")
    printf '%s' "$tmp"
}

# Rewrite recognized file options to point at a staged copy. Handles --opt=val and --opt val.
args=()
i=1
while [ "$i" -le "$#" ]; do
    a="${!i}"
    case "$a" in
        --*=*)
            opt="${a%%=*}"; val="${a#*=}"
            if [[ "$FILE_OPTS" == *" $opt "* ]] && [ -f "$val" ]; then
                args+=("$opt=$(stage "$val")")
            else
                args+=("$a")
            fi
            ;;
        --*)
            if [[ "$FILE_OPTS" == *" $a "* ]]; then
                j=$((i + 1)); val="${!j:-}"
                if [ -n "$val" ] && [ -f "$val" ]; then
                    args+=("$a" "$(stage "$val")"); i="$j"
                else
                    args+=("$a")
                fi
            else
                args+=("$a")
            fi
            ;;
        *) args+=("$a") ;;
    esac
    i=$((i + 1))
done

sudo -u "$BACKEND_USER" "$JAVA_BIN" -jar "$CLI_JAR" "${args[@]}"
EOF
} > "$WRAPPER_PATH"
chmod 755 "$WRAPPER_PATH"
log "installed wrapper $WRAPPER_PATH (stages --course-file/--lab-file, runs as $BACKEND_USER)"

# Sudoers: members may run ONLY the pinned jar as the backend user (any subcommand). The command
# path is fully qualified (sudo requirement + PATH-hijack safety).
cat > "$SUDOERS_FILE" <<EOF
# Members of $TEACHERS_GROUP may run the cs30 CLI as $BACKEND_USER, the user that owns the
# student + problem repos. Pinned to this jar, not a general shell. The CLI allows every
# subcommand on any course; add only trusted staff.
%$TEACHERS_GROUP ALL=($BACKEND_USER) NOPASSWD: $JAVA_BIN -jar $CLI_JAR *
EOF
chmod 440 "$SUDOERS_FILE"
if command -v visudo >/dev/null 2>&1; then
    if visudo -cf "$SUDOERS_FILE" >/dev/null 2>&1; then
        log "installed + validated $SUDOERS_FILE"
    else
        rm -f "$SUDOERS_FILE"
        warn "sudoers syntax check failed, removed $SUDOERS_FILE; verify JAVA_BIN ($JAVA_BIN) and CLI_JAR ($CLI_JAR)"
    fi
else
    warn "visudo not found (is sudo installed?), left $SUDOERS_FILE in place unvalidated"
fi

# --- teacher grant command ------------------------------------------------

step "Grant command: install '$GRANT_COMMAND' from grant-pool-access.sh (run as the caller, no sudo)"
# grant-pool-access.sh needs no privilege: it only changes ACLs on dirs the caller already owns.
# Install it as a standalone COPY on PATH, NOT a symlink into $DEPLOY_DIR: that dir is locked to
# root (750, holds secrets), so teachers cannot traverse it to reach a link inside. root-owned,
# group $TEACHERS_GROUP, mode 750: members can run it but not edit it; others cannot run it.
if [ -f "$GRANT_SCRIPT" ]; then
    rm -f "$GRANT_COMMAND"   # clear any stale symlink/file from a previous run
    install -o root -g "$TEACHERS_GROUP" -m 750 "$GRANT_SCRIPT" "$GRANT_COMMAND"
    log "installed $GRANT_COMMAND (copy of $GRANT_SCRIPT; group $TEACHERS_GROUP, run as caller)"
else
    warn "grant script $GRANT_SCRIPT not found; deploy it there, then re-run to install $GRANT_COMMAND"
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

3. Grant repo access with the cs30-grant command, run by whoever OWNS the repos, as themselves
   (no sudo needed to change ACLs on your own dirs):
     PROBLEM_POOL_DIR=... PROBLEM_REPO_DIR=... STUDENTS_DIR=... cs30-grant
   Point each course's studentGitRepo / problemGitRepo (in the DB) at those dirs.

4. Keep the judge internal (bound to 127.0.0.1); its docker-group membership is
   root-equivalent on the host, so it must not be publicly reachable.

5. Teacher access (group '$TEACHERS_GROUP'):
     add a teacher:  sudo usermod -aG $TEACHERS_GROUP <username>   (they must log out/in)
     cs30 CLI:       cs30 <subcommand> ...        as $BACKEND_USER via sudoers (course mgmt, canvas)
     cs30-grant:     PROBLEM_POOL_DIR=... PROBLEM_REPO_DIR=... STUDENTS_DIR=... cs30-grant
                     as the caller, on repos they own (no sudo)
     verify:         sudo -l -U <username>        should list the '$CLI_JAR' rule
   The cs30 wrapper + $SUDOERS_FILE point at $JAVA_BIN and $CLI_JAR; if either lives elsewhere,
   re-run with JAVA_BIN=... CLI_JAR=... so the wrapper and sudoers agree.
   cs30 CLI members can run every subcommand as $BACKEND_USER on any course, so add only trusted
   staff. cs30-grant is unprivileged (caller only touches dirs they own).

6. CI deploy user: '$RUNNER_USER' holds rwX on $DEPLOY_DIR via ACL. If your runner runs as
   another account, re-run with RUNNER_USER=<name>, or the deploy loses write access.
     verify:  sudo -u $RUNNER_USER -- cat $DEPLOY_DIR/application.properties
EOF
