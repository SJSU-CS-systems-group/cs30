---
title: Architecture
parent: External
nav_order: 6
---

# Architecture

> **Draft**
> Stub. A public, high-level version of the internal architecture, without any infrastructure specifics.
{: .info }

At a high level, CS30 has three parts: a client (web or desktop) built with Compose Multiplatform, a Spring Boot backend that also serves the web client, and a judge service that runs submitted code inside an isolated Docker sandbox. Student work is stored in git repositories, and login uses Google OAuth.

<!-- TODO:
  Adapt the internal architecture overview into a public explanation.
  Keep the high-level component diagram, drop server names, ports, and ops detail.
-->

The detailed, internal version is in the [internal architecture docs]({% link internal/architecture/overview.md %}).
