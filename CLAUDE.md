# CS30 / LabX — Repo Structure & Placement Rules

This file tells Claude where new code belongs. Read it before adding any file. The repo is a single Gradle build with three included modules (`settings.gradle.kts`): `:data`, `:backend`, `:frontend`. `cli/` exists on disk but is **not** in the Gradle build right now — do not add code there unless explicitly asked.

## Module map

```
cs30/
├── data/        :data    — Kotlin Multiplatform (commonMain)
│                          shared data models + repository interfaces
│                          consumed by BOTH :backend and :frontend
├── backend/     :backend — JVM only (Ktor server)
│                          real HTTP endpoints, persistence, OAuth
├── frontend/    :frontend — Kotlin Multiplatform (desktop JVM + wasmJs)
│                            Compose Multiplatform UI + mock impls
├── cli/         (not wired into Gradle — ignore unless asked)
└── settings.gradle.kts / build.gradle.kts
```

The dependency direction is one-way: `:frontend → :data ← :backend`. `:data` never depends on `:backend` or `:frontend`. `:frontend` never depends on `:backend` (the prototype talks to dummy services in-process; the real impl will be HTTP).

## Where to put a new file — decision tree

1. **Is it a data model or repository contract used by both server and client?**
   → `data/src/commonMain/kotlin/labx/data/<Name>.kt`
   - `@Serializable` data classes (`Student`, `TestResult`, `ProblemSummary`, `LockdownViolation`, …)
   - Repository interfaces (`ProblemRepository`)
   - Static catalogs of immutable values (`ProblemCatalog`)
   - Enums shared across tiers (`ViolationKind`)

2. **Is it server-side (HTTP route, persistence, OAuth, real business logic)?**
   → `backend/src/main/<area>/<Name>.kt`
   - `controller/` — Ktor route handlers (`CourseController`, `FileController`)
   - `repository/` — server-side data access (`CourseRepository`)
   - `models/` — server-only models that are NOT shared with the frontend (if a model is shared, it belongs in `:data` instead)
   - `login/` — OAuth flow (`OAuthLogin`)
   - New area? Add a sibling folder. Keep names singular.

3. **Is it frontend code?** Use the package that matches the feature; create the package directory under `commonMain/kotlin/labx/` if it doesn't exist. Current packages:

   | Package | What lives here | Examples |
   |---|---|---|
   | `labx` (root) | App composition root only | `App.kt` |
   | `labx.auth` | Login service abstraction + platform impls | `AuthService.kt`, `AuthServiceFactory.kt` |
   | `labx.login` | Login UI screen | `LoginScreen.kt` |
   | `labx.problems` | Problem-list screen + related UI | `ProblemListScreen.kt` |
   | `labx.editor` | Code editor screen + panels (problem, code, output, custom input, top bar) | `CodeEditorScreen.kt`, `CodeEditorPanel.kt`, `OutputPanel.kt`, … |
   | `labx.backend` | `BackendService` interface + `DummyBackendService` (run/test/submit) — frontend-side service abstraction | `BackendService.kt` |
   | `labx.lockdown` | Lockdown controller, banner, event service, clipboard guard, time | `LockdownController.kt`, `DummyLockdownEventService` (in `LockdownEventService.kt`), `LockdownBanner.kt`, `Time.kt` |
   | `labx.html` | HTML rendering helpers (desktop WebView / wasm `<iframe>`) | `HtmlText.kt`, `ProblemHtmlRenderer.kt` |
   | `labx.data` | Mock impl of `ProblemRepository` (frontend-only — talks to bundled resources) | `MockDataRepository.kt` |
   | `labx.theme` | Material 3 theme + colors | `Theme.kt` |

