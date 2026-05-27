# CS30 Code Editor

A Kotlin Multiplatform + Compose Multiplatform prototype student coding editor for a university coding lab.

Runs on **Desktop (JVM)** and **Web (wasmJs)**, backed by a small Ktor server for Google OAuth.

## Requirements

- JDK 21+
- Node 18+ (only if you want to run the Playwright-based verifier)
- No other tools needed — Gradle Wrapper is included

## Google OAuth Setup

Create `backend/local.properties` with:

```
GOOGLE_CLIENT_ID=your-client-id-here
GOOGLE_CLIENT_SECRET=your-client-secret-here
REDIRECT_URI=http://localhost:8080/callback
```

Get the client ID and secret by creating an OAuth 2.0 Client ID in the [Google Cloud Console](https://console.cloud.google.com/apis/credentials). Add `http://localhost:8080/callback` to the list of authorized redirect URIs.

## Modules

- `:backend` — Ktor server on `:8080`. Hosts the OAuth flow and serves the wasm frontend as static files.
- `:frontend` — Compose Multiplatform UI (desktop JVM + wasmJs browser targets).
- `:data` — shared Kotlin models.

## Run on Web

The backend serves the wasm frontend itself, so a single command brings up the whole stack:

```bash
./gradlew :backend:run
```

Open `http://localhost:8080`. First launch builds the wasm bundle and takes a few minutes; subsequent runs reuse the Gradle daemon.

## Run on Desktop

In one terminal:

```bash
./gradlew :backend:run
```

In another:

```bash
./gradlew :frontend:run
```

The desktop window's "Login with Google" button opens the system browser to the backend on `:8080`, completes OAuth, and posts the result back to a local socket on `:9090`.

## Frontend-only web dev server

For UI iteration without the backend (OAuth will not work):

```bash
./gradlew :frontend:wasmJsBrowserDevelopmentRun
```

Serves on its own port (usually `:8081`).

## Build Web (production)

```bash
./gradlew :frontend:wasmJsBrowserDistribution
```

Output: `frontend/build/dist/wasmJs/productionExecutable/`

To serve the production build directly:

```bash
cd frontend/build/dist/wasmJs/productionExecutable
npx serve --cors .
```

> **Note:** The wasmJs build needs `Cross-Origin-Opener-Policy` / `Cross-Origin-Embedder-Policy` headers for WebAssembly. The Gradle dev server and `:backend:run` handle this; for other production servers, configure the headers yourself.

## Project Structure

```
cs30/
├── backend/
│   ├── local.properties               # OAuth credentials (you create this)
│   └── src/main/kotlin/Server.kt      # Ktor server
├── frontend/src/
│   ├── commonMain/kotlin/labx/        # Shared Compose code
│   │   ├── App.kt                     # Navigation root
│   │   ├── auth/                      # AuthService expect + factory
│   │   ├── editor/                    # Editor screen components
│   │   ├── login/                     # Login screen
│   │   ├── html/                      # HtmlText expect declaration
│   │   └── theme/                     # CS30Theme
│   ├── commonMain/composeResources/files/
│   │   ├── problem.html               # Baby Shark problem
│   │   ├── problem.css
│   │   └── *.json                     # Mock backend responses
│   ├── desktopMain/kotlin/labx/       # Desktop entry + DesktopGoogleAuthService
│   └── wasmJsMain/kotlin/labx/        # Web entry + WebGoogleAuthService
└── data/src/commonMain/kotlin/labx/data/   # Shared models + MockDataRepository
```

## Mock Data

Mock backend responses live in `frontend/src/commonMain/composeResources/files/`:

- `student.json` — logged-in student profile (used when bypassing auth)
- `run-output.json` — mock Run result
- `test-results.json` — mock Test/Submit results
- `runtime-error.json` — mock runtime error

The problem statement (`problem.html`) is the real **Baby Shark** problem in the standard `problembody`/`sample` HTML format.

## Architecture

```
UI layer    ──  login/, editor/        (Compose screens, no data access)
Auth layer  ──  auth/                  (Google OAuth via :backend)
Data layer  ──  data/                  (MockDataRepository → replace with ktor-client)
Resources   ──  composeResources/files/ (bundled HTML + JSON)
```

## Tips

- **Skip the login screen for UI testing**: the wasm entry point reads `?name=…&email=…` and drops straight into the editor. Example: `http://localhost:8080/?name=Test+User&email=test@sjsu.edu`
- `local.properties` lives in `backend/`, not the repo root.
