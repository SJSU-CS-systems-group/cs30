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

### Reading and writing files (optional)

By default a program reads each test case from standard input and is graded on what it prints. For a problem that teaches files or command-line arguments, write the problem as usual (the same format as any other problem: `problem.yaml`, `problem_statement/`, `data/`, `submissions/`), then add a `file_io.yaml` next to `problem.yaml` (it can be empty) and give each test case any of these, next to its `N.in`:

| File | What it is |
|---|---|
| `N.yaml` with `args: [...]` | The program's command-line arguments |
| `N.files/` | Starting files, copied next to the program before it runs. Any number, any names |
| `N.expected/` | The files the program must create, with their correct contents. Any number. A file named `stdout` here means "and what it printed" |

`N.in` must still exist (it can be empty); it is the program's standard input. Give either `N.expected/` or `N.ans`, not both: with `N.expected/` the program is graded on the files it creates, without it on what it prints, as usual.

For example, "sum the numbers in the file named on the command line and write the total to `<name>.sum`":

```
sumlst/
  problem.yaml                          # name: Sum the list
  .timelimit                            # 2   (seconds)
  problem_statement/problem.tex         # the description students read
  file_io.yaml                          # empty
  data/sample/1.in                      # empty
  data/sample/1.yaml                    # args: ["file1.lst"]
  data/sample/1.files/file1.lst         # 3 4 5
  data/sample/1.expected/file1.lst.sum  # 12
  data/secret/1.in                      # empty
  data/secret/1.yaml                    # args: ["file2.lst"]
  data/secret/1.files/file1.lst
  data/secret/1.files/file2.lst
  data/secret/1.expected/file2.lst.sum  # 30
  submissions/accepted/sum.py           # a correct solution
```

Say in the statement which files and arguments the program gets and which files it must create: students cannot type their own input for these problems, so the statement and the samples are all they have. The statement's sample table shows each sample's arguments, starting files and expected files, under the same `=== name ===` headings students see after **Run**.

Each output file is compared in order, the same way printed output is. When order does not matter, say so per file in `file_io.yaml`:

```yaml
compare:
  "*":     {order: strict}                   # the default for every file
  odd.txt: {order: any}                      # lines in any order
  out.txt: {order: any_words, unique: true}  # words in any order, repeats ignored
```

`order` is `strict`, `any` (lines) or `any_words` (words, even on one line); `unique: true` needs one of the `any` orders.

Capitals, extra spaces and decimal tolerance are set the usual way, in `data/testdata.yaml`. **Capitals are ignored unless you ask for them**, so a problem where case matters needs:

```yaml
# data/testdata.yaml
output_validator_flags: case_sensitive
```

What students see after **Run** is each sample's arguments and starting files, the expected files, and the files their program created, each under a `=== name ===` heading. A file the program did not create shows as `=== name (missing) ===` and the case gets `WA`. Anything the program printed that is not graded shows as debug output. The custom-input box is hidden for these problems, since it can only supply standard input.

File names must be plain (letters, digits, `.`, `_`, `-`, no folders). `addproblem` and `addproblems` refuse a problem that breaks these rules, and say why. Plain `bt` on your own machine does not understand `file_io.yaml`, so test these problems through the judge.

## Step 2 — Add problems to the pool

Adding a problem renders its statement to HTML (using Docker on the server) and commits it to the problem
git repo.

**One problem at a time — upload a ZIP to the server:**

Get a TA token first by visiting `/ta/login` in a browser. The token appears in the redirect URL as
`api_token=<token>`.

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblem \
  --problem-zip=./problems/babyshark.zip \
  --course-code=CS-200 --year=2026 --semester=Fall \
  --token=<ta-token>
```

The ZIP must contain exactly one top-level directory (the problem name). Docker runs on the server — you
do not need Docker installed locally.

**A whole folder of problems at once** (each subfolder is one problem — runs on the server, needs Docker
and direct repo access):

```bash
java -jar cs30-1.0-SNAPSHOT.jar addproblems \
  --problems-dir=./problems \
  --git-repo=/path/to/problems
```

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
    tas:                         # optional, one or more
      - ta.section1@sjsu.edu
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

**Add a TA to a section** (a section may have several; run it once per TA):
```bash
java -jar cs30-1.0-SNAPSHOT.jar addta \
  --course-code=CS30 --year=2026 --semester=Summer --section=1 --email=ta@sjsu.edu
```

**Remove one TA:** same options with `removeta`, naming the TA to remove with `--email`.

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

They run from any machine that can reach the server, reading the course through it rather than the
database. First say where the server is and which CLI token to use, and set the Canvas token; the
commands read all three from the environment:

```bash
export CS30_BACKEND_URL='https://sjsu.cs30.app'   # or pass --server; also cs30.backend.url in cs30.properties
export CS30_ADMIN_TOKEN='...'                     # your CLI token; or pass --token
export CANVAS_TOKEN='12~...'                      # Canvas: Account > Settings > New Access Token
```

The admin token covers every course; a TA's token covers only the section that TA is assigned to.

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

Assignments are named `LAB` plus the lab number padded to two digits, then the first word of the
problem's note when it has one, so a bonus problem lands on `LAB01-Bonus` and the plain one on
`LAB01`. Give each problem in a lab a distinct note; only one may be left without one.

The options that name the cs30 course are prefixed `--cs30-` and the Canvas ones `--canvas-`, so the
two courses are never mixed up. For `submissions2canvas` both can be fragments, as in
`--cs30-course-code=cs30 --canvas-course="cs 30"`: the year, semester and section are only needed
when the code fits more than one course, a fragment that fits several courses is an error listing
them, and one that fits none lists the active courses to choose from.

No grades are entered. Each assignment is worth 100 points and the comment reports how many test
cases the student passed, so you decide the grade. Re-running is safe: a student whose submission was
already posted is skipped.

Full details and every option are in the [command reference]({% link external/cli-reference.md %}).
