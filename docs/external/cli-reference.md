---
title: Command reference
parent: External
nav_order: 4
---

# Command reference

Every command the `cs30` tool exposes. General form:

```bash
java -jar cs30-1.0-SNAPSHOT.jar <command> [options]
```

- Every command has `--help` and `-h`.
- Any command that touches the database also accepts `--db-url`, `--db-user`, `--db-pass`. Leave them off when you run on the server; add them otherwise. See [getting started]({% link external/getting-started.md %}).
- `--config=<path>` adds a configuration file (comma-separated for several) to the settings before anything starts — for commands, and for the server `serve` runs; the `--db-*` options override it. Without it, `cs30.properties` is picked up from the standard configuration directory if it's there — see [getting started]({% link external/getting-started.md %}).
- Each command below notes what it changes: **database**, **problem pool** (git), or **read-only**.

Dates are `yyyy-MM-dd`. Date-times are `yyyy-MM-ddTHH:mm:ss`.

---

## Courses

### `addcourse` — load a course from YAML (database + repos)
Creates or updates every section, lab, problem, and enrollment in the file. Re-running updates existing sections instead of duplicating them. If the repo paths are set, it also initializes the git repos.

| Option | Required | Meaning |
|---|---|---|
| `--course-file <path>` | yes | Path to the course YAML file |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addcourse --course-file=./course.yaml
```

The YAML format is described in [setting up a course]({% link external/usage.md %}#step-3--write-the-course-file).

### `addlab` — add or update one lab (database)
Adds a lab to a course that already exists. Matched by lab number, so re-running updates it. Uses a small lab YAML file (`templates/labTemplate.yml` is a template).

| Option | Required | Meaning |
|---|---|---|
| `--lab-file <path>` | yes | Path to the lab YAML file |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addlab --lab-file=./lab.yml
```

### `changeenddate` — change a course's end date (database)

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | e.g. `CS30` |
| `--year <int>` | yes | |
| `--semester <str>` | yes | e.g. `Summer` |
| `--section <str>` | yes | a section number, or `all` |
| `--end-date <date>` | yes | new end date, `yyyy-MM-dd` |

```bash
java -jar cs30-1.0-SNAPSHOT.jar changeenddate \
  --course-code=CS30 --year=2026 --semester=Summer --section=all --end-date=2026-08-15
```

### `removecourse` — delete a course/section (database)
Removes the section from the database. (It only removes a course that is past its end date.)

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <str>` | yes | a section number, or `all` |

```bash
java -jar cs30-1.0-SNAPSHOT.jar removecourse \
  --course-code=CS30 --year=2024 --semester=Fall --section=1
```

### `findcourse` — show a course and its students (read-only)

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <str>` | yes | a section number, or `all` |

```bash
java -jar cs30-1.0-SNAPSHOT.jar findcourse \
  --course-code=CS30 --year=2026 --semester=Summer --section=all
```

---

## Students

### `addstudent` / `removestudent` — enroll or unenroll one student (database)

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <int>` | yes | |
| `--email <email>` | yes | student email |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addstudent \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=jane@sjsu.edu

java -jar cs30-1.0-SNAPSHOT.jar removestudent \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=jane@sjsu.edu
```

### `findstudent` — list a student's courses (read-only)

| Option | Required | Meaning |
|---|---|---|
| `--email <email>` | yes | student email |

```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=jane@sjsu.edu
```

---

## TAs

### `setta` / `removeta` — set or clear a section's TA (database)

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <int>` | yes | |
| `--email <email>` | for `setta` only | TA email |

```bash
java -jar cs30-1.0-SNAPSHOT.jar setta \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=ta@sjsu.edu

java -jar cs30-1.0-SNAPSHOT.jar removeta \
  --course-code=CS30 --year=2026 --semester=Summer --section=1
