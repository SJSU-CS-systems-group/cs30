---
name: cs30-frontend-architecture
description: Object lifecycle scoping, business-logic/UI separation, dependency injection, and expect/actual conventions for the CS30 KMP frontend. Use when deciding where a new object/state class/service should live, when a composable accumulates more than three mutableStateOf declarations or more than two action lambdas and needs to be split into a state class, or when wiring a new dependency through App.kt.
---

# CS30 Architecture & Object Lifecycle

## Core Principle: Scope Objects to Their Closest Consumer

Every object is created and destroyed at the narrowest scope that owns it. Do not hoist objects upward unless they must survive across multiple independent consumers.

### Pattern: HtmlRenderer (Canonical Exception)

`HtmlRenderer` used to be scoped to `CodeEditorScreen` via `remember { }`, but JavaFX/WebView initialization turned
out to be too expensive and thread-sensitive to create per-screen-entry (see "HTML Rendering & JavaFX/Compose
Threading" below for the full story). It is now a **pre-initialized singleton**, created once in `main()` before
Compose starts, and shared via a `LocalHtmlRenderer` CompositionLocal:

```kotlin
// frontend/src/commonMain/kotlin/editor/CodeEditorScreen.kt
val htmlRenderer = LocalHtmlRenderer.current ?: remember { HtmlRenderer() }
```

The `remember { HtmlRenderer() }` fallback only fires if `LocalHtmlRenderer` wasn't provided (e.g. in a preview or
test) — in the real app it's always the pre-initialized instance from `main()`.

**Why the exception exists:** this violates "scope to closest consumer" on purpose. The narrowest-scope default
(`remember` at screen level) caused real reentrancy/threading bugs when JFXPanel/WebView was constructed and torn
down on every screen entry — see "HTML Rendering & JavaFX/Compose Threading" below for the concrete failure modes
and the current fix. Default to narrow scoping for every *new* object; only widen scope like this when you hit a
similar expensive-to-construct, thread-sensitive native resource, and document why (as this section does).

---

## Object Lifecycle Rules

| Scope | Lifetime | Created With | Destroyed By |
|-------|----------|--------------|--------------|
| **App level** (`App.kt`)| entire app session | `remember { }` | app exit |
| **Screen level** (e.g., `CodeEditorScreen`) | while screen is in composition | `remember { }` | screen exit |
| **Local state** (within a composable) | per recomposition | `mutableStateOf` | composable exit |

### Examples

| Object | Scope | Reason |
|--------|-------|--------|
| `LockdownController` | App | used by all screens, must track session state |
| `BackendService` | App | one real `HttpBackendService`, reused across all screens |
| `HtmlRenderer` | App (pre-initialized in `main()`, shared via `LocalHtmlRenderer`) | expensive/thread-sensitive native resource — see exception noted above |
| `CodeEditorState` | CodeEditorScreen | holds editor UI state, dies when editor closes |
| Problem HTML+CSS | CodeEditorState | loaded per problem, dies with state |

---

## Separation of Concerns

### Business Logic ↔ UI Layout

**Business logic** → non-Composable class (e.g., `CodeEditorState`)
- No Compose imports (no `@Composable`, no `remember`, no `mutableStateOf`)
- Holds all state as `mutableStateOf` fields (exposed as properties via delegates)
- Contains action methods (`onRun`, `onTest`, `onLanguageChange`, etc.)
- Created via `remember { CodeEditorState(...) }` in the composable that needs it

**UI Layout** → Composable function
- Layout only: `Row`, `Column`, `Box`, component composition
- Read state from injected objects
- Wire callbacks as method references (`state::onRun`)
- Keep lines short; extract complex sub-trees into separate composables

### Anti-pattern

```kotlin
@Composable
fun CodeEditorScreen(...) {
    var problemHtml by remember { mutableStateOf("") }
    var codeState by remember { ... }
    var outputMode by remember { ... }
    // ... 10 more var declarations
    val onRun: () -> Unit = {
        scope.launch { ... backend.runCode(...) ... }
    }
    // ... 5 more lambda definitions
    
    Column { ... }  // layout here
}
```

This mixes state declarations, action methods, and layout. Extract to `CodeEditorState`.

### Correct pattern

