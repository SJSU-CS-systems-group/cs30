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
- The Canvas commands (`course2canvas`, `submissions2canvas`) never touch the database: they read the course through the server over HTTPS, so they take `--server <url>` and `--token <cli token>` instead - or `CS30_BACKEND_URL` / `CS30_ADMIN_TOKEN` in the environment, or `cs30.backend.url` / `cs30.cli.token` in `cs30.properties` (`cs30 doctor` asks for both). See [Canvas](#canvas).
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

---

## Canvas

These push a lab into Canvas. They change **Canvas**, never the database or the problem pool.

They read the cs30 side - the lab window, its problems, the roster, and each student's best submission -
through the server rather than the database, so they run from **any machine that can reach the server**.
Tell them where it is and which CLI token to use:

```bash
export CS30_BACKEND_URL='https://sjsu.cs30.app'   # or --server, or cs30.backend.url in cs30.properties
export CS30_ADMIN_TOKEN='...'                     # or --token, or cs30.cli.token in cs30.properties
```

The admin token works for every course. A TA's own token works for the section that TA is assigned to
and is refused for any other. `--db-url` and friends are not needed and are ignored.

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

Both can also live in `cs30.properties` as `canvas.url` / `canvas.token` (`cs30 doctor` asks for
them, and its `canvas` check confirms Canvas accepts the token); the environment takes precedence.

The token carries your own Canvas permissions, so you need teacher or TA rights on the course.

### `course2canvas`: create Canvas assignments for a lab

One assignment per problem in the lab. The name is `LAB` plus the lab number padded to two digits,
with the first word of the problem's note appended when it has one:

| problem note | assignment |
|---|---|
| (none) | `LAB01` |
| `Bonus problems` | `LAB01-Bonus` |
| `Extra credit` | `LAB01-Extra` |

This is the convention used when assignments are created in Canvas by hand, so the commands find
pre-created assignments rather than making duplicates. Matching ignores case and surrounding spaces.
Existing assignments are left alone unless `--force`.

The note is the only thing separating one problem's assignment from another's, so **at most one
problem per lab may have no note**. If two problems resolve to the same name the command stops and
names them, rather than syncing both to one assignment.

Every assignment is created with **100 points**, and dates come from the lab window: `unlock_at` from
the start, `due_at` and `lock_at` from the end.

| Option | Required | Meaning |
|---|---|---|
| `--cs30-course-code <code>` | yes | cs30 course to read |
| `--cs30-year <int>` | yes | |
| `--cs30-semester <str>` | yes | |
| `--cs30-section <int>` | yes | |
| `--cs30-lab <int>` | yes | Lab whose problems become assignments |
| `--canvas-course <id or name>` | yes | Canvas course id, or a name/code fragment that matches exactly one course; a miss lists the active courses |
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

Assignments must already exist, either created by `course2canvas` or by hand in Canvas. This looks
them up by the same derived name (`LAB01` style, plus the note's first word), and for any it
cannot find it warns and lists the assignment names the course does have, so a naming mismatch is
easy to spot.

| Option | Required | Meaning |
|---|---|---|
| `--cs30-course-code <code>` | yes | cs30 course code, or a fragment of one that matches exactly one course |
| `--cs30-year <int>` | no | Narrows the match |
| `--cs30-semester <str>` | no | Narrows the match; may be a fragment (`fa` is Fall) |
| `--cs30-section <int>` | no | Narrows the match |
| `--cs30-lab <int>` | yes | Lab whose submissions are mirrored |
| `--canvas-course <id or name>` | yes | Canvas course id, or a name/code fragment that matches exactly one course |
| `--dryrun` / `--no-dryrun` | no | Dry run is the default |
| `--force-comment` / `--no-force-comment` | no | Post even when the same submission was already mirrored (default `false`) |

```bash
java -jar cs30-1.0-SNAPSHOT.jar submissions2canvas \
  --cs30-course-code=CS30 --cs30-year=2026 --cs30-semester=Spring \
  --cs30-section=1 --cs30-lab=1 --canvas-course=12345
```

Both courses can be named by a fragment, so the short form is usually enough:

```bash
java -jar cs30-1.0-SNAPSHOT.jar submissions2canvas \
  --cs30-course-code=cs30 --cs30-lab=1 --canvas-course="cs 30"
```

`--cs30-course-code` is matched by the server, case-insensitively, as a substring of the code, with an
exact code winning outright (`CS30` resolves even when `CS30A` exists). `--cs30-year`,
`--cs30-semester` and `--cs30-section` only narrow the candidates, so they are needed only when the
code alone fits more than one course. The search covers the courses your token may read: every course
with the admin token, only your own sections with a TA token. `--canvas-course` works the same way
against the Canvas course name and course code, unless it is all digits, in which case it is the
course id. Both print the course they picked before doing anything.

A fragment that fits several courses is an error that lists them, so the sync never guesses; one
that fits nothing lists the active courses to pick from (cs30 courses that have not ended, Canvas
courses that are not concluded). Canvas courses are listed with their term and state, so same-named
courses from different semesters can be told apart, and the course id always works.

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
A student whose email cannot match — typically a Canvas account under a personal address — can be
mapped by [student override](#student-overrides) instead.

### Student overrides

Sometimes the email match cannot work: a student's Canvas account (and often their cs30 enrollment
with it) is under a personal address. An override maps a cs30 enrollment email to the student's
Canvas **student id** (the login/SIS id), and `submissions2canvas` then matches that student by id
instead of email. An overridden email is matched *only* through its id — a stale override is
reported as "no Canvas user", never silently ignored.

Overrides live in their own table on the server, so they survive `addcourse` re-importing the
rosters, and the commands run remotely like the other Canvas commands (`--server`/`--token`, or the
same configuration). Listing works with the admin or a TA token. The admin can add or remove any
override; a TA only one for a student enrolled in a section they are the TA of.

#### `addoverride` — map an email to a Canvas student id

Re-running with a corrected id updates the existing override.

| Option | Required | Meaning |
|---|---|---|
| `--email <email>` | yes | cs30 enrollment email |
| `--student-id <id>` | yes | Canvas student id (login or SIS id) |

```bash
java -jar cs30-1.0-SNAPSHOT.jar addoverride --email=student@example.com --student-id=012345678
```

#### `removeoverride` — remove an override

| Option | Required | Meaning |
|---|---|---|
| `--email <email>` | yes | cs30 enrollment email of the override |

```bash
java -jar cs30-1.0-SNAPSHOT.jar removeoverride --email=student@example.com
```

#### `listoverrides` — list the overrides

```bash
java -jar cs30-1.0-SNAPSHOT.jar listoverrides
```
