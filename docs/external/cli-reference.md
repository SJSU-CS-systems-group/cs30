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

`addproblems` writes directly to the problem pool git repo and needs Docker on the machine running the
command. `addproblem` uploads a ZIP to the server over HTTP — Docker runs on the server, not on your
machine.

### `addproblem` — add one problem via upload

Uploads a problem ZIP to the server. The server extracts it, renders the statement to HTML with Docker,
and commits it to the course's problem pool repo. Uses the `cs30.cli.token` already set in your
`cs30.properties` (shown in the TA dashboard under **CLI Token**).

| Option | Required | Meaning |
|---|---|---|
| `--problem-zip <path>` | yes | Path to the problem ZIP file |
| `--course-code <code>` | yes | Course code, e.g. `CS-200` |
| `--year <n>` | yes | Course year |
| `--semester <name>` | yes | e.g. `Fall` or `Spring` |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblem \
  --problem-zip=./babyshark.zip \
  --course-code=CS-200 --year=2026 --semester=Fall
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
