# CS30 — Student Coding Lab Editor

A Kotlin Multiplatform + Compose Multiplatform student coding editor for university labs.

Consists of four components:
1. **Backend** — Spring Boot server on `:8080`. Handles OAuth, problem delivery, autosave, and activity logging.
2. **Frontend** — Compose Multiplatform UI (desktop JVM + wasmJs). Connects to backend via SSH tunnel.
3. **CLI** — Command-line tool for instructors to manage courses, students, and problems.
4. **Judge** — Python/FastAPI service in Docker. Compiles and runs student code submissions, returns verdicts.

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
server.port=8080

# Google OAuth
google.client-id=<your-client-id>
google.client-secret=<your-client-secret>
google.redirect-uri=http://localhost:8080/callback

# Database (any Spring JPA-compatible)
spring.datasource.url=<jdbc-url>
spring.datasource.username=<username>
spring.datasource.password=<password>
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

# Git server (CLI only — for uploading problems from a developer machine)
git.server.ssh-host=<server-host>
git.server.ssh-user=<server-username>

# Session
server.servlet.session.timeout=1h

# Frontend backend URL (read by desktop app build)
cs30.backend.url=http://localhost:8080
```

Get OAuth credentials from [Google Cloud Console → APIs & Credentials](https://console.cloud.google.com/apis/credentials).
Register `http://localhost:8080/callback` as an authorized redirect URI.

---

## Server Setup (One-Time)

On the server where problems and student code will live, create the directory structure:

```bash
mkdir -p ~/cs30/repos/students
cd ~/cs30/repos/students && git init && git commit --allow-empty -m "init"

mkdir -p ~/cs30/repos/problems
# Problem files go under: problems/section_<N>/lab_<N>/<slug>/index.html
```

Copy `application.properties` to the server:

```bash
scp application.properties <user>@<server>:~/cs30/
```

---

## Backend

### Build

```bash
./gradlew :backend:bootJar
# Output: backend/build/libs/backend-1.0-SNAPSHOT.jar
```

### Deploy

```bash
scp backend/build/libs/backend-1.0-SNAPSHOT.jar <user>@<server>:~/cs30/
```

### Run

On the server:

```bash
cd ~/cs30
java -jar backend-1.0-SNAPSHOT.jar \
  --spring.config.location=file:./application.properties
```

The backend listens on port `:8080`. It reads the database connection, OAuth credentials, and other config from `application.properties`.

---

## Frontend (Desktop)

### SSH Tunnel (Only for Testing phase)

Google OAuth only allows redirects to `localhost`. If your backend is on a remote server, open an SSH tunnel on your Mac:

```bash
ssh -L 8080:localhost:8080 <user>@<server>
```

Keep this terminal open while running the frontend. The frontend will connect to `http://localhost:8080` (via the tunnel) to reach the backend.

### Run

```bash
./gradlew :frontend:run
```

The desktop window opens and reads `cs30.backend.url=http://localhost:8080` from `application.properties` at build time.

---

## CLI (Instructor Tool)

Instructors use the CLI to manage courses, enroll/remove students, and upload problem definitions.

### Build

```bash
./gradlew :cli:bootJar
# Output: cli/build/libs/cs30-cli-1.0-SNAPSHOT.jar
```

### Run

```bash
java -jar cli/build/libs/cs30-cli-1.0-SNAPSHOT.jar <subcommand> [options]
```

The CLI reads the same `application.properties` file or accepts database credentials via command-line flags.

### Subcommands

| Command | Required flags | Purpose |
|---------|---|---|
| `addcourse` | `--course-file <yaml>` | Create/update course(s) from a YAML file; initializes git repos |
| `addstudent` | `--course-code`, `--year`, `--semester`, `--section`, `--email` | Enroll a student in a course section |
| `removestudent` | `--course-code`, `--year`, `--semester`, `--section`, `--email` | Unenroll a student |
| `removecourse` | `--course-code`, `--year`, `--semester`, `--section` (or `all`) | Delete a course (only after end date) |
| `changeenddate` | `--course-code`, `--year`, `--semester`, `--section`, `--end-date` | Extend or modify a course end date |
| `findcourse` | `--course-code`, `--year`, `--semester`, `--section` (or `all`) | Print course details and enrolled students |
| `findstudent` | `--email` | Find all courses containing a student |
| `addproblem` | `--course-code`, `--year`, `--semester`, `--section`, `--lab`, `--problem-dir` | Upload a single problem to the problem repo |
| `addlabs` | `--course-code`, `--year`, `--semester`, `--labs-dir` | Bulk-upload all labs from a `Section_X/Lab_X/problem/` directory tree |

