---
title: Runbook
parent: Deployment
grand_parent: Internal
nav_order: 2
---

# Runbook

Operational commands for production. Run on server 7 (`cs-reed-07`), which is on the campus network only. The apps run as **system** services (`cs30.service`, `kt-judge.service`), so service control uses `sudo systemctl` — not `systemctl --user`.

## Service status and logs

```bash
sudo systemctl status cs30.service        # backend
sudo systemctl status kt-judge.service    # judge

sudo journalctl -u cs30.service -f            # live backend logs
sudo journalctl -u cs30.service -n 100 --no-pager
sudo journalctl -u kt-judge.service -n 100 --no-pager
```

## Start / stop / restart

```bash
sudo systemctl restart cs30.service
sudo systemctl restart kt-judge.service
```

## Which release is live

```bash
readlink -f /opt/cs30/current      # ends in the git SHA of the running release
ls -1t /opt/cs30/releases          # newest first (last 5 kept)
```

The backend's startup log line (`Starting MainKt ...`) also prints the resolved jar path, which shows the release SHA.

## Health checks

```bash
# backend (443, TLS terminated by the app). localhost is not IP-filtered.
curl -k https://127.0.0.1/health          # {"status":"ok"}

# judge (localhost:8000)
curl -fsS http://127.0.0.1:8000/health    # {"status":"ok"} — liveness
curl -fsS http://127.0.0.1:8000/ready     # ready = docker up + sandbox image present
```

`/ready` prints the image name it checked. It should be `ghcr.io/sjsu-cs-systems-group/judge-sandbox:latest`. If it shows the short `judge-sandbox:latest`, the judge isn't reading the deployed config — see [configuration]({% link internal/deployment/configuration.md %}).

## Watch a submission run

Leave this running, then submit from the frontend. The judge starts a throwaway container per submission:

{% raw %}
```bash
docker events --filter type=container --format \
  '{{.Time}}  {{.Action}}  image={{.Actor.Attributes.image}}  name={{.Actor.Attributes.name}}' \
  | grep --line-buffered judge-sandbox
```
{% endraw %}

You'll see `create → start → die → destroy` for a `kt-judge-<uuid>` container using the GHCR image.

## Verify the judge image matches GHCR

{% raw %}
```bash
docker image inspect --format '{{index .RepoDigests 0}}' \
  ghcr.io/sjsu-cs-systems-group/judge-sandbox:latest
```
{% endraw %}

Compare the `@sha256:` digest to the one on the GHCR package page. The deploy pulls the mutable `:latest`, and CI also tags each build `:<sha>`, so the `:<sha>` for the deployed commit should share that digest.

## Manual rollback

```bash
ls -1t /opt/cs30/releases
sudo ln -sfn /opt/cs30/releases/<old-sha> /opt/cs30/current
sudo systemctl restart kt-judge.service
sudo systemctl restart cs30.service
```

Rollback flips the jars only. It does not revert the DB schema (`ddl-auto=update`) or the judge sandbox image — check what changed before rolling back across a schema change.

## The Actions runner

```bash
sudo systemctl status 'actions.runner.*'
```

Also visible in the repo under Settings → Actions → Runners; idle when not deploying.

## Database

```bash
sudo -u postgres psql cs30db
```

### Backups

`DatabaseBackupService` runs a dump on a schedule — 2 AM daily by default, controlled by `backup.enabled`, `backup.directory` (`/var/backups/cs30-db`) and `backup.retain-days` (`7`). Dumps older than the retention window are deleted. It supports PostgreSQL, MySQL/MariaDB, H2 and SQLite; production is PostgreSQL.

On PostgreSQL it shells out to `pg_dump | gzip`, writing `postgres_<database>_<timestamp>.sql.gz`. `pg_dump` must be on the service user's `PATH` or the job fails. The other engines use `mysqldump`, a `SCRIPT TO` dump, and `sqlite3` respectively, with matching filename prefixes.

Restoring is manual. Stop the backend first so nothing writes mid-restore:

