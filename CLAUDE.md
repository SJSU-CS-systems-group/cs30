# CS30 — Project Rules, Repo Structure & Coding Standards

The single project memory file for this repo.

## Behavioral Rules (ENFORCED EVERY SESSION)

**Rule 1 — Always explain decisions at beginner level**

Before proposing or making any change, explain **what** will change and **why** in plain language. Assume the reader is a competent developer but new to this codebase. Include:
- What the problem is (the current state)
- What the change does (the new state)
- What the trade-off is (pros and cons)

Never just write code without this context. Beginner-friendly explanations are non-negotiable.

**Rule 2 — Never run `git commit` without explicit approval**

Do not run any `git commit`, `git push`, `git rebase`, or other state-changing git command under any circumstance, even if the user's request implies it.

Instead:
1. Show the user what would be committed (use `git status` and `git diff`)
2. Ask for explicit approval in plain language
3. Only proceed after the user says "yes" or "approved"

This rule **cannot be overridden** by any other instruction.

**Rule 3 — README documents only shipped features**

`README.md` may only describe features that are actually implemented and merged in the current codebase. Never
document planned, aspirational, or partially-built functionality as if it's available today. If a feature is
genuinely in-progress, either omit it entirely or mark it explicitly as "not yet available" — don't let the README
imply something works before it does. Before writing a claim into the README, verify it against the actual code,
the same way the `cs30-documentation` skill's review mode checks for staleness.

**Rule 4 — Update skills/CLAUDE.md first for design changes**

Whenever a design decision is made, a pattern is established, or a feature is implemented:
1. Update the relevant skill file (`.claude/skills/*/SKILL.md`) or this file first
2. Then implement the code
3. Then update memory only if needed for user preferences or session context

Skills and this file are the primary persistent knowledge store for this project. Memory is secondary.

---

## What This Project Is

CS30 is a Kotlin Multiplatform student coding-lab editor (desktop JVM + wasmJs web) with a real Spring Boot
backend, Google OAuth, git-backed autosave/activity-logging, and a sandboxed code-execution judge. It is well past
the early prototype stage — there is no mock-only mode and no bypass env vars.

For accurate, currently-true information:

- **How to build and run the code locally** → root `README.md`. It is deliberately thin: requirements, Gradle commands, repo layout, and a link table into the docs site.
- **Setup, configuration, deployment, CLI, judge, troubleshooting** → the docs site at <https://cs30.app>, source in `docs/` (Jekyll, published by `.github/workflows/docs.yml`). This is where the long-form documentation lives; do not re-add it to `README.md`.
- **Where new code goes** → the "Repo Structure & File Placement" section below.
- **Architecture/state/DI patterns** → `cs30-frontend-architecture` skill.
- **UI conventions** → `cs30-compose-ui-style` skill.
- **Service wiring (interface + Dummy + Http)** → `cs30-frontend-service-wiring` skill.
- **Everything else** → `.claude/skills/README.md` indexes every skill and what it covers.

If you're about to describe "how the app currently works" in a doc, prefer linking to one of the above over writing a new standalone description here to avoid duplication that drifts out of sync.

## Response Format Rule

After each code run:
1. Identify change type (above reference table)
2. Use matching format
3. No explanation fluff
4. Only structured info
5. Include file paths + line numbers
6. List classes + patterns used

Example output:
## Added
- PasteDetector.kt (NEW)
  - recordInternalCopy(text)
  - checkAndLogPaste(text)

## Modified
- CodeEditorViewModel.kt
  - Lines 120-140: Added paste detection hook

## Design Patterns
- Hash-based detection (avoid text buffering)
- Async event logging

## Tests
- Added: PasteDetectorTest (3 tests)

---

## Repo Structure & File Placement Rules

Read this before adding any code file. The repo is a single Gradle build with five included modules defined in
`settings.gradle.kts`: `:data`, `:backend`, `:frontend`, `:cli`, `:kt-judge`. `:cli` is named for its original
scope (instructor CLI commands wrapping `:backend`) but is now also the composition-root module that produces the
actual unified deploy jar via `:cli:bootJar` (backend + bundled web app + CLI) — see
<https://cs30.app/internal/architecture/overview/> and <https://cs30.app/internal/deployment/overview/> for why.
`:kt-judge` is the code-execution judge integration.

