---
name: good-documentation
description: Write or review documentation for the CS30 project. Use when deciding which of the five documentation layers (README, docs/external, docs/internal, CLAUDE.md, skills) a new piece of content belongs to, when adding a page to the Jekyll site, or when auditing whether existing content is stale or in the wrong layer.
---

# CS30 Documentation

CS30 has five distinct documentation layers. Each serves a different audience and purpose. The most common mistake is putting content in the wrong layer.

## CS30 Documentation Map

| Location | Audience | What belongs here |
|---|---|---|
| `README.md` | Any developer | Project pitch, quick setup, configuration reference, CLI commands, troubleshooting |
| `docs/external/` | Instructors / external users | Getting started, course setup, usage guides, CLI reference, architecture overview |
| `docs/internal/` | Developer contributors | API reference, system architecture, deployment, CI/CD, local development setup |
| `CLAUDE.md` | Claude only | Behavioral rules, repo structure, file placement, coding standards |
| `.claude/skills/` | Claude only | Domain-specific patterns — when to load, how to apply |

**If you're writing for a human reader → `README.md` or `docs/`.**
**If you're writing instructions Claude must follow → `CLAUDE.md` or a skill.**
**If it's an architectural pattern Claude needs when coding → a skill, not `CLAUDE.md`.**

## Where to Put New Content

| You're writing... | Put it in |
|---|---|
| Setup steps for a new developer | `docs/internal/development/setup.md` |
| How to use a CLI command | `docs/external/cli-reference.md` |
| How to deploy / redeploy | `docs/internal/deployment/runbook.md` |
| An API endpoint reference | `docs/internal/api.md` |
| An architectural decision | `docs/internal/architecture/` + the relevant skill |
| A coding rule for this codebase | `CLAUDE.md` coding standards section |
| A reusable pattern Claude needs when writing code | `.claude/skills/<name>/SKILL.md` |
| A project pitch or quickstart | `README.md` |

## Jekyll Front-Matter Rules (docs/)

Every page in `docs/` requires YAML front matter. Required fields:

```yaml
---
title: Page Title           # shown in nav and browser tab
parent: Parent Page Title   # matches `title:` of the parent index page exactly
nav_order: N               # integer; determines position in the nav sidebar
---
```

Add `grand_parent: Section Title` when the page is two levels deep (e.g. a page under `Architecture` which is under `Internal`).

Two top-level sections exist:
- `docs/external/` — pages for instructors; parent is `External`
- `docs/internal/` — pages for developers; parent is `Internal` (or a sub-section like `Architecture`, `Deployment`, `Development`)

To add a new top-level section: create `docs/<section>/index.md` with `has_children: true` and a unique `nav_order`.

## Writing Mode — Adding New Documentation

**README.md** is the project pitch. Keep it:
- Opening paragraph: specific value statement (what CS30 is, who uses it, what problem it solves)
- Configuration reference: keep the property table current with `application.properties`
- CLI commands: only commands that actually exist in the shipped `:cli` module
- No aspirational or planned features (per CLAUDE.md Rule 3 — verify claims against actual code before writing)

**docs/external/** is for instructors and external users. Follow the Diátaxis model:
- **Tutorial**: a guided walkthrough (`getting-started.md` already exists — extend, don't duplicate)
- **How-to guide**: task-oriented recipe (e.g. "Setting up a course")
- **Reference**: complete, terse (`cli-reference.md` — every command, every flag)
- **Explanation**: why CS30 works the way it does (`architecture.md`)

**docs/internal/** is for developer contributors. Prioritize explanation docs over reference docs — any competent developer can read the code; what they can't get from the code is the *why*. Keep `deployment/runbook.md` accurate: it is the on-call playbook.

**Skills** are documentation for Claude, not people. A skill should answer "what pattern do I follow when writing code for X?" not "what is X?" Architecture explanation → `docs/internal/architecture/`. Pattern Claude must apply when coding → `.claude/skills/`.

## Review Mode — Auditing Existing Docs

Check each layer against its purpose:

**README.md**
- [ ] Does the opening describe CS30 specifically, or could it describe any web app?
- [ ] Is the configuration table in sync with the current `application.properties` keys?
- [ ] Are any CLI commands listed that no longer exist (or vice versa)?
- [ ] Does anything describe planned or aspirational features as if they're shipped?

**docs/external/**
- [ ] Are all four Diátaxis quadrants represented at least minimally? (tutorial, how-to, reference, explanation)
- [ ] Is the CLI reference complete against the current `:cli` module? Run `grep -rn "fun " cli/src/main/ | grep -v "test\|Test"` to list commands and verify each appears in `cli-reference.md` with its flags documented.
- [ ] Does `getting-started.md` work end-to-end? An instructor should be able to follow it from zero to a running lab session — deploy the jar, configure OAuth, enroll a student, have the student load a problem — without consulting any file outside the guide.

**docs/internal/**
- [ ] Does the API reference match the actual endpoints in `backend/src/main/controller/`?
- [ ] Does the deployment runbook match the current deploy process (`:cli:bootJar` + `java -jar ... serve`)?
- [ ] Are architecture docs describing the current system? For each class or endpoint named, run `grep -rn "<ClassName>" backend/src/` to confirm it still exists with the described behavior.

**CLAUDE.md**
- [ ] Do the coding standards in `CLAUDE.md` (the numbered list under "Coding Standards") still reflect how code is actually written in the repo? Pick two or three standards at random and grep for a real file that follows each one.
- [ ] Does the module placement table match `settings.gradle.kts` and the actual directory structure?
- [ ] Are any skills referenced that no longer exist in `.claude/skills/`?

**Skills**
- [ ] Does each skill's code examples match real, current source? For every class name, function name, and file path in a skill, run `grep -rn "<symbol>"` to confirm it exists. Behavioral equivalence is not enough — verify the exact API signature.
- [ ] Does any skill describe a deleted class, retired pattern, or old package name?
- [ ] Are the `description:` frontmatter fields specific enough to trigger at the right time? A description is specific enough if it names at least one file, module, or code pattern the skill governs AND states a condition that would exclude more than half of all typical coding tasks.

## What NOT to Do

- ❌ Put architectural decisions in README — they belong in `docs/internal/architecture/` and the relevant skill
- ❌ Put operational instructions (deploy, configure) in CLAUDE.md — those are for humans in `docs/`
- ❌ Write scenario-specific bug-fix instructions into skills — skills describe patterns, not incidents
- ❌ Duplicate content across layers — a configuration property explained in README should not also be explained at length in a skill
- ❌ Commit docs that reference non-existent features or outdated APIs — grep to verify claims before writing
