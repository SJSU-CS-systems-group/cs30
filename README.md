# CS30 — Student Coding Lab Editor

A Kotlin Multiplatform + Compose Multiplatform student coding editor for university labs.

Consists of four components:
1. **Backend + CLI** — A single unified jar (`cs30-1.0-SNAPSHOT.jar`) that runs both the Spring Boot server and CLI commands.
2. **Frontend** — Compose Multiplatform UI (desktop JVM + wasmJs). Connects to backend over the university network.
3. **Judge** — Python/FastAPI service in Docker. Compiles and runs student code submissions, returns verdicts.

---

## Requirements

- **JDK 21+** — for backend and frontend
- **A database** — any Spring Data JPA-compatible (PostgreSQL, MySQL, H2, etc.)
- **Google Cloud OAuth 2.0 credentials** — for student login
- **Python 3.9+** (judge only) — to run the judge service
- **Docker** (judge only) — to build and run the sandbox image

---

## Configuration

All configuration lives in a single `application.properties` file at the **repo root**. The backend reads it at runtime; the frontend reads it at Gradle build time; the CLI reads it at startup.

```properties
server.port=8443

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

# Database (any Spring JPA-compatible)
spring.datasource.url=<jdbc-url>
spring.datasource.username=<username>
spring.datasource.password=<password>
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

# Git server — the host holding the student + problem repos. The backend reads
# problem statements and commits student code here over SSH, so it must run on
# this same host (it also writes activity logs via local bash). Use localhost.
git.server.ssh-host=<server-host>
git.server.ssh-user=<server-username>

# Judge — the code-execution service. May run on a SEPARATE host; set its URL
# explicitly (the default localhost:8000 only works if co-located).
judge.url=http://<judge-host>:8000

# Session
server.servlet.session.timeout=1h

# Frontend backend URL (read by desktop app build)
cs30.backend.url=https://cs-reed-01.homeofcode.com:8443

# IP whitelist — comma-separated CIDRs or exact IPs allowed to reach the server.
# Empty = allow all (local dev). Use CIDR for lab subnets: 130.65.254.0/24
# See "IP Whitelisting" section below for how to find the right IP/subnet.
cs30.allowed-ips=

# Docker path for running problemtools
docker.path=/usr/local/bin/docker

# Max custom test cases a student can queue in the editor (read by desktop app build)
editor.max-custom-test-cases=1
```

