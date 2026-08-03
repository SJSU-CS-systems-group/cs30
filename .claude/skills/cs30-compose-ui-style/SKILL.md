---
name: cs30-compose-ui-style
description: UI/layout conventions for the CS30 student coding lab. Use whenever building or modifying any Composable in the frontend module, on either desktop or wasmJs target. Keeps the two targets visually identical, code simple, and behavior functional-first.
---

# CS30 UI Style Guide

CS30 is a **student coding lab editor**. Users are students under exam conditions — clarity beats prettiness, function beats novelty. Desktop and web targets must look and behave the same; do not branch on platform for visual reasons.

## Hard rules

1. **All visual code lives in `commonMain`.** Never put `Modifier` styling, colors, or layout in `desktopMain` / `wasmJsMain`. Platform sources are for `expect`/`actual` of integration code only (clipboard, fullscreen, HTTP, native window).
2. **Material 3 + `CS30Theme` only.** Pull colors and typography from `MaterialTheme.colorScheme` / `MaterialTheme.typography`. No hardcoded hex outside `theme/`. The one exception in the codebase — the red lockdown banner (`Color(0xFFB00020)`) — exists because it must be unmissable regardless of theme; do not add more such exceptions.
3. **Use Compose's flex equivalents, not absolute positions.**
   - `Row` + `Column` for direction.
   - `Modifier.weight(f)` for proportional space (`flex: f` analogue).
   - `Arrangement.spacedBy(N.dp)` for gaps between children (`gap: Npx` analogue).
   - `Arrangement.{Start, Center, End, SpaceBetween}` for main-axis distribution (`justify-content`).
   - `Alignment.{Start, CenterHorizontally, End}` for cross-axis (`align-items`).
   - `Modifier.fillMaxSize() / fillMaxWidth() / fillMaxHeight()` for "stretch".
   - Avoid `Modifier.offset` / `absoluteOffset` and fixed `width = Xdp` on containers. Fixed sizes are fine on icons, gutters, and divider thickness.
4. **Spacing scale is 4 / 8 / 12 / 16 / 24 / 32 dp.** Do not use other numbers without a reason.
5. **One pane = one responsibility.** Editor panels (`CodeEditorPanel`, `ProblemPanel`, `CustomInputPanel`, `OutputPanel`) keep their own scroll; the parent layout does not scroll.
6. **Buttons name the action, not the state.** "Run", "Submit", "Start Lab", "Sign Out" — never "Click here" or "OK".
7. **No emoji, no decorative icons.** Material icons are allowed when they carry meaning (run = play, error = warning triangle). Skip them otherwise.

## Layout primitives — pick the right one

| Goal | Use |
|---|---|
| Header / footer / body split | `Column` with `Modifier.weight(1f)` on the body |
| Side-by-side panes (problem ‖ editor) | `Row` with `Modifier.weight()` on each pane |
| Stack of form fields | `Column(verticalArrangement = Arrangement.spacedBy(12.dp))` |
| Button cluster | `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` |
| Overlay banner / toast | `Box(fillMaxSize)` with child `Modifier.align(Alignment.TopCenter)` |
| Scrollable text | wrap in `Modifier.verticalScroll(rememberScrollState())` |
| Resizable split | use the `problemPanelFraction` + `draggable` divider pattern in `frontend/src/commonMain/kotlin/editor/CodeEditorScreen.kt` (search for `problemPanelFraction`); do not reinvent |

## Typography hierarchy

Stick to Material 3 roles — do not invent sizes.

- `headlineSmall` — screen titles ("Welcome, Test User")
- `titleMedium` — pane headers ("Problem", "Output"), primary button label
- `bodyLarge` — instruction text
- `bodyMedium` — default body / problem statement
- `bodySmall` — secondary hint, footnote, timestamp
- Monospace (`FontFamily.Monospace`) — code editor content **and** any inline code-like value (test input/output, errors)

## Color usage

- Primary actions → `MaterialTheme.colorScheme.primary` (filled `Button`)
- Secondary actions → `OutlinedButton` (no extra color)
- Destructive / sign-out → `TextButton` with `colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)`
- Disabled state → let Material handle alpha; don't override
- **Lockdown banner is the only place that uses a literal red** — `Color(0xFFB00020)`. Reused via `LockdownBanner`; do not duplicate.

