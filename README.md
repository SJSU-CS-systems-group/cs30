# CS30 — Student Coding Lab Editor

A proctored coding-lab platform for university CS courses. Students sign in with Google, open a lab problem
in an editor, run their code against sample tests, and submit for automatic grading. Submissions execute in a
locked-down Docker sandbox, and a proctoring layer records activity during exams.

Kotlin throughout: a Spring Boot backend, a Compose Multiplatform frontend targeting desktop and the browser,
an instructor CLI, and a separate judge service.

**Documentation is at [cs30.app](https://cs30.app).** This file covers only how to build and run the code.

## Requirements

- **JDK 21.** What CI uses and what the Gradle toolchain targets.
- **The Gradle wrapper.** Use `./gradlew`; do not install Gradle separately.
- **Docker**, to run the judge. Without it the judge starts but cannot execute code.

You do not need PostgreSQL locally — the backend falls back to an in-memory H2 database.

## Build and run

```bash
./gradlew build                  # compile everything
./gradlew test                   # run the test suite; same command CI runs

./gradlew :backend:bootRun       # backend on :8080, serving the web frontend
./gradlew :frontend:run          # desktop app, in a second terminal
```

To run the judge as well, so Run and Submit actually execute code:

```bash
docker build -t judge-sandbox:latest kt-judge/sandbox
./gradlew :kt-judge:bootRun      # listens on judge.port, default 8000
```

The shipping jar bundles the backend, the web frontend and the CLI:

```bash
./gradlew :cli:bootJar           # -> cli/build/libs/cs30-1.0-SNAPSHOT.jar
java -jar cli/build/libs/cs30-1.0-SNAPSHOT.jar serve        # start the server
java -jar cli/build/libs/cs30-1.0-SNAPSHOT.jar --help       # or run an admin command
```

Full local setup, including OAuth credentials and loading a course so there is a problem to open:
[cs30.app/internal/development/setup](https://cs30.app/internal/development/setup/).

## Documentation

<<<<<<< Updated upstream
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
=======
| Topic | |
|---|---|
| Everything, indexed | <https://cs30.app/> |
| **Architecture** — the system on one page | <https://cs30.app/internal/architecture/overview/> |
| Components, module by module | <https://cs30.app/internal/architecture/components/> |
| Request flows: login, run, submit, autosave, proctoring | <https://cs30.app/internal/architecture/data-flow/> |
| Data model | <https://cs30.app/internal/architecture/data-model/> |
| Backend API reference | <https://cs30.app/internal/api/> |
| **Deployment** — what runs where, and how a release gets there | <https://cs30.app/internal/deployment/overview/> |
| Configuration: every setting and where secrets come from | <https://cs30.app/internal/deployment/configuration/> |
| Runbook: operations, capacity, troubleshooting | <https://cs30.app/internal/deployment/runbook/> |
| CI/CD pipeline | <https://cs30.app/internal/cicd/> |
| **Local setup** | <https://cs30.app/internal/development/setup/> |
| Branches, PRs and what a merge triggers | <https://cs30.app/internal/development/workflow/> |
| Testing: what is covered and what is not | <https://cs30.app/internal/development/testing/> |
| **Getting started** for instructors and TAs | <https://cs30.app/external/getting-started/> |
| Setting up a course | <https://cs30.app/external/usage/> |
| CLI command reference | <https://cs30.app/external/cli-reference/> |
| Contributing | <https://cs30.app/external/contributing/> |

## Layout
>>>>>>> Stashed changes

```
data/        shared models and interfaces; Kotlin Multiplatform, no platform dependencies
backend/     Spring Boot server — controllers, services, JPA repositories
frontend/    Compose Multiplatform UI; desktop JVM and wasmJs from one source set
cli/         instructor CLI, and the composition root that builds the shipping jar
kt-judge/    the judge: its own service and jar, runs submissions in Docker
deploy/      systemd units and the production properties file
docs/        the documentation site published at cs30.app (Jekyll)
scripts/     one-time server setup and permission scripts
templates/   course and lab YAML templates
loadtest/    k6 load-test scripts and measured results
```

`:frontend` and `:backend` both depend on `:data` and never on each other; the frontend reaches the backend
over HTTP at runtime only.
