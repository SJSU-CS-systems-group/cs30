# CS30 Agent Skills & Standards

This directory contains reusable agent instructions for CS30 development. Each skill is a focused guide for a specific domain — kept accurate by the process in `cs30-skill-maintenance`.

## When to Use Each Skill

| Skill | When to Use |
|-------|------------|
| **cs30-frontend-architecture** | Adding new screens, refactoring state management, designing object lifecycle, wiring a new dependency, rendering HTML/embedding JFXPanel-WebView, or modifying lockdown activity logging. Answers: where does this object live? How long does it persist? |
| **cs30-compose-ui-style** | Writing any `@Composable` in commonMain or platform-specific implementations. Answers: how should this layout look? What colors, spacing, typography? |
| **cs30-kotlin-clean-code** | Writing or reviewing any Kotlin file in `:frontend` or `:data` when you're adding new state, logic, or constants — especially when a pattern already exists elsewhere and you're about to copy it, or when a code review question arises about where shared logic belongs. Answers: is this the single source of truth? Does this belong in commonMain or a platform file? |
| **cs30-frontend-service-wiring** | Adding a new backend service, auth service, or lockdown service. Answers: how do I make it swappable? |
| **cs30-testing** | Adding a test in `:frontend`/`:data` (KMP) or `:backend`/`:cli` (JVM/Spring). Answers: which testing pattern applies to this module, and do I need a mock? |
| **cs30-runbook** | Running, building, or deploying any tier (backend, desktop frontend, web frontend, unified CLI+backend jar). |
| **cs30-documentation** | Deciding which of the five documentation layers (README, docs/external, docs/internal, CLAUDE.md, skills) a new piece of content belongs to, writing new Jekyll docs pages, or auditing existing content for staleness or misplacement. |
| **cs30-skill-maintenance** | Creating a new skill, or auditing an existing one for staleness (keep/fix/merge/retire). Use this whenever you're not sure a skill's examples still match the real code. |

## Quick Reference: Key Decisions

### Architecture
- Objects default to the scope of their **closest consumer** — with `HtmlRenderer` as a documented exception
  (pre-initialized singleton, shared via `LocalHtmlRenderer`, because JavaFX/WebView init is too expensive and
  thread-sensitive to recreate per screen entry). See the "Pattern: HtmlRenderer (Canonical Exception)" section in `cs30-frontend-architecture` for the full threading rationale and the `@Stable`/`LocalHtmlRenderer` wiring.
- `@Stable` classes hold state; `@Composable` functions handle layout only.
- Services are injected via constructor against an interface, wired once in `App.kt` (composition root) —
  several (`BackendService`, `ProblemRepository`) are Http-only today with no remaining Dummy variant; others
  (`LockdownEventService`) still branch. See the "Dependency Injection — Composition Root" section in `cs30-frontend-service-wiring` for the exact `App.kt` wiring and how to add the next service.

### UI
- Material 3 + `CS30Theme` only. No custom colors or hardcoded hex (except the lockdown banner red).
- All screens look identical on desktop and web — no platform-specific styling.
- Layout: `Row`/`Column` + `weight()` for flex, `Arrangement.spacedBy()` for gaps, `Alignment.*` for alignment.

### HTML Rendering
- `HtmlRenderer` (expect/actual) is pre-initialized once before Compose starts and shared via `LocalHtmlRenderer`.
- Desktop: JFXPanel + WebView, memory-only, `Platform.setImplicitExit(false)` to keep the FX thread alive.
- Web: iframe with `srcdoc`, attached once to a persistent overlay element and toggled via show/hide — not
  mounted per composable lifecycle.
- Shared: `HtmlDocument.build()` for both platforms, theme-aware via `HtmlTheme`.
- Full detail (including the JavaFX/Compose threading synchronization) lives in `cs30-frontend-architecture` — don't
  duplicate it elsewhere.

### Lockdown Activity Logging
- Events flow `LockdownController` → `CsvLockdownEventService` → `ActivityLogSink` → backend `ActivityController`
  → `ActivityLogService` → a per-student daily CSV in git, token-keyed (not session-id-keyed).
- Endpoints are path-free (`POST /api/activity/event?problem=`, `POST /api/activity/commit`); both desktop and
  web send logs. Full detail lives in `cs30-frontend-architecture`.

### Secrets
- No files written to disk for HTML rendering. All rendering is in-memory: WebView (desktop) or iframe `srcdoc` (web).
- Problem statements and test results are never exposed as user-accessible files.
- The web iframe currently has **no `sandbox` attribute** — a known open hardening item, not yet fixed.

## Files in This Directory

- `cs30-frontend-architecture/SKILL.md` — Object lifecycle, scope, DI, state separation
- `cs30-compose-ui-style/SKILL.md` — Material 3 rules, layout patterns, color/typography/theme standards
- `cs30-kotlin-clean-code/SKILL.md` — DRY, naming, no duplicate logic
- `cs30-frontend-service-wiring/SKILL.md` — Swappable services: interface + dummy + DI
- `cs30-testing/SKILL.md` — Test patterns per module (KMP vs. plain JVM/Spring)
- `cs30-runbook/SKILL.md` — Build & run every tier, plus the unified CLI+backend deploy jar
- `cs30-documentation/SKILL.md` — CS30 documentation map and review checklist (README, docs/, CLAUDE.md, skills)
- `cs30-skill-maintenance/SKILL.md` — How to create/audit/retire a skill in this directory

Each skill directory also has a `memory.md` — terse, dated notes on drift found and fixed, per
`cs30-skill-maintenance`'s memory protocol. Check it before trusting a skill's examples whenever the task touches an API signature, a file path, a CSV column count, or a threading/lifecycle contract — anything a refactor could have changed without updating the skill.

## How Skills Work

When working on a task, invoke the relevant skill:

```bash
/cs30-frontend-architecture
```

Skills are cumulative — you can load multiple at once:

```bash
/cs30-frontend-architecture /cs30-compose-ui-style
```