**Verdict/status colors are a hard rule, not a preference:** any pass/fail/warning-style signal — test results, submission status badges, success checkmarks, anything reporting a judge verdict — anywhere in the app, must come from `theme.LocalEditorPalette.current` (`pass`/`fail`/`warning`), **never** `MaterialTheme.colorScheme`. `colorScheme.tertiary` in particular is undefined in the Light/Dark schemes and silently falls back to an unstyled Material3 default — it must never be used for app-meaningful color. `editor/OutputPanel.kt` is the canonical correct example to copy.

## Component conventions

- **Buttons** fill width only inside narrow cards (login, modal). In toolbars and toolbars-like rows, size to content.
- **Cards** use `CardDefaults.cardElevation(defaultElevation = 4.dp)` — no shadow customization beyond that.
- **TextField** uses `OutlinedTextField` everywhere (visible boundary helps students under stress).
- **Loading** is `CircularProgressIndicator()` centered in its container — never a custom spinner.
- **Lists of test results** use `LazyColumn` with `verticalArrangement = Arrangement.spacedBy(8.dp)`; one card per test case.
- **Compact metadata chip** — a read-only, low-emphasis label: `Box` with `border(1.dp, colorScheme.outline, shapes.small)`, `padding(horizontal = 8.dp, vertical = 2.dp)`, text in `labelMedium` / `onSurface`. Call sites: the editor's read-only language indicator (`editor/CodeEditorPanel.kt`) and the Custom Input case-count badge (`editor/CustomInputPanel.kt`). Reuse this exact pattern for any new compact metadata — don't invent a new variant.

## Desktop ↔ Web parity checklist

Before finishing a UI change, mentally diff what desktop and web users see:

- [ ] No `if (isDesktop) … else …` styling.
- [ ] Tested at 1280×800 (typical lab laptop) — all interactive controls (buttons, text fields, dropdowns) are fully visible; no label is truncated mid-word; Run and Submit buttons are not stacked or hidden.
- [ ] Tested at 1920×1080 — content doesn't strand in the middle of a sea of whitespace; use `Modifier.widthIn(max = 720.dp)` on read-heavy text columns (prose, problem statements).
- [ ] Keyboard focus visible on every interactive element (Material handles this if you use Material components).
- [ ] Right-click suppression and clipboard scrubbing live in `lockdown/`, not in the UI components — UI code stays unaware of lockdown mode except for `LockdownBanner`.

## What NOT to do

- ❌ Animations beyond `AnimatedVisibility(fadeIn/fadeOut)` and Material defaults. No spring physics, no bouncing.
- ❌ Custom drawing with `Canvas { … }` for anything a `Box` + border can do.
- ❌ Gradients, glassmorphism, shadows beyond `cardElevation`.
- ❌ Multiple primary buttons on one screen. Pick one "happy path" action and make it the only filled `Button`.
- ❌ Truncated error messages. If the runner produced 200 lines of stack trace, show all 200 in a scroll view — students cannot debug what they cannot read.
- ❌ Platform-specific Compose code added "to make it look better on web/desktop". Fix the common code instead.

## When in doubt

The screen passes if a tired student at 11pm could:

1. Find the next thing to click in under 2 seconds.
2. Tell at a glance whether their last action succeeded or failed.
3. Read every byte of compiler output without scrolling sideways.

If any of those isn't true, the layout is wrong before the styling is.

---

## Editor Themes (six) + syntax highlighting

The editor offers exactly six themes, selected from the top-bar settings dropdown
(`editor/IconButtons.kt`, which iterates `AppTheme.entries` and shows `displayName`):

| `AppTheme` value | Label | Intent |
|---|---|---|
| `LIGHT` | Light | Standard IntelliJ/Darcula light scheme (bundled with Highlights) — default |
| `DARK` | Dark | Standard IntelliJ/Darcula dark scheme (bundled with Highlights) |
| `LIGHT_HIGH_CONTRAST` | Light (High Contrast) | Readability-first; WCAG-AA-leaning; no red/green reliance |
| `DARK_HIGH_CONTRAST` | Dark (High Contrast) | Same, on pure black |
| `LIGHT_ANSI` | Light (ANSI) | Classic terminal; only the 16 ANSI colors |
| `DARK_ANSI` | Dark (ANSI) | Classic terminal on black; bright ANSI variants |

