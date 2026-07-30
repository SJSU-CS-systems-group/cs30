---
title: Home
nav_order: 1
---

<div class="home-hero" markdown="1">

# CS30 Documentation

A proctored online coding-lab platform for university CS courses. Students log in
with Google, open a lab problem in an editor, run their code against sample tests,
and submit for automatic grading. Code runs inside a locked-down Docker sandbox,
and a proctoring layer records activity during exams.

[Internal docs]({% link internal/index.md %}){: .btn .btn-primary .mr-2 }
[External docs]({% link external/index.md %}){: .btn }

</div>

## Where to go

<!-- Cards use plain links, not headings: heading_anchors would inject an anchor
     icon into each one, and they would clutter the page TOC and search index. -->

<div class="card-grid">
<div class="card" markdown="1">
[Architecture]({% link internal/architecture/overview.md %}){: .card-title }

The whole system on one page, then each component, request flow, and the data model.
</div>
<div class="card" markdown="1">
[Deployment]({% link internal/deployment/overview.md %}){: .card-title }

How the jar reaches the server, the release layout, and the operational runbook.
</div>
<div class="card" markdown="1">
[CI/CD]({% link internal/cicd.md %}){: .card-title }

What the GitHub Actions pipeline builds, checks, and deploys.
</div>
<div class="card" markdown="1">
[Local setup]({% link internal/development/setup.md %}){: .card-title }

Prerequisites and how to build and run each module on your own machine.
</div>
<div class="card" markdown="1">
[API reference]({% link internal/api.md %}){: .card-title }

Every HTTP endpoint the backend exposes, with request and response shapes.
</div>
<div class="card" markdown="1">
[Running a course]({% link external/usage.md %}){: .card-title }

The instructor workflow: define a course in YAML, load it, and manage it.
</div>
</div>

## The two sections

**[Internal]({% link internal/index.md %})** is for the team building and running
CS30: architecture, deployment, CI/CD, development workflow, and operations.

**[External]({% link external/index.md %})** is for instructors, TAs, and outside
contributors: getting started, course setup, the command reference, and how to
contribute.
