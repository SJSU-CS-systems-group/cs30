---
title: External
nav_order: 3
has_children: true
---

# CS30

CS30 is an online coding-lab platform for university CS courses. Students log in with their school Google account, open a lab problem during its scheduled window, write and test code in the browser or a desktop app, and submit it for automatic grading. Submitted code runs in an isolated sandbox.

These docs are for the people who **run** a course on CS30 — instructors and TAs. You set up and manage everything with one command-line tool called `cs30`: courses, sections, labs, problems, students, and TAs. Students never touch the tool; they just use the web or desktop app.

## What you can do with the tool

- Load a whole course from a single YAML file — sections, labs, problems, students, and TAs in one go.
- Add coding problems to the shared problem pool.
- Add or remove students, set a section's TA, add or cancel labs, change a course's end date.
- Check that every problem your course points to actually exists before a lab starts.

## Start here

- **[Getting started]({% link external/getting-started.md %})** — get the tool and run your first command.
- **[Setting up a course]({% link external/usage.md %})** — the full instructor workflow, step by step.
- **[Command reference]({% link external/cli-reference.md %})** — every command and every option.
- **[Architecture]({% link external/architecture.md %})** — a short, high-level picture of how CS30 works.
- **[Contributing]({% link external/contributing.md %})** — for developers who want to work on CS30 itself.