For everything covered by a skill (architecture patterns, service wiring, UI style, HTML rendering, testing), this
section points at the skill rather than duplicating it. This section's own job is narrower: **where does a new file go, and what's it called.**

### Repository Structure

```
cs30/
├── data/                     :data module
│   └── src/commonMain/       Kotlin Multiplatform; shared by :backend and :frontend
│       └── kotlin/data/      Shared models and interfaces
├── backend/                  :backend module — JVM-only Spring Boot server (NOT Ktor)
│   └── src/main/             Flat layout: controller/, service/, repository/, models/, dto/, config/, app/
│                             (package is `com.cs30.server.*` via the `package` declaration in each file —
│                             the directory tree itself does NOT mirror that package path)
├── frontend/                 :frontend module
│   └── src/
│       ├── commonMain/       Kotlin Multiplatform; desktop JVM + wasmJs
│       │   ├── kotlin/       Compose UI + services (packages: app, auth, editor, api, lockdown, html, theme, …)
│       │   └── composeResources/files/  HTML, CSS, JSON fixtures, images
│       ├── desktopMain/      JVM-only platform impls (*Name*.desktop.kt)
│       └── wasmJsMain/       Browser-only platform impls (*Name*.web.kt)
├── cli/                      :cli module — unified deploy jar (backend + instructor CLI)
├── judge/                    Python/FastAPI judge — not a Gradle module
├── kt-judge/                 :kt-judge module — the code-execution judge, its own service and jar
│                             (see https://cs30.app/internal/architecture/components/)
└── gradle/settings.gradle.kts, build.gradle.kts
```

### Dependency Graph

```
:frontend → :data ← :backend
```

**Rules:**
- `:data` never depends on `:backend` or `:frontend`
- `:frontend` never depends on `:backend` as a Gradle module — it talks to it only over HTTP at runtime
- `:backend` depends on `:data` only

### Quick Reference: File Placement by Type

| What | Module | Path | Package | Naming | Examples |
|---|---|---|---|---|---|
| **Shared data model** | `:data` | `data/src/commonMain/kotlin/data/<Name>.kt` | `data` | PascalCase class name | `Student.kt`, `TestResult.kt`, `ProblemSummary.kt` |
| **Repository interface** | `:data` | `data/src/commonMain/kotlin/data/<Name>.kt` | `data` | `interface ProblemRepository` | `ProblemRepository.kt` |
| **Enum (shared)** | `:data` | `data/src/commonMain/kotlin/data/<Name>.kt` | `data` | `enum class ViolationKind` | `ViolationKind.kt` |
| **Catalog (immutable values)** | `:data` | `data/src/commonMain/kotlin/data/<Name>.kt` | `data` | `object ProblemCatalog` | `ProblemCatalog.kt` |
| **Server route handler** | `:backend` | `backend/src/main/controller/<Name>Controller.kt` | `com.cs30.server.controller` | `<Domain>Controller` | `CourseController.kt`, `ProblemController.kt` |
| **Server data access** | `:backend` | `backend/src/main/repository/<Name>Repository.kt` | `com.cs30.server.repository` | `<Entity>Repository` | `CourseRepository.kt` |
| **Server service** | `:backend` | `backend/src/main/service/<Name>Service.kt` | `com.cs30.server.service` | `<Domain>Service` | `GitService.kt`, `ActivityLogService.kt` |
| **Server-only model** | `:backend` | `backend/src/main/models/<Name>.kt` | `com.cs30.server.models` | PascalCase | `AutosaveRequest.kt` |
| **Frontend UI screen** | `:frontend/commonMain` | `frontend/src/commonMain/kotlin/<feature>/<Feature>Screen.kt` | `<feature>` | `<Feature>Screen` | `LoginScreen.kt`, `CodeEditorScreen.kt` |
| **Frontend UI panel/component** | `:frontend/commonMain` | `frontend/src/commonMain/kotlin/<feature>/<Feature>Panel.kt` | `<feature>` | `<Feature>Panel` | `OutputPanel.kt`, `CodeEditorPanel.kt` |
| **Service interface (frontend)** | `:frontend/commonMain` | `frontend/src/commonMain/kotlin/<feature>/<Name>Service.kt` | `<feature>` | `interface Name`, `class DummyName`, `class HttpName` | `BackendService.kt`, `LockdownEventService.kt` |
| **HTTP client impl (frontend)** | `:frontend/commonMain` | `frontend/src/commonMain/kotlin/api/<Name>.kt` | `backend` (see `cs30-frontend-service-wiring`) | `Http<Domain>Service` | `HttpBackendService`, `HttpProblemRepository` |
| **Platform-specific impl (desktop)** | `:frontend/desktopMain` | `frontend/src/desktopMain/kotlin/<feature>/<Name>.desktop.kt` | `<feature>` | `<Name>.desktop.kt` | `AuthService.desktop.kt`, `HttpClient.desktop.kt` |
| **Platform-specific impl (web)** | `:frontend/wasmJsMain` | `frontend/src/wasmJsMain/kotlin/<feature>/<Name>.web.kt` | `<feature>` | `<Name>.web.kt` | `AuthService.web.kt`, `HttpClient.web.kt` |
| **Static asset** | `:frontend/commonMain` | `frontend/src/commonMain/composeResources/files/<path>` | N/A | lowercase, dashes | `problem.css`, `run-output.json` |