```

---

## Problems in the pool

These change the **problem pool** git repo, not the database. `addproblem` and `addproblems` render statements with Docker, so run them where Docker and the repo are available.

### `addproblem` — add one problem (problem pool)

| Option | Required | Meaning |
|---|---|---|
| `--problem-dir <path>` | yes | The problem folder |
| `--git-repo <path>` | yes | The problem pool repo |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblem \
  --problem-dir=./problems/babyshark --git-repo=/path/to/problems
```

### `addproblems` — add every problem in a folder (problem pool)
Each immediate subfolder of `--problems-dir` is treated as one problem.

| Option | Required | Meaning |
|---|---|---|
| `--problems-dir <path>` | yes | Folder containing problem folders |
| `--git-repo <path>` | yes | The problem pool repo |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblems \
  --problems-dir=./problems --git-repo=/path/to/problems
```

### `removeproblem` — delete a problem from the pool (problem pool)

| Option | Required | Meaning |
|---|---|---|
| `--git-repo <path>` | yes | The problem pool repo |
| `--problem-name <name>` | yes | Folder/name to remove |

```bash
java -jar cs30-1.0-SNAPSHOT.jar removeproblem \
  --git-repo=/path/to/problems --problem-name=babyshark
```

---

## Problems in a lab

These change the **database** (the course's copy of the problem), not the pool.

### `updateproblemlanguage` — change a problem's language in one lab (database)

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <int>` | yes | |
| `--lab <int>` | yes | lab number |
| `--problem-name <name>` | yes | |
| `--language <str>` | yes | e.g. `python`, `java`, `cpp` |

```bash
java -jar cs30-1.0-SNAPSHOT.jar updateproblemlanguage \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --lab=1 \
  --problem-name=babyshark --language=java
```

### `cancellab` — remove a lab and its problems from the course (database)
Deletes the lab from the course. The problem pool is not touched.

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <int>` | yes | |
| `--lab <int>` | yes | lab number to cancel |

```bash
java -jar cs30-1.0-SNAPSHOT.jar cancellab \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --lab=4
```

---

## Checks

### `doctor` — configure the tool and check it (writes the configuration file)
Asks about whatever isn't configured yet — the database connection, the directory the course repositories live in, and the server's Google credentials — explaining the JDBC URL syntax for the drivers in the jar, and where the Google credentials come from. Settings that are already there are listed and skipped; `--reconfigure` asks about them too. It creates the repository directory if it isn't there, then checks each setting — git on the PATH, a live connection to the database and whether its tables are there, the repository directory — and offers to save what you gave it to the configuration file — the one `--config` names, or the `cs30.properties` the tool would read anyway. Run it on a machine that has never run the tool.

| Option | Required | Meaning |
|---|---|---|
| `--check` | no | report on the current setup without asking or writing anything |
| `--reconfigure` | no | ask about every setting, not only the ones that aren't configured yet |

Exits with an error if anything the commands need is missing; the server's Google credentials are reported but don't fail the check, since only `serve` needs them.

```bash
java -jar cs30-1.0-SNAPSHOT.jar doctor
java -jar cs30-1.0-SNAPSHOT.jar doctor --check
```

### `validatecourse` — confirm every referenced problem exists (read-only)
Checks each problem the course references against the problem pool and lists any that are missing. Exits with an error if something is missing. Run it before a lab opens.

| Option | Required | Meaning |
|---|---|---|
| `--course-code <code>` | yes | |
| `--year <int>` | yes | |
| `--semester <str>` | yes | |
| `--section <str>` | yes | a section number, or `all` |

```bash
java -jar cs30-1.0-SNAPSHOT.jar validatecourse \
  --course-code=CS30 --year=2026 --semester=Summer --section=all