4. **Which source set?** Inside `:frontend`, every file goes in one of these:
   - `frontend/src/commonMain/kotlin/labx/…` — default. Pure Kotlin + Compose UI. Place files here unless they need a JVM- or browser-only API.
   - `frontend/src/desktopMain/kotlin/labx/…` — JVM-only impls. Naming convention: `<Name>.desktop.kt`, providing `actual` for an `expect` in `commonMain` (`LockdownController.desktop.kt`, `AuthService.desktop.kt`, `Time.desktop.kt`).
   - `frontend/src/wasmJsMain/kotlin/labx/…` — browser-only impls. Convention: `<Name>.web.kt` for `actual`s (`LockdownController.web.kt`, `Time.web.kt`).
   - **Rule:** if two platform files would do the same job, the shared part belongs in `commonMain` with an `expect` declaration; the platform files hold only the OS/browser bridge.

5. **Static asset (HTML, CSS, JSON fixture, image)?**
   → `frontend/src/commonMain/composeResources/files/<path>`
   - Read at runtime via `Res.readBytes("files/<path>")` from `MockDataRepository`.
   - Per-problem statements: `files/problems/<slug>/index.html`. The shared `problem.css` lives once at `files/problem.css` — do **not** duplicate it per problem.
   - Mock JSON for run/test/submit fixtures: top-level under `files/` (`run-output.json`, `test-results.json`, `runtime-error.json`, `student.json`).

## Dummy vs real services — placement

The prototype runs entirely against in-process dummies; a real backend swap is one-line in `App.kt`. Both impls live frontend-side **for now** because the dummies must compile against Compose UI types they log alongside. The shape:

- **Interface** in `commonMain` (e.g. `labx/backend/BackendService.kt`).
- **DummyFooService** in the same file or a sibling under the same package, also `commonMain`.
- **HttpFooService** (when added) lives next to its dummy in the same package. Add it; don't move the dummy.
- **DI:** instantiate once in `App.kt` (composition root) and pass down as a parameter — no singletons, no globals. To swap real for dummy, change one `remember { DummyFooService() }` to `remember { HttpFooService(baseUrl) }`.
- Every dummy method logs once: `println("[DummyFooService] action :: detail")`.
- Mark swap points with `// TODO(real-backend): …`.

**Do not** put dummy frontend services under `:backend`. `:backend` is the real Ktor server; the frontend dummies are part of the frontend prototype.

## When a model is shared but a service is not

If `:backend` and `:frontend` both need the *shape* of a request/response (e.g. `RunRequest`, `TestResultsResponse`), the data class belongs in `:data`. The frontend's `BackendService` interface can stay in `:frontend` (it returns `:data` types). Only promote an interface to `:data` if a third module needs it — premature promotion adds friction.

## Naming conventions

- File name == primary public declaration. `BackendService.kt` contains `interface BackendService` and `class DummyBackendService` (both small, one swap point — one file is fine).
- Platform actuals: `<Name>.desktop.kt` / `<Name>.web.kt`.
- Composable screens: `<Feature>Screen.kt` (e.g. `LoginScreen`, `ProblemListScreen`, `CodeEditorScreen`). Sub-panels: `<Feature>Panel.kt`.
- No `Util` / `Helpers` dumping grounds. If a helper has no home, the feature package it serves is its home.

## Don't do

- Don't add a new top-level module without updating `settings.gradle.kts`.
- Don't add JVM-only dependencies to `commonMain` — they break the wasmJs build.
- Don't read files via `java.io` in `commonMain`. Use `Res.readBytes(…)`.
- Don't duplicate per-problem CSS or shared starter code — single source of truth (`STARTER_CODE` in `labx.editor.StarterCode`, `ProblemCatalog.problems` in `labx.data.ProblemCatalog`).
- Don't put real HTTP code in a `Dummy*` class. Add a sibling `Http*` class instead.

## Related skills

- `.claude/skills/cs30-clean-code` — DRY + clean-code rules for Kotlin/Compose files.
- `.claude/skills/cs30-service-pattern` — interface + dummy + DI recipe.
- `.claude/skills/cs30-ui-style` — Material 3 component rules.