```kotlin
@Composable
fun CodeEditorScreen(...) {
    val state = remember(problem, backend, scope) { 
        CodeEditorState(problem, backend, scope, codeState) 
    }
    
    Column {
        TopBar(...)
        Row {
            ProblemPanel(html = state.problemHtml, ...)
            CodeEditorPanel(onRun = state::onRun, ...)
        }
    }
}
```

---

## HTML Rendering & JavaFX/Compose Threading

`HtmlRenderer` (platform expect/actual) owns the native rendering element and is pre-initialized once in `main()`
(see the "Pattern: HtmlRenderer" exception above for why). `HtmlDocument`
(`frontend/src/commonMain/kotlin/html/HtmlDocument.kt`) is the single place that builds the final HTML string
(strip `<link>`/`<script>`, normalize via `HtmlNormalizer`, inject theme CSS via `HtmlTheme`) for both platforms.

**Desktop** (`html/HtmlRenderer.desktop.kt`): `jfxPanel` is built synchronously in `init{}`; `WebView` setup is
dispatched onto the JavaFX thread via `Platform.runLater` and may finish after the constructor returns —
`loadHtml`/`setInteractive` null-check `webView` and simply drop a call if it's not ready yet, rather than
blocking. `SwingPanel`'s `factory` returns the **same pre-built `jfxPanel`** every recomposition. Never construct a
fresh `JFXPanel`/`WebView` per composable mount, and don't reintroduce a `CountDownLatch` inside `HtmlRenderer`
itself to "guarantee" readiness — the real design tolerates the race by dropping one `loadHtml` call, not blocking.

**Web** (`html/HtmlRenderer.web.kt`): the iframe attaches **once** to a persistent `#htmlOverlay` DOM element and
is toggled via `show()`/`hide()` — not mounted/unmounted per composable lifecycle. `srcdoc` never touches the
filesystem. **There is currently no `sandbox` attribute on this iframe** — a known open hardening gap, not a
solved problem; don't assume it's sandboxed.

**Security facts:** no temp files on either platform, `<link>`/`<script>` stripped before rendering, desktop
context menu disabled by default (`WebView.isContextMenuEnabled = false`), no MathJax injection currently wired up.

**Self-check:** if you touch `loadHtml`/`HtmlDocument.build`, keep the `theme: HtmlTheme` param threaded through
every caller; if you touch the web renderer, keep the "attach once, show/hide" pattern; if asked to harden the
iframe, actually add the `sandbox` attribute rather than just documenting around the gap.

---

## Lockdown Activity Logging

Lockdown security events (FocusLoss, Paste, TabHidden, etc.) are persisted on the **backend**, never written to
disk by the frontend. Both desktop and web send activity logs — there is no platform exclusion.

**Flow:** `LockdownController` emits violations (SharedFlow) and session state (`controller.active` StateFlow) →
`CsvLockdownEventService` (commonMain, wraps `DummyLockdownEventService` by composition) forwards non-excluded
violations to an `ActivityLogSink` → `HttpActivityLogSink` buffers on a `Channel` and POSTs each event → backend
`ActivityController` (`backend/src/main/controller/ActivityController.kt`) authenticates and delegates to
`ActivityLogService`, which appends to the student's daily CSV in their git repo.

**Endpoints are path-free** — `POST /api/activity/event?problem=<slug>` (query param, omitted on the Problem List)
and `POST /api/activity/commit` — identity comes from the session/auth header, never the URL.

**CSV row is exactly 7 columns, in order:** `token, timestamp_ms, timestamp_iso, platform, problem, event_kind,
detail`. One file per student per **day** (not per session) — the **token** column (not a generated session id) is
what distinguishes login sessions within a day's file; a changed token means a new login. `Heartbeat` and
`CopyFromEditor` are excluded from the CSV (high-frequency, low-value). Course/repo/section are resolved from the
student's email (`findByStudentEmail`), never from the problem slug.

**Self-check:** don't add `sessionId`/`problemSlug` to the endpoint paths; keep the CSV column count/order; check
both platform factories (`ActivityLogSessionHookFactory.{desktop,web}.kt`) when changing behavior, not just one.

---

## Kiosk Attestation (the `cs30.kiosk-secret` gate)

The rule above — "identity comes from the session/auth header, never the URL" — still holds without exception. The
kiosk secret is **not identity**. It answers "did this request come from a lab kiosk?", never "which student is
this?". Keeping those two questions separate is what makes a URL-borne credential acceptable here.

