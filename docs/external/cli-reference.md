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
