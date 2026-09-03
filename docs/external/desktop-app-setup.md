---
title: Desktop App Setup
parent: External
nav_order: 4
---

# Desktop App Setup

Once your course is [set up]({% link external/usage.md %}), you'll likely want students using the
desktop app instead of (or alongside) the browser. There's no separate "download an installer"
step — building the installer *is* the setup, because the backend URL your students' app will talk
to gets baked into the package when it's built. There's no way to point an already-built installer
at a different server afterward, so build it once your course's backend URL is final.

## What you need

- **JDK 21** and the repo's Gradle wrapper (`./gradlew`) — see [Local setup]({% link internal/development/setup.md %})
  for the full prerequisites if you're setting this up for the first time.
- The CS30 source checked out, since packaging is a Gradle task run from the repo root.

## 1. Point the build at your backend

Set `cs30.backend.url` in `application.properties` (repo root) to your course's actual backend
URL — not `localhost`. The desktop app reads this once, at build time; it is not something a
student's installed app checks again later.

## 2. Build the installer for your OS

Run the package task for the OS you're targeting, from the repo root:

```bash
./gradlew :frontend:packageDmg    # macOS   -> frontend/build/compose/binaries/main/dmg/
./gradlew :frontend:packageMsi    # Windows
./gradlew :frontend:packageDeb    # Linux
```

jpackage doesn't cross-compile, so build each installer on a machine running that OS. Students
don't need Java, Gradle, or any of this installed themselves — the JRE is bundled into the
package.

## 3. Distribute it

Hand the resulting installer file to your students (or lab machines) however you normally
distribute software — a shared drive, your course site, USB, etc. Installing it is a normal
double-click install on each platform; no configuration is needed on the student's end.

## If you need to change the backend URL later

Repeat steps 1–3 with the new URL and redistribute. Existing installs will keep pointing at the
old URL until replaced.

For troubleshooting or more detail on the underlying build, see
[Local setup]({% link internal/development/setup.md %})'s "Packaging the desktop app" section —
that page is the full technical reference this one summarizes.