**Two independent layers.** `KioskGateFilter` (`backend/src/main/config/KioskGateFilter.kt`) is a servlet filter
registered in `WebConfig` at `Ordered.HIGHEST_PRECEDENCE + 1`, immediately after `IpWhitelistFilter`. It runs
before any controller and decides only whether a request may *proceed to* the normal auth check. Identity is still
resolved solely from the Bearer token by `StudentIdentityService`. A valid token with no kiosk attestation gets
403; a valid kiosk attestation with no token gets 401.

**Three carriers, checked in order:** the `X-CS30-Kiosk` header (desktop app), a one-shot `?kiosk=<secret>` on a
GET (the lab launcher handshake, answered with a 302 that strips the param and sets the cookie), then the
`cs30_kiosk` cookie (every later web request). Empty `cs30.kiosk-secret` = gate off, the same "empty means
disabled" idiom as `cs30.allowed-ips`.

**Invariants — do not break these:**
- The secret must **never** enter `commonMain` (it compiles to wasmJs, where page JS could read it) and never be
  mirrored to a `window.__*` global the way `ApiToken` is. Desktop reads it in `desktopMain` only, via
  `KioskSecretDesktop` (`frontend/src/desktopMain/kotlin/auth/KioskSecret.desktop.kt`).
- The secret must **never** go in `application.properties`' `# Frontend properties` block — that block is read by
  `frontend/build.gradle.kts` at *Gradle build time* and would be compiled into the wasm bundle and the shared
  installer, shipping it to every student.
- Never log the expected or presented secret; log method, path, and IP only.
- Exempt paths are `/health` (the CI deploy gate curls it), `/callback` (Google calls it and cannot carry the
  secret), `/ta` + `/ta/**` + `/api/ta/**` (TAs work from their own laptops), and `/favicon.ico`. `/api/**` must
  stay gated — a hand-crafted Google auth URL can mint a real token via the exempt `/callback`, and the gate is
  what stops that token from being usable.
- The cookie's `secure` flag mirrors `request.isSecure`. Hardcoding `true` breaks local HTTP dev, where the
  browser accepts a `Secure` cookie and then never sends it back — which presents as a permanent, baffling 403.

**Self-check:** don't thread the kiosk param through the frontend to "fix" the cookie; don't add the secret to
`login_sessions` or any identity path; if you add a new exempt route, add a filter test for it.

---

## @Stable Classes for State

When a non-Composable class holds `mutableStateOf` fields that need to recompose the UI when changed:

```kotlin
@Stable
class CodeEditorState(...) {
    private val _outputMode = mutableStateOf<OutputMode>(OutputMode.Empty)
    var outputMode by _outputMode  // delegates to mutableStateOf
}
```

`@Stable` tells Compose: "this object's identity never changes, but its fields may; treat field changes as grounds for recomposition."

---

## No Global Singletons for Services

❌ Do not: `object BackendService { ... }`

✅ Do: Create in `App.kt`, pass via composition

```kotlin
val backend: BackendService = remember { HttpBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() } }
// Pass down: App → Screen → Panel → Component
```

Reason: testability, explicit dependency, easy to point at a different backend URL or swap in a fake for a test.
`BackendService` today only has the real `HttpBackendService` implementation (the earlier `DummyBackendService`
was retired once the real backend existed) — see `cs30-frontend-service-wiring` for the Dummy+Http shape to use for your
*next* service, while it still has two implementations to swap between.

---

## Expect/Actual for Platform Code

Only when the implementation differs by platform (JVM vs browser):

```kotlin
// commonMain
expect class HtmlRenderer() { fun loadHtml(...) }

// desktopMain: JFXPanel + WebView
actual class HtmlRenderer { ... }

// wasmJsMain: iframe
actual class HtmlRenderer { ... }
```

Do NOT create expect/actual for UI components or logic. `@Composable` is always commonMain.

---

## Dependency Injection: ProblemRepository Pattern

State classes accept their dependencies via constructor, not via hardcoded global lookups.

### Anti-pattern

```kotlin
@Stable
class CodeEditorState(...) {
    private val problemRepository = HttpProblemRepository(hardcodedUrl)  // ❌ hardcoded, not injected

    fun loadProblem(slug: String) {
        val problem = problemRepository.getProblemContent(slug)  // can't be swapped for a test double
    }
}
```

