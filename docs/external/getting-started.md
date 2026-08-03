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
- **Docker and your problem files** — only if you're adding problems. Turning a problem into a viewable statement uses Docker, so the problem commands have to run on a machine that has it, with access to the problem git repo.

## Running a command

```bash
java -jar cs30-1.0-SNAPSHOT.jar <command> [options]
```

Every command has `--help`:

```bash
java -jar cs30-1.0-SNAPSHOT.jar --help
java -jar cs30-1.0-SNAPSHOT.jar addcourse --help
```

## Setting up

On a machine that has never run the tool, start here — it asks for what the tool needs, checks each answer, and writes the configuration file for you:

```bash
java -jar cs30-1.0-SNAPSHOT.jar doctor
```

It only asks about what isn't configured yet; `--reconfigure` asks about everything. `--check` reports on the setup without asking for anything, which is the quickest way to find out why a command isn't working. Every command also prints which configuration file it read on startup. The rest of this page describes what it configures, in case you'd rather do it by hand.

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

If you use the same connection over and over, put it in a properties file instead and point `--config` at the file:

```properties
# cs30.properties
spring.datasource.url=jdbc:postgresql://localhost:5432/cs30db
spring.datasource.username=cs30
spring.datasource.password=secret
```

```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=jane@sjsu.edu --config=cs30.properties
```

The file is added to the tool's configuration before it starts. `--db-url`, `--db-user`, and `--db-pass` win over anything the file sets, so you can keep the file for the common case and override a single value on the command line.

Better still, name the file `cs30.properties` and put it in the standard configuration directory for your machine — then you don't pass anything at all:

| | user | machine |
|---|---|---|
| **Linux** | `~/.config/cs30.properties` | `/etc/cs30.properties` |
| **macOS** | `~/Library/Application Support/cs30.properties` | `/Library/Application Support/cs30.properties` |
| **Windows** | `%APPDATA%\cs30.properties` | `%ProgramData%\cs30.properties` |

The first one that exists is used, and `--config` overrides it. On Linux, `XDG_CONFIG_HOME` is honored if you set it.

## A safe first command

`findstudent` and `findcourse` only read; they change nothing. Use one to confirm your connection works:

```bash
java -jar cs30-1.0-SNAPSHOT.jar findstudent --email=you@sjsu.edu
```

If that runs without a connection error, you're set. Next: [set up a course]({% link external/usage.md %}).

> `java -jar cs30-1.0-SNAPSHOT.jar serve ...` starts the web server instead of running a command. That's for whoever operates the server, not for course setup — you won't use `serve`.