```

---

## Canvas

These push a lab into Canvas. They change **Canvas**, never the database or the problem pool.

Both default to a **dry run**: they print what they would do and make no changes. Add `--no-dryrun` to
apply. Because one command reads a cs30 course and writes a Canvas course, the cs30 options are
prefixed `--cs30-` and the Canvas ones `--canvas-`, so it is always clear which system an option
refers to.

Set the Canvas instance and an access token before running either. The token is a secret, so keep it
in the environment and out of the configuration file:

```bash
export CANVAS_TOKEN='12~...'                     # Canvas: Account > Settings > New Access Token
export CANVAS_URL='https://sjsu.instructure.com' # only if your instance differs from the default
```

The token carries your own Canvas permissions, so you need teacher or TA rights on the course.

### `course2canvas`: create Canvas assignments for a lab

One assignment per problem in the lab, named `Lab <n> - <problem>`. Existing assignments are matched
by that name and left alone unless `--force`.

Every assignment is created with **100 points**, and dates come from the lab window: `unlock_at` from
the start, `due_at` and `lock_at` from the end.

| Option | Required | Meaning |
|---|---|---|
| `--cs30-course-code <code>` | yes | cs30 course to read |
| `--cs30-year <int>` | yes | |
| `--cs30-semester <str>` | yes | |
| `--cs30-section <int>` | yes | |
| `--cs30-lab <int>` | yes | Lab whose problems become assignments |
| `--canvas-course <id or name>` | yes | Canvas course id, or a name that matches exactly one |
| `--canvas-section <name>` | no | Scope the dates to one Canvas section, for a course that holds several |
| `--assignment-group <name>` | no | Canvas assignment group, created if missing (default `Labs`) |
| `--rubric <title>` | no | Attach an existing Canvas rubric, matched by title |
| `--dryrun` / `--no-dryrun` | no | Dry run is the default |
| `--force` / `--no-force` | no | Update assignments that already exist (default `false`) |

```bash
java -jar cs30-1.0-SNAPSHOT.jar course2canvas \
  --cs30-course-code=CS30 --cs30-year=2026 --cs30-semester=Spring \
  --cs30-section=1 --cs30-lab=1 \
  --canvas-course=12345 --rubric="Lab Rubric"
```

The rubric must already exist in the Canvas course; this never creates one, and a title that matches
nothing fails with the list of rubrics it can see. It is attached for grading, which means **Canvas
replaces the assignment's points with the rubric's own total**. If you want the assignment to stay at
100, make the rubric total 100.

The rubric is attached when an assignment is created, and again on `--force`. A plain re-run that
skips an existing assignment does not touch its rubric.

### `submissions2canvas`: mirror best submissions as comments

For each enrolled student, reads their best submission for every problem in the lab and posts it as a
**submission comment**. No grade is entered: the score is stated in the comment so the professor can
grade manually.

Run `course2canvas` first. This looks assignments up by the name that command creates, and warns for
any that are missing.

| Option | Required | Meaning |
|---|---|---|
| `--cs30-course-code <code>` | yes | cs30 course to read |
| `--cs30-year <int>` | yes | |
| `--cs30-semester <str>` | yes | |
| `--cs30-section <int>` | yes | |
| `--cs30-lab <int>` | yes | Lab whose submissions are mirrored |
| `--canvas-course <id or name>` | yes | Canvas course id, or a name that matches exactly one |
| `--dryrun` / `--no-dryrun` | no | Dry run is the default |
| `--force-comment` / `--no-force-comment` | no | Post even when the same submission was already mirrored (default `false`) |

```bash
java -jar cs30-1.0-SNAPSHOT.jar submissions2canvas \
  --cs30-course-code=CS30 --cs30-year=2026 --cs30-semester=Spring \
  --cs30-section=1 --cs30-lab=1 --canvas-course=12345
```

A posted comment looks like:

```
Best submission for pascalmagic: 1/33 test cases passed, submitted 2026-08-06T04-10-12 UTC.
submission-2026-08-06T04-10-12.cpp
<the source, inlined when under 8 KB>
```

Re-runs are cheap. A student is skipped when a comment already records a submission at least as new,
so only students who submitted again get a new comment. `--force-comment` posts regardless, which
**adds** another comment rather than editing the previous one, since Canvas comments cannot be
edited through the API.

Students are matched to Canvas users by email, falling back to the Canvas login id. Anyone with no
matching Canvas user, or with no submission, is counted and reported rather than treated as an error.

This command reads the student repo, so run it as the user that can read it (the backend service
user on the server).
