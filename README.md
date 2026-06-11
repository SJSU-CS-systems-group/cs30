# CS30 — Student Coding Lab Editor

A Kotlin Multiplatform + Compose Multiplatform student coding editor for university labs.

Runs as a **Desktop app (JVM)** backed by a **Spring Boot server** for OAuth, problem serving, autosave, and activity logging.

---

## Modules

| Module | What it is |
|--------|-----------|
| `:backend` | Spring Boot server on `:8080`. OAuth 2.0, problem delivery via SSH git, autosave, activity logging. |
| `:frontend` | Compose Multiplatform UI (desktop JVM + wasmJs). |
| `:data` | Shared Kotlin models used by both backend and frontend. |
| `cli/` | Command-line tool for instructors to create courses and labs. |

---

## Requirements

- JDK 21+
- PostgreSQL (for the backend database)
- SSH access to the server where problems and student repos live
- Google Cloud project with OAuth 2.0 credentials

---

## Configuration

All configuration lives in a single `application.properties` file at the **repo root**. Both the backend (at runtime) and the frontend build (Gradle) read from this file.

```properties
server.port=8080

# Google OAuth
google.client-id=<your-client-id>
google.client-secret=<your-client-secret>
google.redirect-uri=http://localhost:8080/callback

# Database (any Spring-compatible DB: PostgreSQL, MySQL, H2, etc.)
spring.datasource.url=<jdbc-url>
spring.datasource.username=<username>
spring.datasource.password=<password>
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

# Git server (backend SSHes to this host to read problems and commit student code)
git.server.ssh-host=localhost
git.server.ssh-user=<server-username>

# Session
server.servlet.session.timeout=1h

# Frontend backend URL (read by desktop app build)
cs30.backend.url=http://localhost:8080
```

Get OAuth credentials from [Google Cloud Console → APIs & Credentials](https://console.cloud.google.com/apis/credentials).
Register `http://localhost:8080/callback` as an authorized redirect URI.

---

## Setup: Backend on Server, Frontend on Local Mac

This is the standard development setup: the Spring Boot backend runs on a remote Linux server, and the desktop app runs on your Mac. An SSH tunnel bridges them.

### 1. One-time server setup

On the server, create the directory structure:

```bash
mkdir -p ~/cs30/repos/students
cd ~/cs30/repos/students && git init && git commit --allow-empty -m "init"

mkdir -p ~/cs30/repos/problems
# Problems go under: problems/section_<N>/lab_<N>/<slug>/index.html
```

Ensure the backend process can SSH to itself for git operations:

```bash
# On the server
cat ~/.ssh/id_rsa.pub >> ~/.ssh/authorized_keys
ssh-keyscan localhost >> ~/.ssh/known_hosts
ssh localhost echo "OK"   # should print OK without password prompt
```

### 2. Copy application.properties to server

The backend reads `application.properties` at startup. Copy it to the server alongside the JAR:

```bash
scp application.properties <user>@<server>:~/cs30/
```

### 3. Build and deploy backend

```bash
./gradlew :backend:bootJar
scp backend/build/libs/backend-1.0-SNAPSHOT.jar <user>@<server>:~/cs30/
```

Start the backend on the server:

```bash
# On the server
cd ~/cs30
java -jar backend-1.0-SNAPSHOT.jar \
  --spring.config.location=file:./application.properties
```

### 4. Open SSH tunnel (on your Mac)

Google OAuth only works with `localhost` redirect URIs. The SSH tunnel maps the server's port 8080 to your Mac's localhost:

```bash
ssh -L 8080:localhost:8080 <user>@<server>
```

Keep this terminal open while using the app.

### 5. Run the desktop frontend

```bash
./gradlew :frontend:run
```

The app reads `cs30.backend.url=http://localhost:8080` from `application.properties` at build time and connects through the SSH tunnel.

---

## Course Setup (CLI)

Use the CLI to create courses and labs in the database. The CLI reads the same `application.properties`.

```bash
java -jar cli/cs30-cli-1.0-SNAPSHOT.jar addcourse --course-file=<path-to-course.yaml>
```

Example `course.yaml`:

```yaml
courseCode: CS30
courseName: Intro to CS
section: 1
problemGitRepo: /home/<user>/cs30/repos/problems
instructorEmail: instructor@sjsu.edu
students:
  - student@sjsu.edu
labs:
  - labNumber: 1
    startDateTime: "2026-06-10T00:00:00"
    endDateTime: "2026-06-10T23:59:59"
```

---

## How it works end-to-end

```
Student Mac                          Server
─────────────────────────────────────────────────────────────
Desktop App  ──HTTP (via SSH tunnel)──► Spring Boot :8080
                                         │
                                         ├── Google OAuth (redirect to localhost)
                                         ├── Problem delivery (SSH cat from problems repo)
                                         ├── Autosave (SSH git commit to students repo)
                                         └── Activity logging (SSH append CSV)
```

1. Student clicks **Login with Google** → browser opens → OAuth completes → session established.
2. App fetches problem list for the student's active lab time window.
3. Student writes code → autosave commits to `students/` repo every 60 seconds (only when code changes).
4. Lockdown events (paste, focus loss, F12) are logged to a CSV per session and committed at end of lab.

---

## Project Structure

```
cs30/
├── application.properties          # All config (backend + frontend build)
├── data/src/commonMain/kotlin/data/
│   └── *.kt                        # Shared models (Student, LabProblemInfo, TestResult…)
├── backend/src/main/kotlin/com/cs30/server/
│   ├── controller/                 # HTTP route handlers
│   ├── service/                    # GitService, ProblemService, ActivityLogService…
│   ├── repository/                 # Spring Data JPA repos
│   ├── models/                     # Server-only request/response models
│   └── login/                      # OAuth handler + session management
├── frontend/src/
│   ├── commonMain/kotlin/          # Shared Compose UI (editor, problems, lockdown…)
│   ├── desktopMain/kotlin/         # JVM platform impls (AuthService, HtmlRenderer…)
│   └── wasmJsMain/kotlin/          # Browser platform impls
└── cli/                            # Instructor CLI (addcourse command)
```

