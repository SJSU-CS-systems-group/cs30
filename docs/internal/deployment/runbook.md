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
