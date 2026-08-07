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

### Set the time limit

`problem.yaml` carries the per-testcase time limit. This is the limit that decides AC vs TLE for each case, and it is the problem's to set — the judge passes no time flag of its own. `bt` (bapctools) reads it from the package inside the sandbox.

```yaml
limits:
  time_limit: 8        # seconds; floats allowed, e.g. 2.5
```

Do not hand-pick the number. Let `bt` measure your accepted solutions and derive it:

```bash
cd babyshark
bt time_limit     # measures the accepted solutions and writes a limit with margin
bt validate       # warns if time_limit is missing, plus other package problems
```

If `time_limit` is missing, `bt` falls back to 1 second. A submission is killed at roughly 2x the limit, so an unset problem starts failing around 2s.

**A missing `time_limit`, or a package built with a different `bt` version, is the usual reason a known-correct solution grades `TLE` or a problem comes back `0/0`.** Set `limits.time_limit` and regenerate the package with a matching `bt`.

The limit is per language only in the sense that it applies equally to all of them: a limit your C++ reference solution clears comfortably may be out of reach for the Java or Python one. Assign a problem in a language whose own accepted solution passes — see `language` in the course file, [Step 3](#step-3--write-the-course-file).

## Step 2 — Add problems to the pool

Adding a problem renders its statement to HTML (this is the step that needs Docker) and commits it to the problem git repo.

One problem at a time:

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblem \
  --problem-dir=./problems/babyshark \
  --git-repo=/path/to/problems --token=<your-token>
```

A whole folder of problems at once (each subfolder is one problem):

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblems \
  --problems-dir=./problems \
  --git-repo=/path/to/problems --token=<your-token>
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
java -jar cs30-1.0-SNAPSHOT.jar addcourse --course-file=./course.yaml --token=<your-token>
```

This creates every section, lab, problem, and enrollment from the file. It's safe to re-run: a section that already exists (same code, year, semester, section) is updated rather than duplicated, so you can edit the file and load it again.

## Step 5 — Check it before the lab

Confirm every problem your course references actually exists in the pool:

```bash
java -jar cs30-1.0-SNAPSHOT.jar validatecourse \
  --course-code=CS30 --year=2026 --semester=Summer --section=all --token=<your-token>
```

It prints a ✓ or ✗ per problem and lists anything missing. Do this before a lab opens — a missing problem means students can't open it.

You can also see what got loaded:

```bash
java -jar cs30-1.0-SNAPSHOT.jar findcourse \
  --course-code=CS30 --year=2026 --semester=Summer --section=all --token=<your-token>
```

## Managing the course while it runs

**Add a student who enrolled late:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar addstudent \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=late@sjsu.edu --token=<your-token>
```

**Remove a student:** same options with `removestudent`.

**Find which sections a student is in:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=jane.smith@sjsu.edu --token=<your-token>
```

**Set or change a section's TA:**
```bash
java -jar cs30-1.0-SNAPSHOT.jar setta \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=ta@sjsu.edu --token=<your-token>
```

**Add another lab later** — write a small lab file (`templates/labTemplate.yml` is the template) and load it:
```bash
java -jar cs30-1.0-SNAPSHOT.jar addlab --lab-file=./lab.yml --token=<your-token>
```

**Extend the course end date** (one section or `all`):
```bash
java -jar cs30-1.0-SNAPSHOT.jar changeenddate \
  --course-code=CS30 --year=2026 --semester=Summer --section=all --end-date=2026-08-15 --token=<your-token>
```

**Cancel a lab** (removes the lab and its problems from the course; the problem pool is untouched):
```bash
java -jar cs30-1.0-SNAPSHOT.jar cancellab \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --lab=4 --token=<your-token>
```

**Fix a problem's language in one lab** (database only — doesn't change the pool):
```bash
java -jar cs30-1.0-SNAPSHOT.jar updateproblemlanguage \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --lab=1 \
  --problem-name=babyshark --language=java --token=<your-token>
```

Full details and every option are in the [command reference]({% link external/cli-reference.md %}).
