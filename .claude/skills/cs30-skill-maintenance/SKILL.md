---
name: cs30-skill-maintenance
description: How to create a new project skill, audit an existing one for staleness, or decide whether a skill should be kept, fixed, merged, or retired. Use when writing a new SKILL.md, when any class name, function signature, or file path in a skill cannot be verified by a quick grep of the current source, or when asked to review or clean up the .claude/skills directory.
---

# CS30 Skill Maintenance

This project's skills (`cs30/.claude/skills/*/SKILL.md`) are living documents that describe real, current code. They rot exactly like any other documentation: a refactor lands, nobody updates the skill that described the old shape, and the next session that trusts the skill writes code against an API that no longer exists. This skill is the checklist for keeping that from happening — run it whenever you touch a skill, not just when something visibly breaks.

## 0. Triage first — keep, fix, merge, or retire

Before improving a skill's content, decide if it should exist in its current form at all:

- **Keep** — the skill describes a pattern that is referenced in more than one skill or CLAUDE.md, or governs a security-critical or high-churn subsystem (auth, lockdown, HTML rendering, service wiring, deployment).
- **Fix** — same as keep, but its examples have drifted from the real code (the common case — go to step 2).
- **Merge** — two skills have converged on describing the same code and started contradicting each other, or one skill covers fewer than three distinct trigger conditions and all of its content fits cleanly under an existing skill's topic heading without requiring a new section. Merges can cascade — don't assume a skill that already absorbed one merge is done absorbing.
- **Retire** — the skill is a point-in-time spec (a PRD, a session summary, a snapshot of "what we decided today")
  rather than an instruction for ongoing work. Specs drift no matter how many times they're patched — the real
  code is the source of truth, not a doc trying to track it.

If you retire or merge a skill, grep the repo for its name and fix every cross-reference — a dangling
"see cs30-foo for details" pointing at a deleted directory is its own kind of stale.

## 1. Best examples/context — verify every concrete claim against real source

This is the step that actually catches drift, and it cannot be done from memory of how the code "probably still
works." For every file path, class/function name, signature, and described behavior in the skill:

- `grep`/`Read` the real file. Don't assume a class still exists because it did when the skill was written —
  classes and services get deleted as the codebase evolves, and skills that trust memory describe code paths that
  may no longer exist.
- If you find drift, fix it with a citation (`file:line`), not a vague rewrite — the next person auditing this
  skill needs to be able to re-verify your fix the same way you found the original bug.
- If a skill's example currently duplicates another skill's implementation details (not just references it), that
  duplication is a liability — the two copies will drift independently. Point at the other skill instead of
  re-hosting its code.
- Don't invent code you haven't read. If you're not sure a snippet is real, go read the file — guessing plausible
  Kotlin/Spring/whatever code is exactly how a skill accumulates fabricated sections.

## 2. Explicit trigger description — frontmatter with a concrete "use when"

Every skill needs YAML frontmatter:

```yaml
---
name: cs30-whatever
description: One or two sentences saying what this covers AND when to reach for it. "Use when X" beats a
  restatement of the title.
---
```

A skill with no frontmatter at all falls back to an auto-generated description and won't trigger reliably. A vague
description ("architecture stuff") is nearly as bad as none — be specific about the trigger condition (which
files, which kind of task, which question the skill answers).

## 3. Test manually + add a self-check

After writing or fixing a skill, mentally run the scenario it's supposed to govern: if someone follows this skill
literally right now, does the resulting code compile against the real interfaces? For anything correctness- or
security-critical, or for any fact that a refactor could silently break (an API signature, a column/field count, an endpoint path, or a threading/lifecycle contract), add a short **"Self-check before shipping"** checklist near the end of the skill — concrete yes/no items
the model can run against its own output, not generic advice. Skip this step if the skill already has an
equivalent section under a different name — "When in doubt," "Troubleshooting," "Testing & Validation" all count;
don't add a redundant second checklist.

## 4. `memory.md` per skill

Every skill directory should have a `memory.md` alongside `SKILL.md`. This is where corrections and lessons
accumulate across sessions/audits, so the next audit doesn't have to rediscover the same drift from scratch or
repeat a mistake that was already caught once.

## 5. Memory protocol — terse and pruned, not an append-only log

Use this exact convention at the top of every `memory.md`:

```markdown
# Memory: <skill-name>

Protocol: one dated bullet per entry, terse (fragments over sentences). Newest-relevant entry wins —
when a new entry supersedes an old one, delete the old one instead of appending. Don't log anything
that just restates what's already in SKILL.md.

- 2026-07-02: <what was found/fixed, one or two sentences, with enough specificity to be useful six months later>
```

Rules:
- **Terse.** Fragments, not prose paragraphs. A future reader needs the fact and the lesson, not a narrative.
- **Prune, don't accumulate.** If a new entry makes an old one obsolete (the old bug was fixed and re-broken
  differently, or the old note no longer applies), delete the old entry — don't leave a growing pile of
  superseded notes for someone to sort through.
- **Only log what's non-obvious.** Log when: (a) a fact in `SKILL.md` was wrong and had to be corrected, (b) a pattern was tried and failed before the correct one was found, or (c) a specific file or line was the source of confusion. If the fact is already stated plainly in `SKILL.md`, don't restate it in memory — memory is for *why something changed* and *what to double-check next time*, not a changelog of every edit.

## Procedure: auditing an existing skill end-to-end

1. Read the skill fully.
2. For every concrete claim, `grep`/`Read` the real source and note MATCHES / STALE with a citation.
3. Fix every STALE item, citing the real file:line in the fix itself where it aids a future reader.
4. Check frontmatter exists and has a concrete trigger description; add one if missing.
5. Check for a self-check/equivalent section; add one if the skill is correctness-critical and lacks one.
6. Check/update `memory.md`; prune anything superseded.
7. Decide keep/fix/merge/retire (step 0) — don't skip this just because you already started fixing content;
   sometimes the fix-in-progress reveals the skill should actually be merged or retired instead.
8. If you retired or merged anything, grep the repo for dangling references (skill index READMEs, `CLAUDE.md`,
   other skills' "Related" sections) and fix those too.