### Module-by-Module Placement Guide

#### `:data` — Shared Models & Interfaces

**Location:** `data/src/commonMain/kotlin/data/`

**What goes here:**
- `@Serializable` data classes used by both server and client
- Repository interfaces (contracts that backend and frontend both depend on) — e.g. `ProblemRepository`
- Enums representing fixed sets of values
- Static catalogs of immutable data
- No service implementations; no UI code

**Don't:**
- Put server-only models here (move to `:backend/models/`)
- Put frontend-only services here (keep in `:frontend`)
- Add service implementations (interfaces only)

#### `:backend` — Server Logic & HTTP Routes (Spring Boot)

**Location:** `backend/src/main/` — flat, not nested under a `kotlin/com/cs30/server/` directory tree. The
`com.cs30.server.*` package comes from each file's `package` declaration, not from directory nesting
(`kotlin.setSrcDirs(listOf("src/main"))` in `backend/build.gradle.kts`).

**Directory structure (confirmed against real source):**
```
backend/src/main/
├── controller/   Spring Boot @RestController route handlers (CourseController, ProblemController, ActivityController, OAuthController, …)
├── repository/   Spring Data JPA repositories (CourseRepository, …)
├── service/      Business logic and external integrations (GitService, ActivityLogService, StudentIdentityService, ProblemService, …)
├── models/       Server-only data classes (NOT in :data) — JPA entities, request/response models
├── dto/          Request/response DTOs
├── config/       WebConfig and other Spring configuration
└── app/          Application entrypoint (Application.kt)
```

**Naming:**
- Controllers: `<Domain>Controller` (e.g., `CourseController`)
- Repositories: `<Entity>Repository` (e.g., `CourseRepository`)
- New area? Add a sibling folder under `backend/src/main/`, singular name

**Don't:**
- Add JVM-only dependencies to `:data` (breaks wasmJs frontend)
- Put service implementations in `:data`
- Put HTTP code in a service meant for frontend use

#### `:frontend` — UI & Frontend Services

**Location:** `frontend/src/commonMain/kotlin/`

**Frontend package map (verified against the real top-level packages):**

| Package | Purpose |
|---------|---------|
| `app` | Root — `App.kt`, `main.kt` (composition root, DI setup) |
| `auth` | Authentication service + platform impls |
| `login` | Login screen |
| `start` | Start Lab screen (pre-lockdown welcome) |
| `problems` | Problem catalog screen |
| `editor` | Code editor screen & panels (`CodeEditorScreen`, `CodeEditorPanel`, `OutputPanel`, …) |
| `api` | HTTP API client services (`BackendService`/`HttpBackendService`, `HttpProblemRepository`, `HttpClient`) — see `cs30-frontend-service-wiring` |
| `lockdown` | Exam lockdown (fullscreen, activity logging, clipboard guard) — see `cs30-frontend-architecture`'s Lockdown Activity Logging section |
| `html` | HTML rendering bridge — see `cs30-frontend-architecture`'s HTML Rendering & Threading section |
| `theme` | Material 3 design tokens — see `cs30-compose-ui-style` |