```bash
sudo systemctl stop cs30.service

# PostgreSQL
gunzip -c /var/backups/cs30-db/<dump>.sql.gz | sudo -u postgres psql cs30db

# MySQL / MariaDB
gunzip -c <dump>.sql.gz | mysql -u <user> -p <database>

# SQLite
gunzip -c <dump>.sql.gz | sqlite3 <database>.db

# H2 — the dump is the database file. Decompress and replace it in place.

sudo systemctl start cs30.service
```

There is no migration tool. The schema is whatever Hibernate derives from the entities with `ddl-auto=update`, so a dump taken against a newer jar may not load cleanly under an older one. Check the jar version before restoring across a schema change — see [workflow]({% link internal/development/workflow.md %}).

## Inspect the running process environment

When config looks wrong and you want what the process actually has:

```bash
PID=$(systemctl show -p MainPID --value cs30.service)
sudo tr '\0' '\n' < /proc/$PID/environ
```

## Permissions after a deploy

CI copies files with `cp` and never sets permissions; the server owns them (set once by `scripts/setup-service-users.sh`). If a service can't read config or a jar right after a deploy (crash loop with "Permission denied"), it's a server-side ownership/ACL problem, not the workflow. Check:

```bash
getfacl /opt/cs30 /opt/cs30/application.properties
sudo -u cs30backend head -1 /opt/cs30/application.properties   # should be readable
```

## Capacity

The judge grades `judge.concurrency.max-workers` submissions at once and queues up to `judge.concurrency.max-queue-size` (100). Past the queue, requests get 429. Everything else waits its turn, so the wait a student sees is mostly queue depth, not their own code.

Most of a submission's cost is fixed overhead — starting a container and launching the grading tool — not running the student's program. On a 16-core server that puts a ceiling of roughly **2.7 submissions per second** no matter how simple the code is. A full class of 100 submitting at the same moment means the last student waits a few minutes. Measured figures, per problem, are in `loadtest/RESULTS.md` in the repo.

Two things to get right when sizing:

