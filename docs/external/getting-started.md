---
title: Getting started
parent: External
nav_order: 2
---

# Getting started

The `cs30` tool is a single Java program you run from a terminal to manage your courses.

## What you need

- **Java 21.** Check with `java -version`.
- **The `cs30` jar.** Get it from the project's releases, or build it from source:
  ```bash
  ./gradlew :cli:bootJar
  # produces cli/build/libs/cs30-1.0-SNAPSHOT.jar
  ```
- **Access to the CS30 database.** The tool writes courses, students, and labs into the same database the server uses. Either run the tool on the server (where that connection is already configured) or pass the connection on the command line — see below.
- **A CLI token.** Every command except `serve`, `doctor`, `--help`, and `--version` requires one — see below.
- **Docker and your problem files** — only if you're adding problems. Turning a problem into a viewable statement uses Docker, so the problem commands have to run on a machine that has it, with access to the problem git repo.

## Getting a token

Log into the admin dashboard (`/admin`) or TA dashboard (`/ta`) on the web app with your Google
account. Your CLI token is shown once, right after your first login there (or after you use the
dashboard's "Reset Token" button) — copy it down immediately, since only a salted hash is stored
server-side and it can't be shown again after that.

Pass it on the command line with `--token`, or set it once as an environment variable so you don't
have to repeat it:

```bash
export CS30_ADMIN_TOKEN=<your-token>
```

`--token` on the command line always wins if both are set. An admin's token can run every command;
a TA's token is blocked from roster/course administration commands (`addcourse`, `addstudent`,
`removecourse`, `removestudent`, `changeenddate`, `setta`, `removeta`) — those need an admin token.

## Running a command

```bash
java -jar cs30-1.0-SNAPSHOT.jar <command> [options]
```

Every command has `--help`:

```bash
java -jar cs30-1.0-SNAPSHOT.jar --help
java -jar cs30-1.0-SNAPSHOT.jar addcourse --help
```

## Connecting to the database

Most commands read or write the database. If you run the tool **on the server**, the connection is already set in the server's config — you don't pass anything. If you run it **anywhere else**, give it the connection with three options:

```
--db-url   jdbc:postgresql://<host>:5432/cs30db
--db-user  <user>
--db-pass  <password>
```

For example:

```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=jane@sjsu.edu \
  --db-url=jdbc:postgresql://localhost:5432/cs30db --db-user=cs30 --db-pass=secret --token=<your-token>
```

These three apply to every command that touches the database. The rest of the docs leave them out so the examples stay readable — add them if you're not on the server.

## A safe first command

`findstudent` and `findcourse` only read; they change nothing. Use one to confirm your connection and token both work:

```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=you@sjsu.edu --token=<your-token>
```

If that runs without a connection or token error, you're set. Next: [set up a course]({% link external/usage.md %}).

> `java -jar cs30-1.0-SNAPSHOT.jar serve ...` starts the web server instead of running a command. That's for whoever operates the server, not for course setup — you won't use `serve`.
