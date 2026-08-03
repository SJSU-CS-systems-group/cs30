---
name: cs30-kotlin-clean-code
description: DRY + clean-code rules for the CS30 KMP codebase. Use when adding new state, logic, or constants to any Kotlin file in :frontend or :data — especially when a pattern already exists elsewhere and you're about to copy it, or when deciding whether shared logic belongs in commonMain vs. a platform-specific file.
---

# CS30 Clean-Code Rules

This codebase is a Kotlin Multiplatform + Compose Multiplatform student lab app with two targets (desktop JVM, wasmJs). The rules below exist so the codebase stays easy to read and easy to swap real implementations into. Pair this with `cs30-compose-ui-style` (for visual code) and `cs30-frontend-service-wiring` (for adding services).

## Hard rules

1. **One source of truth.** Constants used by more than one screen — language list, starter-code templates, mock-file paths, action labels — live in a single `object` or top-level `val` in `commonMain`. If you find yourself typing the same literal in two files, stop and extract it.
2. **Shared logic lives in `commonMain`.** Two near-identical files under `desktopMain` and `wasmJsMain` are a smell. Extract the shared part to `commonMain` (see `LockdownState` as a model), and let the platform files hold only the OS/browser bridge. Never `expect`/`actual` something that could be pure common code.
3. **No premature abstraction.** Add an interface only when the goal requires swappability or a second impl already exists. Three similar lines beats a generic helper. (Mirrors the global `CLAUDE.md`.)
4. **Side effects belong in `LaunchedEffect` / `rememberCoroutineScope`.** Composables stay declarative. Service calls, observers, timers — never directly in a `@Composable` body.
5. **Services depend on interfaces, not impls.** Composables take `BackendService`, not `DummyBackendService`. The composition root (`App.kt`) wires the concrete impl. See `cs30-frontend-service-wiring`.
6. **Name the action, not the state.** `runCode()`, not `executeOrFetch()`. `LockdownEventService`, not `LockdownThing`.
7. **Comments only when WHY is non-obvious.** No `// dummy backend service` headers — the class name is the documentation. Exception: `// TODO(real-backend): …` markers at the swap-in points for services still awaiting their real implementation are encouraged so a `grep` finds them.
8. **Imports stay tight.** No wildcard imports. Group by stdlib → kotlinx → compose → project (the current pattern).
9. **Dead code is deleted, not commented out.** Git remembers.
10. **Multiplatform: no JVM-only APIs in commonMain.** `System.out.flush()`, `java.io.*`, `java.net.*` break the wasmJs build. Use `println()` for logging (works on both platforms). For platform-specific operations, keep them in `desktopMain`/`wasmJsMain` or use `expect`/`actual` classes.

## DRY in this codebase, specifically

- **Time**: epoch-millis uses `lockdown.currentEpochMs()` on both platforms. Don't call `System.currentTimeMillis()` from common code or duplicate `Date.now()` bridges.
- **Lockdown event types**: extend `ViolationKind` rather than introducing a parallel `LockdownEvent` hierarchy. The envelope `LockdownViolation(kind, timestampMs, detail)` carries *all* monitoring events (focus, heartbeat, summary, etc.) because the transport is the same.
- **Languages**: edit `StarterCode.kt` only. `CodeEditorPanel` reads `LANGUAGES` and `STARTER_CODE` from there.

## What "clean" looks like in a code review here

- Every new file under `frontend/src/commonMain/kotlin/<feature>/` follows the existing area split: `editor/`, `api/`, `lockdown/`, `auth/`, `html/`, `login/`, `theme/`, `app/`.
- A new service comes with a `DummyXxxService` in the same file (until a real impl shows up).
- A change that adds 10+ lines to a composable triggers a question: if the added lines are layout structure, extract to a sub-composable; if they are `mutableStateOf` declarations or action lambdas, move them to a `@Stable` state class; if they invoke a backend endpoint, that call belongs in a service method, not inline.
- An `if (platform == …)` branch in `commonMain` triggers a refactor to `expect`/`actual`.

## When in doubt

The codebase passes a clean-code review if a new contributor can:

1. Find every service the screen depends on by reading `App.kt` (composition root).
2. Swap any `DummyXxxService` for a real impl by changing one line in `App.kt`.
3. Read a screen composable top to bottom without jumping platforms.
