---
title: Setting up a course
parent: External
nav_order: 3
---

# Setting up a course

This walks through a full term, start to finish: get your problems in, define the course, load it, check it, and manage it while it runs. For the exact options on any command, see the [command reference]({% link external/cli-reference.md %}).

## Two places CS30 keeps things

It helps to know this up front, because it explains which command affects what:

- **The database** holds the course structure — sections, labs, which problems belong to which lab, who's enrolled, and the TA. Commands like `addcourse`, `addstudent`, and `cancellab` change the database.
- **The problem pool** is a git repository of the actual problem content (statement, test data). Commands like `addproblem` and `removeproblem` change the pool, not the database.

A course in the database points at problems *by name*. The name must match a folder in the problem pool. That's the one thing to keep straight: the pool has the problems, the course just references them by name.

## Step 1 — Prepare your problems

Each problem is a folder in the standard ICPC/Kattis format ("problemtools"). At minimum:

```
babyshark/
  problem.yaml            # problem config (name, time limit, etc.)
  data/
    sample/               # sample cases students can see: 1.in, 1.ans, ...
    secret/               # hidden cases used for grading
  submissions/
    accepted/             # at least one correct reference solution
```

The **folder name** (`babyshark`) becomes the problem name you'll reference everywhere.

## Step 2 — Add problems to the pool

Adding a problem renders its statement to HTML (this is the step that needs Docker) and commits it to the problem git repo.

One problem at a time:

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblem \
  --problem-dir=./problems/babyshark \
  --git-repo=/path/to/problems
```

A whole folder of problems at once (each subfolder is one problem):

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblems \
  --problems-dir=./problems \
  --git-repo=/path/to/problems
```

`--git-repo` is the problem pool path — the same path you'll put in the course file as `problemGitRepo`.

## Step 3 — Write the course file

A course is one YAML file. Here's a complete example:

```yaml
code: CS30
year: 2026
semester: Summer
startDate: "2026-07-01"
endDate: "2026-07-31"
studentGitRepo: /path/to/students
problemGitRepo: /path/to/problems
language: Python          # default language for problems that don't set one
sections:
  - number: 1
    ta: ta.section1@sjsu.edu     # optional
    labs:
      - number: 1
        startDateTime: "2026-07-03T10:00:00"
        endDateTime: "2026-07-03T11:15:00"
        problems:
          - name: "babyshark"
          - name: "tenkindsofpeople"
            language: Java        # optional, overrides the course default
            note: "Extra credit"  # optional
    students:
      - jane.smith@sjsu.edu
      - john.doe@sjsu.edu
```

What the fields mean:

- `code`, `year`, `semester` — together these identify the course. You'll pass them to most other commands.
- `startDate`, `endDate` — `yyyy-MM-dd`.
- `studentGitRepo`, `problemGitRepo` — filesystem paths to the two git repos. Optional, but you'll almost always set them. If set, the tool initializes the repos and drops a copy of the course file into the student repo.
- `language` — the default problem language. A problem with no `language` of its own uses this.
- `sections[]` — each has a `number`, an optional `ta`, a list of `labs`, and a list of `students` (emails).
- `labs[]` — each has a `number`, a `startDateTime` and `endDateTime` (`yyyy-MM-ddTHH:mm:ss`), and a list of `problems`.
- `problems[]` — each has a `name` (must match a folder in the problem pool), and optional `language` and `note`.

A ready-to-copy template is in the repo at `templates/courseTemplate.yml`.

## Step 4 — Load the course

```bash
java -jar cs30-1.0-SNAPSHOT.jar addcourse --course-file=./course.yaml
```

This creates every section, lab, problem, and enrollment from the file. It's safe to re-run: a section that already exists (same code, year, semester, section) is updated rather than duplicated, so you can edit the file and load it again.

## Step 5 — Check it before the lab

Confirm every problem your course references actually exists in the pool:

```bash
java -jar cs30-1.0-SNAPSHOT.jar validatecourse \
  --course-code=CS30 --year=2026 --semester=Summer --section=all
```

It prints a ✓ or ✗ per problem and lists anything missing. Do this before a lab opens — a missing problem means students can't open it.

You can also see what got loaded:

```bash
java -jar cs30-1.0-SNAPSHOT.jar findcourse \
  --course-code=CS30 --year=2026 --semester=Summer --section=all
```

## Managing the course while it runs

**Add a student who enrolled late:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar addstudent \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=late@sjsu.edu
```

**Remove a student:** same options with `removestudent`.

**Find which sections a student is in:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=jane.smith@sjsu.edu
```

**Set or change a section's TA:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar setta \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=ta@sjsu.edu
```

**Add another lab later** — write a small lab file (`templates/labTemplate.yml` is the template) and load it:
```bash
java -jar cs30-1.0-SNAPSHOT.jar addlab --lab-file=./lab.yml
```

**Extend the course end date** (one section or `all`):
```bash
java -jar cs30-1.0-SNAPSHOT.jar changeenddate \
  --course-code=CS30 --year=2026 --semester=Summer --section=all --end-date=2026-08-15
```

**Cancel a lab** (removes the lab and its problems from the course; the problem pool is untouched):
```bash
java -jar cs30-1.0-SNAPSHOT.jar cancellab \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --lab=4
```

**Fix a problem's language in one lab** (database only — doesn't change the pool):
```bash
java -jar cs30-1.0-SNAPSHOT.jar updateproblemlanguage \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --lab=1 \
  --problem-name=babyshark --language=java
```

## After the lab: push it to Canvas

Two commands move a finished lab into Canvas. Both default to a dry run that prints what they would
do, so you can look before anything changes. Add `--no-dryrun` to apply.

First set the token, which the commands read from the environment:

```bash
export CANVAS_TOKEN='12~...'   # Canvas: Account > Settings > New Access Token
```

**Create one assignment per problem in the lab:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar course2canvas \
  --cs30-course-code=CS30 --cs30-year=2026 --cs30-semester=Summer \
  --cs30-section=1 --cs30-lab=1 --canvas-course=12345 --rubric="Lab Rubric"
```

**Then post each student's best submission as a comment:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar submissions2canvas \
  --cs30-course-code=CS30 --cs30-year=2026 --cs30-semester=Summer \
  --cs30-section=1 --cs30-lab=1 --canvas-course=12345
```

Run them in that order, since the second finds the assignments the first created. If your
assignments already exist in Canvas, skip the first command: the second matches them by name.

Assignments are named `LAB<lab number>`, plus the first word of the problem's note when it has one,
so a bonus problem lands on `LAB1-Bonus` and the plain one on `LAB1`. Give each problem in a lab a
distinct note; only one may be left without one.

The options that name the cs30 course are prefixed `--cs30-` and the Canvas ones `--canvas-`, so the
two courses are never mixed up.

No grades are entered. Each assignment is worth 100 points and the comment reports how many test
cases the student passed, so you decide the grade. Re-running is safe: a student whose submission was
already posted is skipped.

Full details and every option are in the [command reference]({% link external/cli-reference.md %}).
