# LabX Frontend — Summary

Student-facing code editor for CS30 labs. Kotlin Multiplatform + Compose Multiplatform. One UI, two targets: **desktop JVM** and **wasmJs (browser)**. The prototype runs entirely without a backend — every server-bound call is routed through a service interface whose dummy impl returns mock data and logs every call.

## Module layout

```
frontend/
  src/
    commonMain/kotlin/labx/
      App.kt                  composition root — wires services, routes screens
      auth/                   AuthService + per-platform Google auth
      backend/
        BackendService.kt     interface + DummyBackendService (run/test/submit)
      data/
        MockDataRepository.kt reads bundled JSON fixtures
      editor/
        CodeEditorScreen.kt   two-pane editor + output panel orchestration
        CodeEditorPanel.kt    editor with lined numbers + language dropdown
        ProblemPanel.kt       collapsible HTML problem statement
        CustomInputPanel.kt   always-visible stdin
        OutputPanel.kt        bottom console: run / test / submit / error
        TopBar.kt             app bar (logo, student, exit)
        StarterCode.kt        single source of truth: LANGUAGES + STARTER_CODE
      html/                   HtmlText expect/actual (Swing JEditorPane / DOM)
      lockdown/
        LockdownController.kt expect class — engages fullscreen / focus trap
        LockdownEventService.kt
                              interface + DummyLockdownEventService (telemetry)
        LockdownBanner.kt     red lockdown banner (only literal-red in app)
        Time.kt               expect currentEpochMs()
      login/                  LoginScreen
      theme/                  CS30Theme, palette
    desktopMain/              JVM/Swing implementations of expect classes
    wasmJsMain/               browser implementations of expect classes
  composeResources/files/     mock JSON + problem.html
```

## Screens

`Login → StartLab → Editor`. State machine lives in `App.kt`. The editor screen owns:
- `codeState` (multiline `TextFieldState`)
- `selectedLanguage` (drives starter via `STARTER_CODE[lang]`)
- `customInput` (stdin)
- `outputMode` (Empty / Run / Test / Error)
- `isOutputOpen`, `isProblemPanelOpen`

## Service abstractions

Two services. Both follow the pattern documented in the `cs30-service-pattern` skill.

### `BackendService` (`labx.backend.BackendService`)

```kotlin
interface BackendService {
    suspend fun runCode(req: RunRequest): RunOutput
    suspend fun testCode(req: TestRequest): TestResultsResponse
    suspend fun submitCode(req: SubmitRequest): SubmissionResult
    suspend fun lastRuntimeError(): RuntimeError
}
```

`DummyBackendService` logs every call (`[DummyBackendService] runCode :: lang=… codeLen=… stdinLen=…`) and returns mocks from `MockDataRepository`. Marked with `// TODO(real-backend)` at every swap-in point.

### `LockdownEventService` (`labx.lockdown.LockdownEventService`)

```kotlin
interface LockdownEventService {
    suspend fun observe(controller: LockdownController)
    fun log(event: LockdownViolation)
}
```

`DummyLockdownEventService` subscribes to the controller's `violations` flow, runs a 10-second heartbeat, accumulates counters, and emits a `SessionSummary` event when lockdown stops.

## Lockdown telemetry signals

Every event is a `LockdownViolation(kind, timestampMs, detail)`. Kinds:

| Kind                | Source                                          |
|---------------------|-------------------------------------------------|
| `FocusLoss`         | window blur (desktop + web)                     |
| `FocusGained`       | window/tab focus restored                       |
| `TabHidden`         | web only — `visibilitychange` to hidden         |
| `TabVisible`        | web only — `visibilitychange` to visible        |
| `CopyFromEditor`    | Ctrl/Cmd+C / +X inside editor                   |
| `PasteFromOutside`  | clipboard content didn't match last own-copy    |
| `ContextMenu`       | right-click suppressed                          |
| `DevToolsAttempt`   | F12 / Ctrl-Shift-I / Cmd-Q / Alt-F4 etc.        |
| `FullscreenExit`    | web fullscreen released                         |
| `ClipboardEscape`   | clipboard scrubbed on focus loss                |
| `Heartbeat`         | 10-second tick, tagged `active` or `idle`       |
| `HeartbeatGap`      | wall-clock gap > 1.5× interval                  |
| `SessionSummary`    | one event on lockdown stop; see below           |

`SessionSummary.detail` is a single space-separated line:

```
durationMs=… outMs=… focusLosses=… tabHidden=… copiesFromEditor=…
pastesExternal=… fullscreenExits=… navAttempts=… maxHeartbeatGapMs=…
```

These answer the measurable signals the lab cares about: how long was the student out of the app, how many times did they switch, did they paste foreign content, did they try to exit fullscreen or open devtools, were there suspicious heartbeat gaps.

## Swap-in real backend (recipe)

In `App.kt`, change two lines:

```kotlin
val backend: BackendService = remember { HttpBackendService(baseUrl = "…") }
val lockdownEvents: LockdownEventService = remember { HttpLockdownEventService(baseUrl = "…") }
```

Nothing else changes. Every UI composable depends on the interface only. `grep -rn "TODO(real-backend)" frontend/` locates the remaining swap details (endpoints, request shapes).

## Running

- Desktop: `./gradlew :frontend:run`
- Web: `./gradlew :frontend:wasmJsBrowserDevelopmentRun`
- Build everything: `./gradlew assemble`

## Related skills

- `cs30-ui-style` — visual / layout rules; Material 3 + CS30Theme only.
- `cs30-clean-code` — DRY + clean-code rules for this codebase.
- `cs30-service-pattern` — how to add a swappable service.