- **Memory.** Keep `max-workers × judge.sandbox.memory-mb` under about 80% of host RAM. `max-workers` defaults to the host CPU count, which on a large host can overcommit memory badly.
- **Interactive problems.** These cost more than other problems and have shown unreliability on a host that has been under load for a long time. See [Interactive problems under concurrency](#interactive-problems-under-concurrency) below.

## Kiosk mode

Restricts the student app to lab kiosk sessions. Off unless `cs30.kiosk-secret` is set. The two labs use different carriers for the same secret: the **Windows** lab runs the web app and presents it once as a URL param, the **Linux** lab runs the desktop app and sends it as a header.

**Provision the lab images before setting the server property.** Reversed, the whole room is locked out.

Server, once:

```bash
openssl rand -hex 32                     # generate the secret
# add CS30_KIOSK_SECRET=<hex> to /home/divyam/cs30/cs30.env (mode 0600)
sudo systemctl restart cs30
curl -fsS https://sjsu.cs30.app/health   # must still return {"status":"ok"}
```

`deploy/cs30.service` already loads that env file, so the unit needs no change.

Windows lab — write the secret to `C:\ProgramData\CS30\kiosk.secret`, restrict it, and launch through it:

```bat
icacls "C:\ProgramData\CS30\kiosk.secret" /inheritance:r ^
  /grant "SYSTEM:(R)" "Administrators:(R)" "kioskuser:(R)"

set /p SECRET=<"C:\ProgramData\CS30\kiosk.secret"
start "" msedge.exe --kiosk --edge-kiosk-type=fullscreen "https://sjsu.cs30.app/?kiosk=%SECRET%"
```

Linux lab — same idea, through the environment instead of a URL:

```sh
sudo chown root:kioskuser /etc/cs30/kiosk.secret && sudo chmod 0440 /etc/cs30/kiosk.secret

CS30_KIOSK_SECRET="$(cat /etc/cs30/kiosk.secret)"   # fails for any other account
export CS30_KIOSK_SECRET
exec /opt/cs30/bin/cs30
```

The file permissions are the whole mechanism — a student's own account gets permission denied and so cannot build the handshake URL or set the header. Disable DevTools in the kiosk browser (`DeveloperToolsAvailability=2`) and use an ephemeral profile, or the cookie is readable from the Application panel and `HttpOnly` buys nothing.

Verify from a *student* account on a lab machine that an ordinary browser shows the "Launch CS30 from the Lab Desktop" page. To disable, clear `CS30_KIOSK_SECRET` and restart.

## Troubleshooting

**Cannot reach the backend.** Check the service is up before suspecting the network:

```bash
systemctl status cs30.service
curl -sS -o /dev/null -w '%{http_code}\n' https://sjsu.cs30.app/health
```

**"Bad Request — This combination of host and port requires TLS."** An `http://` request to the TLS port. Use `https://`.

**Browser SSL errors.** The certificate must be the full chain, including intermediates. Use `fullchain.pem`, not `cert.pem`. Check `server.ssl.certificate` points at the chain file in `/etc/ssl/cs30/`.

**IP filter blocking real users.** The blocked page shows the IP the server actually received. Add that address or its `/24` to `cs30.allowed-ips`. An empty list allows everything — see [configuration]({% link internal/deployment/configuration.md %}).

**"Launch CS30 from the Lab Desktop" page on a machine that should work.** The kiosk gate rejected the request. On the Windows lab the launcher's `?kiosk=` value must match `cs30.kiosk-secret` on the server; on the Linux lab confirm the desktop app inherited `CS30_KIOSK_SECRET`. The cookie is session scoped, so a browser reopened by hand instead of through the launcher is blocked by design. Rejections log `[kiosk] blocked <method> <path> from <ip>` — never the secret. See [kiosk mode](#kiosk-mode).

**OAuth "Invalid redirect URI."** `google.redirect-uri` must match what is registered in Google Cloud exactly, protocol and port included.

**OAuth callback still points at localhost after a config change.** The frontend reads `cs30.backend.url` at **build time**. Changing it requires a rebuild and redeploy, not just a restart.

**Autosave files not appearing.** Usually the lab window does not cover the current time, so the editor is not in a writable lab. Check `scheduled_labs` start and end times for the course.

**`LazyInitializationException` in the backend log.** A JPA lazy relationship — `Course.students` is the usual one — was walked outside a transaction. Confirm `spring.jpa.open-in-view=false`; that makes the failure immediate and consistent instead of appearing only under concurrent load, which is how it first reached production. Then fix the call site to fetch through an explicit repository method such as `existsByIdAndStudentsContaining` rather than walking the entity lazily.

**Judge reports the sandbox image is missing.** `/ready` returns 503 when Docker is down or `judge.image` is absent. In production the image comes from GHCR and is pulled on deploy, so check the image tag in `application.properties` matches what CI pushed — see [Verify the judge image matches GHCR](#verify-the-judge-image-matches-ghcr).

**Interactive problems hang, then fail at the wall timeout.** Silent — nothing in any log says why. See below.

## Interactive problems under concurrency

Interactive problems run the submission and the checker at the same time, exchanging messages. They cost more than other problems and have failed in ways non-interactive problems do not. The cause is **not identified**. What is measured, all on a 16-core host with one interactive problem of 101 testcases:

| conditions | result |
| --- | --- |
| 1 container, idle host | all 101 cases, 16s |
| 16 containers, freshly rebooted host | 16/16 complete all 101 cases, under 30s |
| 16 containers, host that had run load tests for hours | 15/16 complete in ~120s; 1 never finished, still at 0 cases after 300s |
| 16 containers, same host minutes later | 9 of 16 exited early having graded 0–18 cases |

Concurrency alone does not explain it: on a clean host, 16-way is only about 2x slower than a single run and fails zero times. The failures appeared only on a host that had been running load tests for a long time, and a reboot cleared them entirely.

Two things were investigated and ruled out. `fs.pipe-user-pages-soft`, the per-uid pipe memory budget, makes no difference — the same failure occurred identically at the 64 MB default and at 1 GB, and host pipe usage was low in both cases. The `bt` version is not implicated either; the sandbox image was unchanged across every run above.

Where a stalled container was captured, the grading worker was inside `subprocess.Popen.wait()` at `bapctools/interactive.py`, its two child processes were both blocked reading pipes, and the main thread was simply joining workers. That is a symptom, not a cause.

**Operationally:** if interactive grading starts failing, restart the host or at least the judge before diagnosing further, and re-check. Note that `bt` exits non-zero even on fully successful runs, so exit code is not a success signal — count graded cases instead.
