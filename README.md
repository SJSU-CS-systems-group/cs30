# CS30 — Student Coding Lab Editor

A Kotlin Multiplatform + Compose Multiplatform student coding editor for university labs.

Consists of four components:
1. **Backend + CLI** — A single unified jar (`cs30-1.0-SNAPSHOT.jar`) that runs both the Spring Boot server and CLI commands.
2. **Frontend** — Compose Multiplatform UI (desktop JVM + wasmJs). Connects to backend over the university network.
3. **Judge** — Python/FastAPI service in Docker. Compiles and runs student code submissions, returns verdicts.

## Contents

- [Requirements](#requirements)
- [Quick Start (Local Dev)](#quick-start-local-dev)
- [Configuration](#configuration)
- [Server Setup (One-Time)](#server-setup-one-time)
- [Unified Jar (Backend + CLI)](#unified-jar-backend--cli)
- [Frontend (Desktop)](#frontend-desktop)
- [Frontend (Web)](#frontend-web)
- [CLI Commands](#cli-commands)
- [Judge (Code Execution Sandbox)](#judge-code-execution-sandbox)
- [How It Works End-to-End](#how-it-works-end-to-end)
- [Authentication & Sessions](#authentication--sessions)
- [Backend API Reference](docs/internal/api.md) — every HTTP endpoint, request/response shapes, auth requirements
- [Project Structure](#project-structure)
- [Development Workflow](#development-workflow) (adding a problem, running tests, autosave/judge locally)
- [IP Whitelisting](#ip-whitelisting)
- [Troubleshooting](#troubleshooting)

---

## Requirements

- **JDK 21+** — for backend and frontend
- **A database** — drivers for PostgreSQL, MySQL/MariaDB, H2 and SQLite are bundled in the jar
- **Google Cloud OAuth 2.0 credentials** — for student login
- **Python 3.9+** (judge only) — to run the judge service
- **Docker** (judge only) — to build and run the sandbox image

---

## Quick Start (Local Dev)

The fastest path to a running app on your machine, for hacking on the code — not a production deploy (see
[Server Setup](#server-setup-one-time) for that). Nothing here needs SSL, a real domain, or a remote git server.

1. **Get local OAuth credentials.** Create a Google OAuth 2.0 Client ID at the
   [Google Cloud Console](https://console.cloud.google.com/apis/credentials), and register
   `http://localhost:8080/callback` as an authorized redirect URI.
2. **Start a local database** (any Spring JPA-compatible DB; PostgreSQL shown):
   ```bash
   sudo -u postgres psql -c "CREATE USER cs30 WITH PASSWORD 'cs30pass'; CREATE DATABASE cs30db OWNER cs30;"
   ```
3. **Create local git repos** for student/problem storage (see [Running Autosave Locally](#running-autosave-locally) for why these are needed even for local dev):
   ```bash
   mkdir -p ~/cs30/repos/{students,problems}
   cd ~/cs30/repos/students && git init && git commit --allow-empty -m "init"
   ```
4. **Write `application.properties`** at the repo root (see [Configuration](#configuration) for the full reference):
   ```properties
   server.port=8080
   google.client-id=<your-client-id>
   google.client-secret=<your-client-secret>
   google.redirect-uri=http://localhost:8080/callback
   spring.datasource.url=jdbc:postgresql://localhost:5432/cs30db
   spring.datasource.username=cs30
   spring.datasource.password=cs30pass
   spring.jpa.hibernate.ddl-auto=update
   cs30.backend.url=http://localhost:8080
   ```
5. **Run the backend** (bundles and serves the web frontend too — same `processResources` wiring as the production jar):
   ```bash
   ./gradlew :backend:bootRun
   ```
6. **Open the app.** Web: `http://localhost:8080`. Desktop: `./gradlew :frontend:run` in a second terminal.
7. **Enroll yourself as a student** — there's no local-disk bypass, so use the CLI against the same database (see [CLI Commands](#cli-commands)):
   ```bash
   ./gradlew :cli:bootRun --args="addcourse --course-file=course.yaml"
   ```
8. *(Optional)* **Run the judge** if you want Run/Test to actually execute code — see [Running the Judge Locally](#running-the-judge-locally).

---

## Configuration

All configuration lives in a single `application.properties` file at the **repo root**. The backend reads it at runtime; the frontend reads it at Gradle build time; the CLI reads it at startup.

```properties
server.port=8443

# Timezone for the frontend to be in (used for lab start/end times). The backend always uses UTC internally.
app.timezone=America/Los_Angeles

# SSL/TLS — point to your certificate and private key files on the server.
# Spring Boot 3.2+ reads PEM files (.crt/.key or .pem) directly — no keystore conversion needed.
server.ssl.enabled=true
server.ssl.certificate=file:/path/to/server.crt
server.ssl.certificate-private-key=file:/path/to/server.key

# Response compression — gzip the large wasm/JS web-app bundles (~3-5x smaller transfer;
# browsers decompress automatically). The mime-types list MUST include application/wasm
# (it is NOT in Spring's defaults), or the biggest file (the Skia wasm) ships uncompressed.
server.compression.enabled=true
server.compression.mime-types=text/html,text/css,text/plain,application/javascript,text/javascript,application/json,application/wasm,image/svg+xml
server.compression.min-response-size=1024

# Google OAuth
google.client-id=<your-client-id>
google.client-secret=<your-client-secret>
google.redirect-uri=https://cs-reed-01.homeofcode.com:8443/callback
# Optional — separate redirect URI for the TA login flow (/ta/login, /ta/callback).
# Defaults to google.redirect-uri with "/callback" swapped for "/ta/callback", so you
# only need this if the TA flow should redirect somewhere else entirely.
# google.ta-redirect-uri=https://cs-reed-01.homeofcode.com:8443/ta/callback

# Database (any Spring JPA-compatible)
spring.datasource.url=<jdbc-url>
spring.datasource.username=<username>
spring.datasource.password=<password>
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
# Keep false. Spring's default (true) keeps a Hibernate session open for the whole
# request, which papers over missing eager-fetches until the app is under real
# concurrent load — that's what caused a production LazyInitializationException
# (see Troubleshooting below). With this off, any code needing a lazy association
# must fetch it explicitly inside a transactional repository method.
spring.jpa.open-in-view=false

# Database Backup (default: 2 AM daily) — all optional, shown here at their defaults
# Supports PostgreSQL, MySQL/MariaDB, H2 (file-based), SQLite
backup.enabled=true
backup.directory=/var/backups/cs30-db
backup.retain-days=7
backup.cron=0 0 2 * * *

# Git commit author identity for the backend's own auto-commits (student submissions,
# activity logs). Optional — shown here at their defaults; doesn't need to be a real,
# deliverable address. Repos themselves are plain local paths set per-course (see
# course.yaml's studentGitRepo/problemGitRepo under CLI Commands), not a remote git host.
git.server.email=server@cs30.edu
git.server.name=CS30 Server

# Judge — the code-execution service. May run on a SEPARATE host; set its URL
# explicitly (the default localhost:8000 only works if co-located).
judge.url=http://<judge-host>:8000

# Session — governs only the short-lived pre-login OAuth round-trip (the HttpSession that
# holds pending_app_callback/pending_state while Google redirects back). It does NOT govern
# how long a student stays logged in — that's the fixed 2-minute heartbeat TTL in
# ApiTokenStore (see "Authentication & Sessions" below), which isn't a configurable property.
server.servlet.session.timeout=1h

# Frontend backend URL (read by desktop app build)
cs30.backend.url=https://cs-reed-01.homeofcode.com:8443

# IP whitelist — comma-separated CIDRs or exact IPs allowed to reach the server.
# Empty = allow all (local dev). Use CIDR for lab subnets: 130.65.254.0/24
# See "IP Whitelisting" section below for how to find the right IP/subnet.
cs30.allowed-ips=

# Docker path for running problemtools
docker.path=/usr/local/bin/docker

# bapctools binary, used by addproblem/addproblems to upgrade a package for the judge.
# See "Problem package formats" below.
bt.path=/usr/local/bin/bt

# Max custom test cases a student can queue in the editor (read by desktop app build)
editor.max-custom-test-cases=1
```

Get OAuth credentials from [Google Cloud Console → APIs & Credentials](https://console.cloud.google.com/apis/credentials).
Register the value of `google.redirect-uri` as an authorized redirect URI (e.g., `https://cs-reed-01.homeofcode.com:8443/callback` for production or `http://localhost:8080/callback` for local dev).

### Database Backup

The backend automatically backs up the database daily at 2 AM. Backups are compressed with gzip and old backups are automatically cleaned up.

**Supported databases:** PostgreSQL, MySQL/MariaDB, H2 (file-based), SQLite

**Requirements:** The appropriate CLI tool must be installed on the server:
- PostgreSQL: `pg_dump`
- MySQL/MariaDB: `mysqldump`
- SQLite: `sqlite3`
- H2: just needs `gzip` (copies the database file)

**Backup location:** Backups are named `<dbtype>_<dbname>_<timestamp>.sql.gz` and stored in `backup.directory`.

To disable automatic backups, set `backup.enabled=false`.

**Restoring from backup:**

```bash
# PostgreSQL
gunzip -c /path/to/postgres_dbname_timestamp.sql.gz | psql -h host -p 5432 -U username dbname

# MySQL/MariaDB
gunzip -c /path/to/mysql_dbname_timestamp.sql.gz | mysql -h host -P 3306 -u username -p dbname

# SQLite
gunzip -c /path/to/sqlite_dbname_timestamp.sql.gz | sqlite3 /path/to/database.db

# H2 (raw file backup — decompress and replace)
gunzip -c /path/to/h2_dbname_timestamp.db.gz > /path/to/database.mv.db
```

For PostgreSQL/MySQL, you may need to drop and recreate the database first if restoring to an existing database.

### Optional: SSL/TLS

If you have a certificate (e.g. from Let's Encrypt or your institution), you can enable HTTPS by adding these lines and changing the port and URLs:

```properties
server.ssl.enabled=true
server.ssl.certificate=file:/path/to/server.crt
server.ssl.certificate-private-key=file:/path/to/server.key

google.redirect-uri=https://cs-reed-01.homeofcode.com:8443/callback
cs30.backend.url=https://cs-reed-01.homeofcode.com:8443
```

Open the firewall port and register the new redirect URI in Google Cloud Console:

```bash
sudo ufw allow 8443
sudo ufw allow 'OpenSSH'
```

Spring Boot 3.2+ reads PEM files (`.crt`/`.key`) directly — no keystore conversion needed. With Let's Encrypt, use `fullchain.pem` (not `cert.pem`) as the certificate so the full chain is included.

---

## Server Setup (One-Time)

Follow the manual steps below:

### 1. Install Java (JDK 21+)

```bash
# Ubuntu/Debian
sudo apt install -y openjdk-21-jre-headless
java -version  # verify installation
```

### 2. Install Git

```bash
sudo apt install -y git
git config --global user.email "server@cs30.edu"
git config --global user.name "CS30 Server"
```

### 3. Set up the database

Create the database and user. Example for PostgreSQL (adapt for your choice):

```bash
sudo -u postgres psql
CREATE USER cs30 WITH PASSWORD 'cs30pass';
CREATE DATABASE cs30db OWNER cs30;
\q
```

The Spring Boot backend automatically creates tables via `spring.jpa.hibernate.ddl-auto=update`.

### 4. Create the git repo directories

```bash
mkdir -p ~/cs30/repos/students
cd ~/cs30/repos/students && git init && git commit --allow-empty -m "init"

mkdir -p ~/cs30/repos/problems
# Problems use a FLAT global pool keyed by problem name:
#   problems/<problem-name>/index.html  (+ optional problem.css)
# The CLI (addproblem/addproblems) populates this; do NOT nest by section/lab.
```

### 5. Configure SSH access from developer machine (for deployment & CLI)

The CLI is bundled into the same jar as the backend (see [Unified Jar](#unified-jar-backend--cli)) and reads/writes the git repos as plain local paths on whatever host it runs on — it no longer uploads anything over SSH itself. In practice that means running CLI commands (`addcourse`, `addproblem`, etc.) directly on the server, so you still need normal SSH access to deploy the jar and invoke it there remotely. Add the developer's SSH public key to the server:

**On your Mac:**
```bash
cat ~/.ssh/id_rsa.pub  # or id_ed25519.pub
```

**On the server:**
```bash
echo "<paste-your-public-key>" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

Verify from your Mac:
```bash
ssh <user>@<server> echo "OK"
```

### 6. Set up Google OAuth

1. Go to [Google Cloud Console → APIs & Credentials](https://console.cloud.google.com/apis/credentials)
2. Create a new OAuth 2.0 Client ID (Web application type)
3. Add the value of `google.redirect-uri` to the authorized redirect URIs (e.g., `https://cs-reed-01.homeofcode.com:8443/callback`)
4. Copy the **Client ID** and **Client Secret** into `application.properties` (see Configuration section)

### 7. Set up SSL/TLS

Place your certificate and private key on the server (e.g. from your CA or Let's Encrypt):

```bash
# Let's Encrypt example — certbot puts files here automatically:
# /etc/lego/certificates/_.cs30.app.crt  (certificate)
# /etc/lego/certificates/_.cs30.app.key  (private key)

# Or copy your own .crt/.key files:
mkdir -p ~/cs30/ssl
scp server.crt server.key <user>@<server>:~/cs30/ssl/
```

Update `application.properties` to point to the files:
```properties
server.port=8443
server.ssl.enabled=true
server.ssl.certificate=file:/home/<user>/cs30/ssl/server.crt
server.ssl.certificate-private-key=file:/home/<user>/cs30/ssl/server.key
```

Open port 8443 in the firewall:
```bash
sudo ufw allow 8443
sudo ufw allow 'OpenSSH'
```

### 8. Copy configuration to server

```bash
scp application.properties <user>@<server>:~/cs30/
```

---

## Unified Jar (Backend + CLI)

The backend server and CLI are bundled into a single jar. Use `serve` to start the web server, or run CLI commands directly.

**Why the module is called `:cli`:** it started as exactly that — a picocli tool wrapping `:backend` so instructors
could run `addcourse`/`addstudent`/etc. When `serve` (which launches the full backend + bundled web app) was added,
it went in as just one more subcommand of the same module, rather than standing up a separate composition-root
module — so `:cli` is now the module that produces the actual deployable product, of which instructor commands are
one part. The name stuck; if you're looking for "where does the whole app get assembled," this is it, not just
"where the instructor commands live."

### Build

```bash
./gradlew :cli:bootJar
# Output: cli/build/libs/cs30-1.0-SNAPSHOT.jar
```

This also builds the production wasmJs web app and bundles it into the jar (served at `/`),
so the jar is self-contained — no separate frontend deploy. (Because it builds the web app,
the first `bootJar` takes a few minutes.)

### Deploy

```bash
scp cli/build/libs/cs30-1.0-SNAPSHOT.jar <user>@<server>:~/cs30/cs30-1.0-SNAPSHOT.jar
```

### Run Server

On the server (foreground):
```bash
cd ~/cs30
java -jar cs30-1.0-SNAPSHOT.jar serve --config=./application.properties
```

Background (survives SSH disconnect):
```bash
cd ~/cs30
nohup java -jar cs30-1.0-SNAPSHOT.jar serve --config=./application.properties \
  > backend.log 2>&1 & echo $! > backend.pid
tail -f backend.log   # watch startup
```

Stop:
```bash
kill $(cat ~/cs30/backend.pid)
```

The server listens on the port set in `application.properties` (`:8443` with SSL, `:8080` without).

### Run CLI Commands

```bash
java -jar cs30-1.0-SNAPSHOT.jar <command> [options]
java -jar cs30-1.0-SNAPSHOT.jar --help   # show available commands
```

CLI commands run without starting a web server. Database credentials come from the bundled `application.properties` or can be overridden:

```bash
java -jar cs30-1.0-SNAPSHOT.jar --db-url=jdbc:postgresql://... --db-user=user --db-pass=pass addcourse --course-file=course.yaml
```

### Redeploy (after a code change)

```bash
# 1. Build on your Mac
./gradlew :cli:bootJar

# 2. Copy jar to server
scp cli/build/libs/cs30-1.0-SNAPSHOT.jar <user>@<server>:~/cs30/cs30-1.0-SNAPSHOT.jar

# 3. Restart on server
ssh <user>@<server> "kill \$(cat ~/cs30/backend.pid); cd ~/cs30 && nohup java -jar cs30-1.0-SNAPSHOT.jar serve --config=./application.properties > backend.log 2>&1 & echo \$! > backend.pid"
```

---

## Frontend (Desktop)

### Development (Local Testing)

For local testing, use `localhost`:

```bash
# Update application.properties for local dev:
# cs30.backend.url=http://localhost:8080
# google.redirect-uri=http://localhost:8080/callback

./gradlew :frontend:run
```

The desktop window opens and reads `cs30.backend.url` from `application.properties` at build time.

### Production (Native Distribution)

Build native installers with the production backend URL baked in. Students install and run with no Java, Gradle, or SSH tunnel needed. The JRE is bundled in the package.

**1. Update `application.properties` with production URLs:**
```properties
cs30.backend.url=https://cs-reed-01.homeofcode.com:8443
google.redirect-uri=https://cs-reed-01.homeofcode.com:8443/callback
```

**2. Build installers:**
```bash
./gradlew :frontend:packageDmg    # macOS → frontend/build/compose/binaries/main/dmg/cs30-1.0.0.dmg
./gradlew :frontend:packageMsi    # Windows → .msi
./gradlew :frontend:packageDeb    # Linux → .deb
```

**3. Distribute** the installer file to students.

---

## Frontend (Web)

The browser version is the same Compose UI compiled to **wasmJs**. It is built and bundled
into the unified jar automatically (see Unified Jar → Build) and served by the server at the site
root, so students just open the site — no install.

- **Build/deploy:** nothing separate — `:cli:bootJar` builds and bundles it; deploy the jar.
- **Served at:** `https://<host>/`. The OAuth login runs **same-origin** on whatever host the
  browser used (the frontend hits a relative `/login`, not a hardcoded host), so the redirect
  back from Google lands on the same host that issued it and the Bearer token in the redirect
  URL is picked up correctly (see [Authentication & Sessions](#authentication--sessions)).
  `google.redirect-uri` must therefore match that exact host and be registered in the Google
  console.
- **Size:** the wasm/JS bundles are large because they contain the Compose + Skia rendering
  framework (not your app code) — this is normal for Compose-for-Web. The backend gzips them
  (~3-5x smaller transfer). See `server.compression.*` in Configuration.
- **Caching:** the server sends no `Cache-Control` header on any static asset — deliberately
  removed rather than fixed. An earlier version cached `composeApp.js`/`.wasm` as a 1-year
  immutable asset on the (incorrect) assumption that the filename was content-hashed; it isn't
  (`outputFileName = "composeApp.js"` is hardcoded in `frontend/build.gradle.kts`), so a
  redeploy silently kept serving the old bundle to anyone who'd loaded the app before, with no
  way to force a refresh short of a manual cache clear. Fixing this properly means giving the
  build's output filenames a real content hash; until that's done, no caching beats wrong
  caching.

For local web testing: `./gradlew :cli:bootRun`, then open `http://localhost:8080/`. Or build the jar and run `java -jar cs30-1.0-SNAPSHOT.jar serve`.

---

## CLI Commands

Instructors use the CLI to manage courses, enroll/remove students, and upload problem definitions. The CLI is part of the unified jar (see [Unified Jar](#unified-jar-backend--cli) above for why the module producing it is named `:cli` even though it also builds the server and web app).

### Run

```bash
java -jar cs30-1.0-SNAPSHOT.jar <subcommand> [options]
java -jar cs30-1.0-SNAPSHOT.jar --help   # show available commands
```

The CLI reads the bundled `application.properties` or accepts database credentials via flags (`--db-url`, `--db-user`, `--db-pass`).

### Subcommands

| Command                 | Required flags                                                                                | Purpose                                                                             |
|-------------------------|-----------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| `addcourse`             | `--course-file <yaml>`                                                                        | Create/update course(s) from a YAML file; initializes git repos                     |
| `addstudent`            | `--course-code`, `--year`, `--semester`, `--section`, `--email`                               | Enroll a student in a course section                                                |
| `removestudent`         | `--course-code`, `--year`, `--semester`, `--section`, `--email`                               | Unenroll a student                                                                  |
| `removecourse`          | `--course-code`, `--year`, `--semester`, `--section` (or `all`)                               | Delete a course (only after end date)                                               |
| `changeenddate`         | `--course-code`, `--year`, `--semester`, `--section`, `--end-date`                            | Extend or modify a course end date                                                  |
| `findcourse`            | `--course-code`, `--year`, `--semester`, `--section` (or `all`)                               | Print course details and enrolled students                                          |
| `findstudent`           | `--email`                                                                                     | Find all courses containing a student                                               |
| `setta`                 | `--course-code`, `--year`, `--semester`, `--section`, `--email`                               | Set or update the TA email for a course section                                     |
| `removeta`              | `--course-code`, `--year`, `--semester`, `--section`                                          | Remove the TA from a course section                                                 |
| `addproblem`            | `--problem-dir`, `--git-repo`                                                                 | Convert one problem to HTML and add it to the global problem pool                   |
| `addproblems`           | `--problems-dir`, `--git-repo`                                                                | Bulk-add every problem from a directory (`problems_dir/<name>/`) to the global pool |
| `removeproblem`         | `--problem-name`, `--git-repo`                                                                | Remove a problem from the global problem pool                                       |
| `updateproblemlanguage` | `--course-code`, `--year`, `--semester`, `--section`, `--lab`, `--problem-name`, `--language` | Update a problem's language in the database                                         |
| `cancellab`             | `--course-code`, `--year`, `--semester`, `--section`, `--lab`                                 | Cancel a lab and delete its problems from the database                              |
| `validatecourse`        | `--course-code`, `--year`, `--semester`, `--section`                                          | Validate that all course problems exist in the git repo                             |

### Example: Create a course

```bash
java -jar cs30-1.0-SNAPSHOT.jar addcourse --course-file=course.yaml
```

**course.yaml** (see `templates/courseTemplate.yml` for the canonical schema):
```yaml
code: CS30
year: 2026
semester: Spring
startDate: "2026-01-01"
endDate: "2026-05-31"
studentGitRepo: /home/joshini/cs30/repos/students
problemGitRepo: /home/joshini/cs30/repos/problems
language: kotlin
sections:
  - number: 1
    ta: ta.section@sjsu.edu
    labs:
      - number: 1
        startDateTime: "2026-01-10T09:00:00"
        endDateTime: "2026-01-10T10:15:00"
        problems:
          - name: "babyshark"
          - name: "tenkindsofpeople"
            language: Python   # optional per-problem override
    students:
      - joshini.naagraj@sjsu.edu
```

---

## Judge (Code Execution Sandbox)

The judge is an internal HTTP service that compiles and runs student code in a sandboxed Docker container. It is called by the backend and is not directly exposed to students.

### Build the Sandbox Image

```bash
docker build -t judge-sandbox:latest ./judge
```

This uses `bapctools` internally to compile and run code safely.

### Install Python Dependencies

```bash
pip install -r judge/requirements.txt
```

### Run the Service

```bash
uvicorn judge.service:app --host 127.0.0.1 --port 8000
```

The judge reads config from `judge/config.yaml`. Override the path with the `JUDGE_CONFIG` environment variable.

### HTTP Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/submit` | Grade a submission against all testcases (sample + secret). Returns verdict and per-case breakdown. |
| `POST` | `/run` | Run sample testcases + optional custom input. For student self-testing. |
| `GET` | `/health` | Liveness check — returns `{"status":"ok"}` |
| `GET` | `/docs` | OpenAPI UI |

**Verdicts:** `AC` (accepted), `WA` (wrong answer), `TLE` (time limit exceeded), `RTE` (runtime error), `MLE` (memory limit exceeded), `CE` (compile error).

### CLI Mode (Local Testing)

Test code without the HTTP server:

```bash
# Judge against all testcases
python -m judge all <problem_dir> <code_file> [--wall-timeout 60]

# Run on custom input
python -m judge custom <problem_dir> <code_file> --input-file input.txt [--ans-file expected.txt]
```

### Configuration (judge/config.yaml)

```yaml
image: judge-sandbox:latest          # Docker image
problems_dir: /path/to/problems_pool # Flat problem-package root: problems_dir/<name>/
concurrency:
  max_workers: <cpu-count>           # Parallel submissions
sandbox:
  memory_mb: 2560                     # Memory limit per run
  cpus: 1.0                           # CPU cores per run
languages:                           # language -> source extension (map, not a list)
  c: .c
  cpp: .cpp
  java: .java
  python: .py
```

---

## How It Works End-to-End

```
Student Mac (with Native App)        Server cs-reed-01 (backend + Postgres + git repos)
──────────────────────────────────────────────────────────────────────────
  Desktop App  ───────HTTPS───────► Spring Boot :8443
                                      │
                                      ├── OAuth callback to app_callback (localhost ephemeral port)
                                      ├── Problem delivery   (reads statement pool via local bash)
                                      ├── Autosave/Submit    (git commit via local bash)
                                      ├── Activity logging   (CSV write + commit via local bash)
                                      │
                                      └──HTTP──► Judge :8000  (separate host)
                                                  │
                                                  └── Docker sandbox (compile + run)
```

> The backend reaches the git repos as plain local filesystem paths (`ProcessBuilder`
> shelling out to `git`/`bash`), so it **must be co-located with the git repos** —
> there's no remote/SSH option for this. The judge may live on its own host, pointed
> to by `judge.url`.

### Flow

1. **Login** — Student clicks "Login with Google" → browser redirects to `https://cs-reed-01.homeofcode.com:8443/login` → user approves → Google redirects to `https://cs-reed-01.homeofcode.com:8443/callback` → backend verifies identity and issues a Bearer token (see [Authentication & Sessions](#authentication--sessions)) → redirects to ephemeral `localhost:XXXX?...` (app-local callback).
2. **Problems** — Frontend fetches problem list for student's active lab time window. Filters by `startDateTime` and `endDateTime`.
3. **Autosave** — Student writes code → every 60 seconds, autosave sends code to backend → backend writes the file into the student git repo and commits it (local filesystem, co-located repo host). Only creates a git commit if code changed.
4. **Run/Test** — Student clicks "Run" or "Test" → backend sends request to judge → judge compiles and runs in Docker sandbox → results returned to student.
5. **Activity Log** — Every lockdown event (paste, focus loss, etc.) is logged to a CSV file on disk → at end of lab, committed via local bash.

---

## Authentication & Sessions

Both desktop and web authenticate the same way: a Bearer token (`Authorization: Bearer <token>`),
issued by `ApiTokenStore.generate()` immediately after `OAuthController.callback()` confirms identity
with Google. This wasn't always true — web used to run on a JSESSIONID session cookie instead, which
meant two separate identity mechanisms to keep in sync. Desktop already needed a token (it has no
browser session/cookie jar of its own), so web was migrated onto the same mechanism rather than
maintaining both.

**Session length isn't configurable.** A token stays valid for a fixed 2-minute TTL, measured from
`lastHeartbeatAt` and refreshed every time a 60-second heartbeat (`POST /api/check-session`) arrives
while the app is open. This TTL answers "is this client still connected," not "is the student still
active at the keyboard" — the heartbeat fires on a fixed interval regardless of mouse/keyboard activity,
so leaving the tab open and idle does not log anyone out. Only something that actually stops the
heartbeat (closing the tab, a crash, losing network) lets the TTL lapse. This is unrelated to
`server.servlet.session.timeout` in `application.properties`, which only covers the brief pre-login
OAuth round-trip, not how long a student stays logged in.

**One active session per student.** `OAuthController.callback()` rejects a new login
(`error=session_exists`) while the student's existing token is still within its TTL and hasn't been
logged out — a student cannot hold two valid sessions at once. There are exactly two legitimate ways
to get a new one: log out explicitly, or wait for the existing session's TTL to expire (e.g. after a
device crash mid-lab). Anything else attempting to bypass this is what the check exists to catch.

**No endpoint trusts identity claimed by the client.** Every authenticated route resolves the caller's
email server-side from the Bearer token via `StudentIdentityService`, and uses *that* value for every
enrollment check, file path, and git write — never a `studentEmail` field or path segment the request
happens to include. This matters specifically because the source is open: a student who knows the
exact request shape could otherwise submit code, read submissions, or query lab schedules as any other
enrolled student just by changing a field in a raw HTTP request.

**`login_sessions` is the actual session store, not just a monitoring log.** `ApiTokenStore` holds no
state of its own — it's a thin wrapper over this table. The token issued at login is the table's primary
key, and every login inserts a brand-new row rather than overwriting one, so the table is a full
login/logout history per student, not a snapshot of "current device." A row's `loggedOutAt` is null while
the session is active and gets set the moment it ends; the one-active-session check above is answered by
querying for exactly that. Every authenticated request also cross-checks its token's IP against the row
recorded for that token; a mismatch is logged as a warning, not blocked — a monitoring signal for
reviewing who used which seat, not a login gate.

**Logout runs a hook before it actually happens, and that hook can block it.** Ending a session — either
explicit logout or TTL expiry — publishes an internal event synchronously, before `loggedOutAt` is
written. The one listener today records a `LoggedOut` lockdown event and commits that day's activity log
to git. Unlike most git writes in this app (deliberately best-effort, so a hiccup never locks a student
out), this one is allowed to fail loudly: if the commit fails, the session is left active rather than
silently ending. This trades "logout always succeeds" for "logout is never recorded without also being
provable in the activity log."

---

## Project Structure

```
cs30/
├── application.properties                    # All config (backend, frontend, CLI)
├── data/src/commonMain/kotlin/data/
│   └── *.kt                                 # Shared models (Student, Course, LabProblemInfo…)
├── backend/src/main/                        # package com.cs30.server.*
│   ├── controller/                          # HTTP route handlers (auth, problems, autosave, activity, code)
│   ├── service/                             # GitService (SSH + bash), ProblemService, JudgeService, ActivityLogService…
│   ├── repository/                          # Spring Data JPA repos
│   ├── models/                              # JPA entities (Course, ScheduledLab, Problem) + server models
│   ├── dto/                                 # Request/response DTOs (CodeDtos…)
│   └── config/, app/                        # WebConfig + Application entrypoint
├── frontend/src/
│   ├── commonMain/kotlin/                   # Shared Compose UI (editor, problems, lockdown…)
│   ├── desktopMain/kotlin/                  # JVM platform impls (AuthService, HtmlRenderer…)
│   └── wasmJsMain/kotlin/                   # Browser platform impls
├── cli/
│   └── src/main/                            # Unified jar entry point + picocli commands (serve, addcourse, etc.)
├── judge/
│   ├── service.py                           # FastAPI app
│   ├── sandbox/                             # Docker setup
│   ├── config.yaml                          # Judge config
│   ├── requirements.txt                     # Python deps
│   └── README.md                            # Judge-specific docs
└── gradle/                                  # Gradle wrapper + build scripts
```

---

## Development Workflow

### Adding a Problem

1. Create a problem package locally as a flat folder: `my-problem/` (problem definition, testcases, statement source). Keep it in the **legacy** package format (see [Problem package formats](#problem-package-formats)).
2. Use the CLI to add it to the global pool: `addproblem --problem-dir=my-problem --git-repo=<problemGitRepo>`. This converts the statement to HTML, writes `problemGitRepo/my-problem/index.html`, and upgrades the pool copy to the format the judge needs.
3. Register it in a lab via the course YAML (`problems: - name: "my-problem"`) so it appears in the database. The backend serves problems from the DB, not by scanning the filesystem.
4. For run/submit to work, also place the problem package (with testcases) in the judge host's `problems_dir/my-problem/`. The problem-folder name must match in all three places (DB, statement pool, judge pool).

### Problem package formats

Two tools read problem packages, and they support different versions of the ICPC package format:

| Tool | Used for | Supports |
| --- | --- | --- |
| problemtools (`problem2html`) | converting the statement to HTML | `legacy`, `2023-07-draft` |
| bapctools (`bt`) | grading in the judge | `2025-09` only |

There is no format both accept, so `addproblem` / `addproblems` handles it in this order:

1. Read the time limit from the source package's `problem_statement/timelimit.txt`.
2. Run `problem2html` on the source package, which is legacy, to produce `index.html` and `problem.css`.
3. Copy the package into the problem pool.
4. Run `bt upgrade` on the **pool copy** so the judge can grade it.
5. Write the time limit from step 1 into the pool copy's `problem.yaml` as `limits.time_limit`.

**Keep your source packages in legacy format, and keep an archive copy.** Ingest **moves** the package directory into the pool, so the directory you point `addproblem` at is gone afterwards. Hand it a copy and keep the legacy originals somewhere separate, because they are the only record of the legacy time limit and the only thing `problem2html` can convert later.

Steps 4 and 5 are skipped if `problem.yaml` already declares a `problem_format_version`, so re-adding a problem does not upgrade it twice.

**Why step 5 exists.** `bt upgrade` moves `problem_statement/` to `statement/` but does not carry the time limit over, and `bt` does not read `timelimit.txt`. Without step 5 every problem silently falls back to `bt`'s default of 1 second, so any problem whose reference solution takes longer is graded TLE. Verify a package's effective limit with:

```bash
grep -A2 '^limits:' <pool>/<problem>/problem.yaml   # expect time_limit: <seconds>
```

`bt upgrade` also does not rename `statement/problem.tex` to the language-tagged `statement/problem.en.tex` the 2025-09 spec expects. Grading never reads the statement, so cs30 does not do this rename. It only matters for `bt zip`, `bt export`, and PDF building, which cs30 does not use.

**Requirements.** `addproblem` needs Docker (it pulls `problemtools/full:latest`) and `bt` on the host.

**Required `bt` version: any release that targets the `2025-09` spec**, which is every current one (`2026.3.0` and later). All of them reject legacy packages, so the `bt upgrade` step during ingest is not optional. Do not try to downgrade `bt` to regain legacy support: no release on PyPI has it.

The host `bt` and the sandbox `bt` (`BT_VERSION` in `kt-judge/sandbox/Dockerfile`) do **not** have to be the same version for ingest. `bt upgrade` only rewrites metadata and layout, so its output is a plain `2025-09` package any 2025-09 version can grade. Verified: a package upgraded by host `2026.7.0` grades correctly under sandbox `2026.4.0`.

Versions **do** need to match when you regenerate testcase data with `bt generate`, because data written by one version and graded by another is a known cause of every submission scoring `0/0`. Keeping them aligned is the safer habit.

```bash
sudo python3 -m venv /opt/cs30/btenv
sudo /opt/cs30/btenv/bin/pip install bapctools
sudo ln -sf /opt/cs30/btenv/bin/bt /usr/local/bin/bt
```

Point `bt.path` at it if it is not on `PATH` (`sudo` often has a different `PATH`). `bt` has no `--version` flag, so check the installed version with `pip show`:

```bash
/opt/cs30/btenv/bin/pip show bapctools | grep Version
grep BT_VERSION kt-judge/sandbox/Dockerfile    # the sandbox's copy, for comparison
```

### Running Autosave Locally

Git access is always local filesystem paths (no SSH/remote option), so there's nothing special to configure beyond having the repos exist and pointing a course at them:

1. Create local repos:
   ```bash
   mkdir -p ~/cs30/repos/{students,problems}
   cd ~/cs30/repos/students && git init && git commit --allow-empty -m "init"
   ```
2. Point `studentGitRepo`/`problemGitRepo` in your course YAML at those paths (see [CLI Commands](#cli-commands)) and run `addcourse`.
3. Autosave/Submit now commit straight to those repos — no extra `application.properties` config needed for this.

### Running the Judge Locally

```bash
docker build -t judge-sandbox:latest ./judge
pip install -r judge/requirements.txt
uvicorn judge.service:app --host 127.0.0.1 --port 8000
```

Test a submission manually:

```bash
python -m judge all /path/to/problem /path/to/solution.kt
```

### Running Tests

Test infrastructure differs by module: `:frontend`/`:data` are Kotlin Multiplatform (pure-function tests only, no
mocking library — `kotlin.test`), while `:backend`/`:cli` are plain JVM/Spring (JUnit 5 + MockK). See the
`cs30-unit-testing` skill for the full breakdown.

```bash
./gradlew :frontend:desktopTest   # frontend + :data (KMP commonTest, compiled for the desktop target)
./gradlew :backend:test           # backend (JUnit 5)
./gradlew :cli:test               # CLI (JUnit 5)
./gradlew test                    # everything
```

Reports land at `<module>/build/reports/tests/<taskName>/index.html`.

---

## IP Whitelisting

The `cs30.allowed-ips` property restricts access to specific IPs or subnets. Leave it empty to allow all connections (local dev). In production, set it to the lab network subnet so only students on authorized networks can reach the app.

**Format:** comma-separated exact IPs or CIDR ranges:
```properties
# Entire lab subnet
cs30.allowed-ips=130.65.254.0/24

# Multiple ranges + specific IPs
cs30.allowed-ips=130.65.254.0/24,10.0.0.0/8,203.0.113.42
```

Blocked users see a styled HTML page with their IP shown, telling them to connect to the lab network.

**Finding the right IP/subnet to allow:**

Temporarily block all traffic, then open the app — the blocked page shows your exact IP as the server sees it:
```properties
cs30.allowed-ips=1.2.3.4   # a dummy IP that won't match
```
Restart the backend, open the site, read "Your IP: x.x.x.x" from the block page, then set that IP or its `/24` subnet.

**To update the whitelist:** edit `application.properties` on the server and restart the backend. No nginx changes needed — all filtering happens in Spring Boot.

---

## Troubleshooting

**"Cannot reach backend"** — Backend is not running on the server, or network is unreachable. Verify with:
```bash
curl https://cs-reed-01.homeofcode.com:8443/api/problems/lab
```

**"Bad Request — This combination of host and port requires TLS"** — You're using `http://` on an SSL-enabled port. Use `https://` instead.

**SSL certificate errors in browser** — Ensure the `.crt` file is the full chain (includes intermediate certs), not just the leaf cert. With Let's Encrypt use `fullchain.pem`, not `cert.pem`.

**IP filter blocking legitimate users** — Use the blocked page's "Your IP" display to see exactly what IP the server receives, then add that IP or its `/24` subnet to `cs30.allowed-ips`.

**OAuth callback shows "Invalid redirect URI"** — Ensure the value of `google.redirect-uri` in `application.properties` is registered in Google Cloud Console. The callback URL must match exactly (including protocol and port).

**Autosave files not appearing** — Check that lab times in the database cover the current time. Update with:
```sql
UPDATE scheduled_labs SET start_date_time = NOW() - INTERVAL '1 hour', end_date_time = NOW() + INTERVAL '24 hours';
```

**Judge returns "Image not found"** — Run `docker build -t judge-sandbox:latest ./judge` on the server first.

**`LazyInitializationException` in the backend log** — A JPA lazy relationship (e.g. `Course.students`) was accessed outside a transaction. Confirm `spring.jpa.open-in-view=false` is set (see Configuration) — this makes the failure happen immediately and consistently instead of only under concurrent load — then fix the actual call site to fetch that relationship through an explicit repository method (e.g. `existsByIdAndStudentsContaining`) rather than lazily walking the entity.

**OAuth callback still shows localhost after rebuild** — The frontend reads `cs30.backend.url` at **build time** from `application.properties`. After changing it, you must rebuild (`./gradlew :cli:bootJar`), redeploy the jar, and restart the server.
