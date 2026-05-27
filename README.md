## Google OAuth Setup
Create a file "local.properties" in the root directory of the project with the following content:
```
GOOGLE_CLIENT_ID=your-client-id-here
GOOGLE_CLIENT_SECRET=your-client-secret-here
REDIRECT_URI=http://localhost:8080/callback
```
Get the client ID and client secret by creating an OAuth 2.0 Client ID credential in the Google Cloud Console: https://console.cloud.google.com/apis/credentials. 

Add callback URL `http://localhost:8080/callback` to the list of authorized redirect URIs for the credential.

Start the server:
```
./gradlew run 
```
# CS30 Code Editor

A Kotlin Multiplatform + Compose Multiplatform prototype student coding editor for a university coding lab.

Runs on **Desktop (JVM)** and **Web (wasmJs)**.

## Requirements

- JDK 21+
- No other tools needed — Gradle Wrapper is included

## Run on Desktop

```bash
./gradlew :composeApp:run
```

## Run on Web (dev server)

```bash
./gradlew :composeApp:wasmJsBrowserDevelopmentRun
```

Opens at `http://localhost:8080` automatically.

## Build Web (production)

```bash
./gradlew :composeApp:wasmJsBrowserDistribution
```

Output: `composeApp/build/dist/wasmJs/productionExecutable/`

To serve the production build locally:

```bash
cd composeApp/build/dist/wasmJs/productionExecutable
npx serve --cors .
```

> **Note:** The wasmJs build requires `Cross-Origin-Opener-Policy` and `Cross-Origin-Embedder-Policy` headers for WebAssembly SharedArrayBuffer. The dev server (`wasmJsBrowserDevelopmentRun`) handles this automatically. For production, use `npx serve --cors` or configure your web server accordingly.

## Project Structure

```
cs30/
├── composeApp/src/
│   ├── commonMain/kotlin/labx/     # Shared Kotlin/Compose code
│   │   ├── App.kt                  # Navigation root
│   │   ├── auth/                   # Auth layer (MockOktaAuthService)
│   │   ├── data/                   # Data models + MockDataRepository
│   │   ├── editor/                 # Editor screen components
│   │   ├── login/                  # Login screen
│   │   ├── html/                   # HtmlText expect declaration
│   │   └── theme/                  # CS30Theme
│   ├── desktopMain/kotlin/labx/    # Desktop entry point + HtmlText actual
│   └── wasmJsMain/kotlin/labx/     # Web entry point + HtmlText actual
├── composeApp/src/commonMain/composeResources/files/
│   ├── problem.html                # Baby Shark problem (babyshark_html format)
│   ├── problem.css
│   └── *.json                      # Mock backend responses
└── mock-data/                      # Human-readable copies of the JSON files
```

## Mock Data

All mock responses are in `mock-data/` (mirrored into `composeResources/files/` for bundling):

- `student.json` — logged-in student profile
- `run-output.json` — mock Run result
- `test-results.json` — mock Test/Submit results
- `runtime-error.json` — mock runtime error

The problem statement (`problem.html`) is the real **Baby Shark** problem in the standard `problembody`/`sample` HTML format, as a real backend would deliver it.

## Architecture

```
UI layer    ──  login/, editor/        (Compose screens, no data access)
Auth layer  ──  auth/                  (MockOktaAuthService → replace with real Okta)
Data layer  ──  data/                  (MockDataRepository → replace with ktor-client)
Resources   ──  composeResources/files/ (bundled HTML + JSON)
```