### Example: Create a course

```bash
java -jar cli/build/libs/cs30-cli-1.0-SNAPSHOT.jar addcourse --course-file=course.yaml
```

**course.yaml:**
```yaml
courseCode: CS30
courseName: Intro to Computer Science
year: 2026
semester: Spring
startDate: 2026-01-01
endDate: 2026-05-31
section: 1
studentGitRepo: /home/joshini/cs30/repos/students
problemGitRepo: /home/joshini/cs30/repos/problems
language: kotlin
students:
  - joshini.naagraj@sjsu.edu
labs:
  - labNumber: 1
    startDateTime: "2026-01-10T09:00:00"
    endDateTime: "2026-01-10T10:15:00"
  - labNumber: 2
    startDateTime: "2026-01-20T09:00:00"
    endDateTime: "2026-01-20T10:15:00"
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
problems_dir: /path/to/problems      # Problem packages root
concurrency:
  max_workers: <cpu-count>           # Parallel submissions
sandbox:
  memory_mb: 512                      # Memory limit per run
  cpus: 1                             # CPU cores per run
languages:
  - c
  - cpp
  - java
  - python
```

---

## How It Works End-to-End

```
Student Mac                          Server
────────────────────────────────────────────────────────────
  Desktop App  ──HTTP (SSH tunnel)──► Spring Boot :8080
                                      │
                                      ├── OAuth callback (localhost)
                                      ├── Problem delivery (local filesystem)
                                      ├── Autosave (direct git commit via bash)
                                      ├── Activity logging (direct CSV write via bash)
                                      │
                                      └──HTTP──► Judge :8000
                                                  │
                                                  └── Docker sandbox (compile + run)
```

### Flow

1. **Login** — Student clicks "Login with Google" → browser redirects to backend → OAuth completes → session token returned.
2. **Problems** — Frontend fetches problem list for student's active lab time window. Filters by `startDateTime` and `endDateTime`.
3. **Autosave** — Student writes code → every 60 seconds, autosave sends code to backend → backend writes file directly to disk and commits via local bash. Only creates a git commit if code changed.
4. **Run/Test** — Student clicks "Run" or "Test" → backend sends request to judge → judge compiles and runs in Docker sandbox → results returned to student.
5. **Activity Log** — Every lockdown event (paste, focus loss, etc.) is logged to a CSV file on disk → at end of lab, committed via local bash.

---

## Project Structure

```
cs30/
├── application.properties                    # All config (backend, frontend, CLI)
├── data/src/commonMain/kotlin/data/
│   └── *.kt                                 # Shared models (Student, Course, LabProblemInfo…)
├── backend/src/main/kotlin/com/cs30/server/
│   ├── controller/                          # HTTP route handlers (auth, problems, autosave, activity)
│   ├── service/                             # GitService (direct bash), ProblemService, ActivityLogService…
│   ├── repository/                          # Spring Data JPA repos
│   ├── models/                              # Server-only request/response models
│   └── login/                               # OAuth handler + session management
├── frontend/src/
│   ├── commonMain/kotlin/                   # Shared Compose UI (editor, problems, lockdown…)
│   ├── desktopMain/kotlin/                  # JVM platform impls (AuthService, HtmlRenderer…)
│   └── wasmJsMain/kotlin/                   # Browser platform impls
├── cli/
│   ├── src/main/kotlin/com/cs30/cli/
│   │   └── commands/                        # picocli commands (AddCourse, AddStudent, etc.)
│   └── README.md                            # CLI-specific docs
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

1. Create problem directory structure locally (or on server): `section_1/lab_1/my-problem/`
2. Commit problem definition and HTML/CSS to the problem git repo
3. Use CLI to register it in the database (optional — backend reads from filesystem)

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

## Troubleshooting

**"Cannot reach login server"** — Backend is not running, or SSH tunnel is not open.

**Autosave files not appearing** — Check that lab times in the database cover the current time. Update with:
```sql
UPDATE course_labs SET start_date_time = NOW() - INTERVAL '1 hour', end_date_time = NOW() + INTERVAL '24 hours';
```

**Judge returns "Image not found"** — Run `docker build -t judge-sandbox:latest ./judge` first.

**OAuth callback shows "Invalid redirect URI"** — Ensure `http://localhost:8080/callback` is registered in Google Cloud Console AND the SSH tunnel is open (frontend connects via `localhost:8080`, not the server's IP).