**Problem:** hardcoding the concrete class inside the state means you can never substitute a fake in a test or
point at a different backend without editing `CodeEditorState` itself.

### Correct Pattern

`ProblemRepository` lives in the `:data` module (shared by backend and frontend), not in `:frontend` — real
signature:

```kotlin
// data/src/commonMain/kotlin/data/ProblemRepository.kt
interface ProblemRepository {
    suspend fun listProblemsForStudent(email: String): List<ProblemSummary>
    suspend fun getProblemContent(slug: String): ProblemContent
}

@Stable
class CodeEditorState(
    private val problemRepository: ProblemRepository,  // ✅ injected
    private val scope: CoroutineScope,
) {
    fun loadProblem(slug: String) {
        scope.launch {
            val problem = problemRepository.getProblemContent(slug)
            _problem.value = problem
        }
    }
}
```

### Wiring in App.kt

Today there's exactly one implementation — `HttpProblemRepository` (`frontend/src/commonMain/kotlin/api/HttpProblemRepository.kt`,
package `backend`) — wired unconditionally, since the real backend already exists and there's no mock variant to
fall back to:

```kotlin
@Composable
fun App(...) {
    val problemRepository: ProblemRepository = remember {
        HttpProblemRepository(defaultReporterBaseUrl) { getCurrentAuthHeader() }
    }
    CodeEditorScreen(problemRepository = problemRepository, /* … */)
}
```

**The point of this pattern isn't the branch** (there's nothing to branch on right now) — it's that `CodeEditorState`
depends on the `ProblemRepository` interface, never on `HttpProblemRepository` directly, so a test or a future
alternate implementation can be substituted without touching `CodeEditorState`.

---

## Layer Decision Architecture: Where to Solve Problems

When a Compose component wraps web-rendered content (HtmlText → WebView/iframe), problems like padding, scrolling, and spacing can be solved at multiple layers. **Choose the layer closest to the problem.**

**Pattern: Work inside out**
1. **HTML/CSS layer** (innermost) — Handles content layout, scrolling, native browser behavior
2. **HtmlDocument.build()** — Wraps content; can inject structural divs and styling
3. **Compose component level** — Padding/margins on the HtmlText or ProblemPanel
4. **Compose layout level** (outermost) — Row/Column spacing, resizable dividers

**Example: Problem content needs padding + scrollbar positioning**

**Right approach:**
1. Recognize scrolling is an HTML concern (scrollbars, overflow)
2. Wrap content in a **scrollable container div** at the HtmlDocument level
3. Apply padding to that container (CSS)
4. Scrollbar sits at container edge; divider sits at panel edge; no gap

**When deciding which layer:**
- **Scrolling behavior** → HTML/CSS (`overflow`, container divs)
- **Content spacing/padding** → HTML/CSS (`body`, wrapper divs)
- **Component layout rhythm** → Compose (`Modifier.padding`, spacing scale)
- **Panel-level structure** (tabs, split panels) → Compose (Row/Column)

---

## Self-check before shipping an architecture change

- [ ] New object: does it default to the narrowest scope that owns it? If you're widening scope (app-level instead
      of screen-level), can you name the specific expensive/thread-sensitive reason, the way the `HtmlRenderer`
      exception does? "Might need it elsewhere later" is not a reason.
- [ ] New state class: no Compose imports, no `@Composable`, no direct `remember` inside it — only the composable
      that owns it calls `remember { StateClass(...) }`.
- [ ] New dependency: injected via constructor against an interface, not a hardcoded concrete class or a global
      `object`. Can you swap it in a test without editing the class that uses it?
- [ ] If you added a `Dummy`/mock variant of anything, check the "Shape" section in `cs30-frontend-service-wiring` for the three-part file layout (interface + Dummy class + data classes in one file), and don't assume every existing service still has a Dummy — several were retired once the real backend replaced them.
- [ ] If you're touching HTML rendering or lockdown/activity-logging, did you update the dedicated sections in
      *this* file (they're the single source of truth for both) — and re-verify the CSV column count/order against `ActivityLogService`, the endpoint paths against `ActivityController`, and the iframe sandbox status against `HtmlRenderer.web.kt`?