There is currently no active `data` package under `:frontend` (a leftover empty directory may still exist on disk
from the retired `MockDataRepository` — don't put new code there without checking first).

**Source sets within `:frontend`:**

1. **`commonMain`** (default — use unless platform-specific): pure Kotlin + Compose Multiplatform; use `expect`/`actual` for platform code.
2. **`desktopMain`**: JVM-only. Naming: `<Name>.desktop.kt`. Use for JVM-only libs, AWT/Swing/JavaFX, file system access.
3. **`wasmJsMain`**: browser-only. Naming: `<Name>.web.kt`. Use for browser APIs (localStorage, fetch, DOM), JS interop.

**Multi-platform rule:** if two platforms implement the same logic differently, put the contract in `commonMain` as `expect`, implementations in `.desktop.kt`/`.web.kt`.

**Static assets (HTML, CSS, JSON, images):**
- Path: `frontend/src/commonMain/composeResources/files/`
- Read at runtime: `Res.readBytes("files/<path>")`
- `problem.css` lives once at `files/problem.css` — don't duplicate CSS per problem.

### Adding a New Feature Area

When you need to add a completely new feature (not extending an existing package):

1. **Shared model/interface required?** → `:data/src/commonMain/kotlin/data/<Name>.kt`.
2. **Server-side only?** → `backend/src/main/controller/<Feature>Controller.kt` (+ `repository/`, `service/` as needed).
3. **Frontend feature?** → new package `frontend/src/commonMain/kotlin/<feature>/`, with a service interface + Dummy
   implementation if it needs backend data (see `cs30-frontend-service-wiring` for the interface+Dummy+Http shape and how
   to wire it into `App.kt`).
4. **Platform code needed?** → contract as `expect` in `commonMain`, implementations in `.desktop.kt`/`.web.kt`.
5. Add a row to the frontend package map above once the feature package exists.

### Naming Conventions

**Files:** file name matches the primary public declaration. `BackendService.kt` contains `interface BackendService` and (while it still has one) `class DummyBackendService`.

**Classes & interfaces:**
- Screens: `<Feature>Screen.kt` → `class LoginScreen`, `class CodeEditorScreen`
- Panels/components: `<Feature>Panel.kt` → `class OutputPanel`, `class CustomInputPanel`
- Services: `interface FooService`, `class DummyFooService`, `class HttpFooService` — see `cs30-frontend-service-wiring`
- Controllers: `<Domain>Controller.kt` → `class CourseController`
- Repositories: `<Entity>Repository.kt` → `class CourseRepository`
- Data models: PascalCase → `class Student`, `class TestResult`, `enum class ViolationKind`

**Platform-specific files:** `<Name>.desktop.kt` / `<Name>.web.kt` (provides `actual` for `expect` in `commonMain`).

**Packages:** no `Util`/`Helper`/`Common` dumping grounds — a helper's home is the feature package it serves. Singular names for backend areas (`controller/`, not `controllers/`).

### Rules to Follow

**Platform Safety — don't add JVM-only dependencies to `commonMain`.** `:frontend/commonMain` compiles to both JVM (desktop) and JavaScript (web) — JVM libs (Swing, AWT, JavaFX, Apache Commons, etc.) break the wasmJs build. Put JVM code in `.desktop.kt`, browser code in `.web.kt`, shared logic in `commonMain`. Example: ❌ `java.io.File` in `commonMain` — ✓ `Res.readBytes("files/<path>")` instead.

**Architecture:**
- Don't duplicate code that changes together. Same pattern across `runCode()`/`testCode()`/`submitCode()`? Extract to a shared function. See `cs30-kotlin-clean-code`.
- Don't mix HTTP code with Dummy code. `DummyFooService` contains only mock logic + a `println(...)` log line; `HttpFooService` is a sibling class in the same file. See `cs30-frontend-service-wiring`.
- Don't promote services to `:data` prematurely. Keep service interfaces in `:frontend` unless a third module needs them — only shared *data models* belong in `:data`.

**Correctness:**
- Don't read files via `java.io` in `commonMain`. Use `Res.readBytes("files/<path>")` — works on both platforms.
- Don't use singletons or globals for services. Instantiate once in `App.kt` (composition root), pass as parameters. See `cs30-frontend-architecture`.
- Don't add a new top-level module without updating `settings.gradle.kts` (`include(":<module>")`) and root `build.gradle.kts`.
- Single source of truth for shared assets/constants. If you find a literal (CSS, a constant, starter content) duplicated in two places, extract it — check whether an existing single-source file for that kind of thing already exists before creating a new one.

---

## Coding Standards (ENFORCED - EVERY RUN)

General Kotlin hygiene, always enforced (below). For CS30-specific rules (mock/dummy conventions, KMP platform
boundaries, package layout) see the `cs30-kotlin-clean-code` skill — the two lists are complementary, not duplicates:
this one applies to any Kotlin file anywhere, `cs30-kotlin-clean-code` applies to this codebase specifically.

### 1. Extract Magic Numbers to Constants
**When:** Numbers representing time, size, count, threshold
**Why:** Easy to tweak later when requirements change

```kotlin
// ❌ WRONG
delay(5000)
if (retries > 3) return

// ✓ RIGHT
companion object {
    private const val AUTOSAVE_DEBOUNCE_MS = 5000L
    private const val MAX_SAVE_RETRIES = 3
}
delay(AUTOSAVE_DEBOUNCE_MS)
if (retries > MAX_SAVE_RETRIES) return
```

**Exclude:** Single-use calculations, version strings, error messages

### 2. Use Enums for Fixed Sets
**When:** Set of values is fixed + will be compile-time checked
**Why:** Typo protection, type safety

```kotlin
// ❌ WRONG
fun logEvent(type: String) { }
logEvent("heartbeat")  // Typo = silent bug

// ✓ RIGHT
enum class EventType { HEARTBEAT, FOCUS_LOST, PASTE_EXTERNAL }
fun logEvent(type: EventType) { }
logEvent(EventType.HEARTBEAT)  // Compile-time checked
```

**Exclude:** Free-form strings (file paths, messages), user input

### 3. Single Responsibility
**When:** A class has multiple reasons to change
**Why:** Easier to test, modify, reason about

```kotlin
// ❌ WRONG (ViewModel does file I/O + state + logging)
class ViewModel {
    fun save() {
        File.write(code)
        logger.info("Saved")
        _state.update { ... }
    }
}

// ✓ RIGHT (ViewModel only manages state)
class ViewModel(private val autosaveUseCase: AutosaveUseCase) {
    fun save() { autosaveUseCase.invoke(code) }
}
```

**Not:** One method per class (that's over-engineering)

### 4. DRY - Extract Only If Changes Together
**When:** Same code duplicated + both pieces update together
**Why:** Reduce maintenance burden

```kotlin
// ❌ WRONG (duplicate, but changes together)
fun runCode() { try { exec() } catch (e) { handle(e) } }
fun testCode() { try { exec() } catch (e) { handle(e) } }
fun submitCode() { try { exec() } catch (e) { handle(e) } }

// ✓ RIGHT (extracted)
private fun <T> executeOperation(op: suspend () -> T) {
    try { op() } catch (e) { handle(e) }
}
fun runCode() = executeOperation { exec() }
```

**Not:** Similar-looking code that changes independently

### 5. Interfaces for Testable Dependencies Only
**When:** Class will be injected into another class + mocked in tests
**Why:** Makes testing easy (swap real for fake)

```kotlin
// ✓ RIGHT (will be mocked)
interface LogService { suspend fun logEvent(event: LogEvent) }
interface AutosaveRepository { suspend fun save(code: String) }

class ViewModel(
    private val logService: LogService,
    private val autosaveRepo: AutosaveRepository
)

// ❌ WRONG (unnecessary interfaces)
interface EditorState { }  // Data class, never mocked
interface StringUtils { }  // Utility, never swapped
interface Constants { }  // Values, never mocked
```

**Only interface:** Classes that will be injected + multiple implementations (real/mock)

### 6. Self-Documenting Names
**When:** Always (no excuses)
**Why:** Code read more than written; names explain intent

```kotlin
// ❌ WRONG
val s = "session"
val t = System.currentTimeMillis()
fun f() { }
val x = 5000

// ✓ RIGHT
val sessionId = "session"
val currentTimeMs = System.currentTimeMillis()
fun saveAutosave() { }
val debounceDelayMs = 5000
```

**Convention exceptions:** Loop index `i`, caught exception `e`

### 7. Result<T> for Expected Errors
**When:** Operation may fail in expected ways (file I/O, parsing, network)
**Why:** Caller can handle gracefully

```kotlin
// ✓ RIGHT (expected failure: file doesn't exist)
fun save(code: String): Result<Unit> = try {
    File.write(code)
    Result.Success(Unit)
} catch (e: IOException) {
    Result.Failure(e)
}

when (save(code)) {
    is Result.Success -> logger.info("Saved")
    is Result.Failure -> logger.error("Failed", e)
}
```

**Not:** Programming errors (use exceptions, they're bugs)

### 8. Extract Complex Logic
**When:** Logic is conditional chain OR >3 lines OR reused
**Why:** Names explain intent, easier to test

```kotlin
// ❌ WRONG (buried intent)
if (text.hashCode() in internalCopies &&
    System.currentTimeMillis() - timestamp < 60000) {
    logEvent("paste_internal")
}

// ✓ RIGHT (clear intent)
if (isRecentInternalCopy(text)) {
    logEvent("paste_internal")
}

private fun isRecentInternalCopy(text: String): Boolean {
    val hash = text.hashCode().toString()
    return internalCopies.any {
        it.first == hash && isNotExpired(it.second)
    }
}
```

**Not:** Simple one-liners (just keep inline)

### 9. Data Classes for Models
**When:** Class is a data container (state, event, user data)
**Why:** Auto-equals, hashCode, immutable, serializable

```kotlin
// ✓ RIGHT (models)
@Serializable
data class EditorState(val code: String = "", val output: String = "")
@Serializable
data class LogEvent(val eventType: EventType, val timestamp: Long)

// ❌ WRONG (don't make data classes of non-data)
data class LogService(...)  // It's a service, not data
data class ViewModel(...)  // It's a component, not data
```

**Only for:** Models, events, responses, states

### 10. Constants in Companion Object
**When:** Class has multiple constant values
**Why:** Easy to find + change + grouped logically

```kotlin
class CodeEditorViewModel : ViewModel() {
    companion object {
        private const val TAG = "CodeEditorVM"
        private const val AUTOSAVE_DEBOUNCE_MS = 5000L
        private const val MAX_RETRIES = 3
        private const val HEARTBEAT_MS = 10_000L
    }
}
```

**Not:** Global constants scattered around codebase (creates inconsistency)

### 11. Sealed Classes for State Machines
**When:** Value is one of several distinct states + each state behaves differently
**Why:** Type-safe, forces handling all cases

```kotlin
// ✓ RIGHT (state machine)
sealed class User {
    data class Authenticated(val name: String) : User()
    object Guest : User()
}

when (user) {
    is User.Authenticated -> println(user.name)
    is User.Guest -> showLoginScreen()
}

// ❌ WRONG (overkill for optional)
sealed class Code {
    data class Valid(val code: String) : Code()
    object Invalid : Code()
}
// Just use String? instead
```

**Only for:** Multiple distinct states with different behavior

### 12. Immutable by Default
**When:** Always (use `val`, `var` exception)
**Why:** Fewer bugs, easier reasoning

```kotlin
// ✓ RIGHT (immutable state)
val code: StateFlow<String> = MutableStateFlow("")
val isRunning: State<Boolean> = derivedStateOf { ... }

// ✓ OK (var only when needed)
var isInitialized = false  // Flag that changes
```

**Sometimes var is acceptable:** Simple boolean flags that change often

### 13. Logging at Boundaries Only
**When:** Function entry/exit + errors only
**Why:** Reduces noise, shows flow

```kotlin
// ✓ RIGHT (boundaries)
fun updateCode(code: String) {
    logger.debug(TAG, "updateCode START")
    try {
        _state.update { it.copy(code = code) }
        logger.info(TAG, "updateCode SUCCESS")
    } catch (e: Exception) {
        logger.error(TAG, "updateCode FAILED", e)
    }
}

// ❌ WRONG (too verbose)
fun updateCode(code: String) {
    logger.debug("Validating...")
    logger.debug("Code = $code")
    logger.debug("Updating state...")
}
```

**Not:** Every internal state change

### 14. Extension Functions for Readability
**When:** Improves readability + used multiple times + reads naturally
**Why:** Makes domain logic clearer

```kotlin
// ✓ RIGHT (improves readability, used multiple times)
fun String.isValidCode() = trim().isNotEmpty() && length < MAX_LENGTH
if (code.isValidCode()) save()

// ❌ WRONG (single-use, just inline)
fun String.addExclamation() = this + "!"
val msg = "Hello".addExclamation()  // Just use "Hello" + "!"
```

**Only if:** Multiple uses OR significantly improves clarity

### 15. Avoid Boolean Parameters (Context Dependent)
**When:** Boolean changes major behavior path
**Why:** Clearer intent

```kotlin
// ✓ RIGHT (boolean changes major behavior)
sealed class SaveMode { object Latest : SaveMode(); object Backup : SaveMode() }
fun save(code: String, mode: SaveMode) { }
save(code, SaveMode.Backup)  // Clear intent

// ✓ OK (minor flag, acceptable)
fun sleep(isBlocking: Boolean)  // Common convention
fun retry(shouldLog: Boolean)  // Clear enough
```

**Not:** Force sealed class for every boolean (sometimes booleans are fine)

## CODE GENERATION RULE

Apply standards above. Before responding:

1. ✓ Magic numbers → constants
2. ✓ Fixed sets → enums
3. ✓ Single responsibility (one reason to change)
4. ✓ DRY (only if changes together)
5. ✓ Interfaces for injectable dependencies only
6. ✓ Self-documenting names
7. ✓ Result<T> for expected errors
8. ✓ Complex logic extracted (>3 lines or reused)
9. ✓ Data classes for models
10. ✓ Constants grouped
11. ✓ Sealed classes for state machines
12. ✓ Immutable by default
13. ✓ Logging at boundaries
14. ✓ Extension functions (if multiple uses)
15. ✓ Boolean params (only if major behavior change)

**REFACTOR if violates standards. DO NOT generate non-standard code.**

## Verification Checklist

Before submitting:

- [ ] Constants extracted (time, size, count, threshold)
- [ ] Enums used for fixed sets (not free-form strings)
- [ ] Single responsibility (one reason to change)
- [ ] DRY (duplicate code changes together)
- [ ] Interfaces only for injectable dependencies
- [ ] Names self-documenting
- [ ] Result<T> for expected errors
- [ ] Complex logic extracted or simple
- [ ] Data classes for models only
- [ ] Constants grouped in companion
- [ ] Sealed classes for state machines
- [ ] Immutable by default (val not var)
- [ ] Logging at boundaries only
- [ ] Extension functions (if useful)
- [ ] Boolean params justified (major behavior)

---

## Related Skills & Documentation

Architecture patterns, service wiring, UI style, HTML rendering, and testing are documented in skills, not here —
consult these instead of expecting this file to repeat them:

- **`cs30-frontend-architecture`** — Object lifecycle, scoping, DI, state/UI separation. Use when deciding where a new object/state class lives.
- **`cs30-kotlin-clean-code`** — DRY, naming, single responsibility. Use whenever editing Kotlin files in `frontend/` or `data/`.
- **`cs30-frontend-service-wiring`** — Interface + Dummy + Http recipe. Use when adding or swapping services.
- **`cs30-compose-ui-style`** — Material 3 components, layout patterns, theming. Use when building Composables.

`cs30-frontend-architecture` also covers HTML rendering/JavaFX-Compose threading and lockdown activity logging directly.
- **`cs30-testing`** — Test patterns per module (KMP vs. plain JVM/Spring).
- **`cs30-skill-maintenance`** — How to create/audit/retire a skill when one of the above drifts.

**Launch & testing:** `cs30-runbook` (build/run every tier), `verify` (sanity-check a change by running the app).

**Code review & refactoring:** `code-review` (correctness bugs + simplification), `simplify` (reuse/efficiency cleanups on changed code).
