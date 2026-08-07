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
- **Your problem ZIP files** — only if you're adding problems with `addproblem`. The command uploads the ZIP to the server over HTTP; Docker runs on the server, not on your machine. `addproblems` (batch add) still requires Docker and direct repo access on the machine running the command.

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
  --db-url=jdbc:postgresql://localhost:5432/cs30db --db-user=cs30 --db-pass=secret
```

These three apply to every command that touches the database. The rest of the docs leave them out so the examples stay readable — add them if you're not on the server.

## A safe first command

`findstudent` and `findcourse` only read; they change nothing. Use one to confirm your connection works:

```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=you@sjsu.edu
```

If that runs without a connection error, you're set. Next: [set up a course]({% link external/usage.md %}).

> `java -jar cs30-1.0-SNAPSHOT.jar serve ...` starts the web server instead of running a command. That's for whoever operates the server, not for course setup — you won't use `serve`.