Get OAuth credentials from [Google Cloud Console → APIs & Credentials](https://console.cloud.google.com/apis/credentials).
Register the value of `google.redirect-uri` as an authorized redirect URI (e.g., `https://cs-reed-01.homeofcode.com:8443/callback` for production or `http://localhost:8080/callback` for local dev).

### Database Backup

The backend automatically backs up the database daily at 2 AM. Backups are compressed with gzip and old backups are automatically cleaned up.

**Configuration** (add to `application.properties`):
```properties
backup.enabled=true                    # Enable/disable automatic backups
backup.directory=/var/backups/cs30-db  # Where backups are stored
backup.retain-days=7                   # Auto-delete backups older than this
backup.cron=0 0 2 * * *                # Schedule (default: 2 AM daily)
```

**Supported databases:** PostgreSQL, MySQL/MariaDB, H2 (file-based), SQLite

**Requirements:** The appropriate CLI tool must be installed on the server:
- PostgreSQL: `pg_dump`
- MySQL/MariaDB: `mysqldump`
- SQLite: `sqlite3`
- H2: just needs `gzip` (copies the database file)

**Backup location:** Backups are named `<dbtype>_<dbname>_<timestamp>.sql.gz` and stored in `backup.directory`.

To disable automatic backups, set `backup.enabled=false`.

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

### 5. Configure SSH access from developer machine (for CLI)

The CLI tool runs on the developer's Mac and uses SSH to upload problems to the server. Add the developer's SSH public key to the server:

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
  browser used (the frontend hits a relative `/login`, not a hardcoded host), so the session
  cookie round-trips correctly. `google.redirect-uri` must therefore match that exact host and
  be registered in the Google console.
- **Size/caching:** the wasm/JS bundles are large because they contain the Compose + Skia
  rendering framework (not your app code) — this is normal for Compose-for-Web. The backend
  gzips them (~3-5x smaller transfer) and serves the content-hashed bundles with a 1-year
  immutable `Cache-Control`, while `index.html` stays `no-cache` so a redeploy loads
  immediately. See `server.compression.*` in Configuration.

For local web testing: `./gradlew :cli:bootRun`, then open `http://localhost:8080/`. Or build the jar and run `java -jar cs30-1.0-SNAPSHOT.jar serve`.

---

## CLI Commands

Instructors use the CLI to manage courses, enroll/remove students, and upload problem definitions. The CLI is part of the unified jar (see "Unified Jar" section above).

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
                                      ├── Problem delivery   (reads statement pool via SSH-to-localhost)
                                      ├── Autosave/Submit    (git commit via SSH-to-localhost)
                                      ├── Activity logging   (CSV write + commit via local bash)
                                      │
                                      └──HTTP──► Judge :8000  (separate host)
                                                  │
                                                  └── Docker sandbox (compile + run)
```

> Because the backend reaches the repos over SSH for some operations and via local
> bash for others, it **must be co-located with the git repos** (set
> `git.server.ssh-host=localhost` + passwordless SSH to self). The judge may live on
> its own host, pointed to by `judge.url`.

### Flow

1. **Login** — Student clicks "Login with Google" → browser redirects to `https://cs-reed-01.homeofcode.com:8443/login` → user approves → Google redirects to `https://cs-reed-01.homeofcode.com:8443/callback` → backend stores session → redirects to ephemeral `localhost:XXXX?...` (app-local callback).
2. **Problems** — Frontend fetches problem list for student's active lab time window. Filters by `startDateTime` and `endDateTime`.
3. **Autosave** — Student writes code → every 60 seconds, autosave sends code to backend → backend writes the file into the student git repo and commits it (over SSH to the co-located repo host). Only creates a git commit if code changed.
4. **Run/Test** — Student clicks "Run" or "Test" → backend sends request to judge → judge compiles and runs in Docker sandbox → results returned to student.
5. **Activity Log** — Every lockdown event (paste, focus loss, etc.) is logged to a CSV file on disk → at end of lab, committed via local bash.

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

1. Create a problem package locally as a flat folder: `my-problem/` (problem definition, testcases, statement source).
2. Use the CLI to add it to the global pool: `addproblem --problem-dir=my-problem --git-repo=<problemGitRepo>`. This converts the statement to HTML and writes `problemGitRepo/my-problem/index.html`.
3. Register it in a lab via the course YAML (`problems: - name: "my-problem"`) so it appears in the database — the backend serves problems from the DB, not by scanning the filesystem.
4. For run/submit to work, also place the problem package (with testcases) in the judge host's `problems_dir/my-problem/`. The problem-folder name must match in all three places (DB, statement pool, judge pool).

### Running Autosave Locally

For testing autosave without a server, the backend and student repos can be on the same Mac:

1. Create local repos:
   ```bash
   mkdir -p ~/cs30/repos/{students,problems}
   cd ~/cs30/repos/students && git init && git commit --allow-empty -m "init"
   ```
2. Set `application.properties`:
   ```properties
   git.server.ssh-host=localhost
   git.server.ssh-user=<your-mac-username>
   ```
3. Backend and frontend both read `application.properties` at the same location
4. No SSH tunnel needed (both on localhost)

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

**OAuth callback still shows localhost after rebuild** — The frontend reads `cs30.backend.url` at **build time** from `application.properties`. After changing it, you must rebuild (`./gradlew :cli:bootJar`), redeploy the jar, and restart the server.