**Where colors live.** Material chrome stays in `theme/Theme.kt` (`ColorScheme` per theme). Editor +
output colors live in `theme/EditorPalette.kt` as `EditorPalette`, provided via `LocalEditorPalette`
inside `CS30Theme` (which also sets `LocalTextSelectionColors`). One palette per theme; **the token
model is shared — only the colors differ.**

**Shared semantic token model.** Syntax tokens come from `dev.snipme:highlights`, pinned to **1.0.0** — bump only when the project moves to Kotlin 2.2+. `EditorPalette.syntax`
is a Highlights `SyntaxTheme` whose slots map to: **keyword, string, literal (number), comment,
multilineComment, metadata (annotations/preprocessor), punctuation (operators), code (default).**
`editor/CodeHighlighter.kt` runs the tokenizer for Java/Python/C++ and builds an `AnnotatedString`
(comments italicized as a non-color cue).

- **Light/Dark token colors are delegated to Highlights' bundled `SyntaxThemes.darcula(...)`** (a
  recognized standard scheme) rather than hand-picked hexes — see `EditorPalette.kt`. Only the chrome
  extras (selection/current-line/line-number/focus/pass/fail) are custom there.
- **High-Contrast and ANSI keep a custom `SyntaxTheme`**: no stock aesthetic palette can satisfy their
  WCAG-AA / CVD-no-red-green / 16-color constraints.

- **Deliberate limitation:** Highlights does not distinguish **Type vs Function vs Variable** — they
  share the default `code` color (like default Monaco/CodeMirror). No classifier was added.
- **Errors/Warnings** are not produced by the lexer (no diagnostics engine); `EditorPalette.fail` /
  `warning` are used by the **output panel** instead, always alongside a text label.

**Live highlighting render (the one fiddly part).** Compose 1.7's `BasicTextField(state=)` can't color
spans, so `editor/CodeEditorPanel.kt` renders a colored read-only `Text` overlay **behind** the real
field (field glyphs `Color.Transparent`; caret + selection still visible). Alignment relies on: identical
`MonoTextStyle`, identical `padding`, identical width/soft-wrap, and a **shared `scrollState`** (overlay
offset by `-scrollState.value`). The current-line band is placed from the overlay's `onTextLayout`
(`getLineForOffset`/`getLineTop`, wrap-accurate) plus top padding, minus scroll. Selection colors must
stay **translucent** so overlay glyphs show through.

**Accessibility.** Selection, current-line, line-number, focus (caret/ring), and console colors are all
per-theme `EditorPalette` fields. Pass/fail in the output panel is theme-aware (`pass`/`fail`) and never
relies on color alone — the verdict text label ("Accepted"/"Wrong Answer"/…) is always shown.

**All chrome follows the theme (no branded accent bar).** The app top bar (`editor/AppTopBar.kt`) uses
`surfaceVariant`/`onSurfaceVariant` like the editor toolbar and output header — not `primary` — so it
blends across all six themes (title + LOCKDOWN label keep `primary` as a small accent; End Lab stays
`error`). The read-only language indicator is a **bordered chip** (`outline` border, `onSurface` text),
not loose text. The problem-statement panel is rendered in a WebView/iframe, so its colors are injected
via `html/HtmlTheme` (built from `colorScheme` in `editor/ProblemPanel.kt`, threaded through
`HtmlText` → `HtmlRenderer.loadHtml` → `HtmlDocument.build`, which sets `body` bg/fg `!important` plus
code/link/table colors). Desktop also paints the JavaFX pane with the theme background (no white flash).
The HTML load effect is keyed on the theme so switching themes re-renders the statement.

**Constraints honored.**
- High contrast: blue/magenta/teal/amber/brown families with large lightness spread (no red-vs-green
  lexical pairing); lexical tokens target ≥~5:1 on their background.
- ANSI: every syntax/accent color is one of the 16 standard ANSI colors; surfaces are pure black/white;
  neutral chrome bands use black/white with alpha so no non-ANSI hue is introduced.
- **Accepted ANSI limitation:** ANSI cyan/green on white and bright-blue/bright-black on black fall
  below 4.5:1 — inherent to the 16-color palette; documented rather than "fixed" by leaving the set.
- No branding/marketing wording (e.g. "colorblind-friendly", "accessibility certified").
