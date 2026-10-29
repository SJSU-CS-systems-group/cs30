# Language versions the sandbox uses. CI builds with no --build-arg, so these
# defaults are what ships. To try another version locally:
#   docker build --build-arg JAVA_VERSION=17 .
#
#   PYTHON_VERSION  Python interpreter, and the Debian base image
#   JAVA_VERSION    OpenJDK version (java + javac)
#   CPP_STD/C_STD   C++/C standard to compile with
#   WARN_FLAGS      compiler warnings that are errors, not warnings
#   BT_VERSION      bapctools version. If you bump it, re-test parser.py.
#
# There is no setting for gcc/g++. The compiler comes with the base image, so
# changing PYTHON_VERSION can change the C/C++ compiler too. CPP_STD/C_STD only
# pick the language standard that compiler is told to use.
ARG PYTHON_VERSION=3.12
FROM python:${PYTHON_VERSION}-slim

# An ARG used after FROM has to be declared again here.
ARG JAVA_VERSION=21
ARG CPP_STD=gnu++23
ARG C_STD=gnu23
ARG WARN_FLAGS="-Werror=return-type -Werror=shadow"
ARG BT_VERSION=2026.4.0

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        openjdk-${JAVA_VERSION}-jdk-headless \
        ca-certificates \
        time \
    && rm -rf /var/lib/apt/lists/*

# bapctools (BT_VERSION, above) is baked into the image, not a runtime dependency.
RUN pip install --no-cache-dir bapctools==${BT_VERSION}

# Pin the C/C++ standards the sandbox compiles with (CPP_STD / C_STD) and the
# warnings that are promoted to errors (WARN_FLAGS), above. bapctools' built-in
# languages.yaml defaults to -std=gnu++20 (C++) and -std=gnu11 (C); we rewrite
# those to the configured standards and append the flags here, once, at the image
# level, so every grade compiles the same way without a per-problem config. The
# grep guards fail the build if bt's C++ default is no longer gnu++20 (e.g. a
# BT_VERSION bump moved it), so the override can't silently target the wrong line.
# -Wshadow also fires on bt's own default output validator, which shadows a member
# with a constructor parameter. That is bt's code and not a student mistake, so the
# validator source is exempted with a pragma. The pre-warm step below compiles it,
# so if that exemption ever stops working the image build fails there.
RUN set -e; \
    CFG="$(python3 -c 'import bapctools, os; print(os.path.join(os.path.dirname(bapctools.__file__), "resources/config/languages.yaml"))')"; \
    grep -q -- '-std=gnu++20' "$CFG" || { echo "ERROR: expected -std=gnu++20 in bt languages.yaml ($CFG) — bt default changed, re-check CPP_STD override"; exit 1; }; \
    sed -i "s/-std=gnu++20/-std=${CPP_STD} ${WARN_FLAGS}/g" "$CFG"; \
    sed -i "s/-std=gnu11/-std=${C_STD} ${WARN_FLAGS}/g" "$CFG"; \
    grep -q -- "-std=${CPP_STD} ${WARN_FLAGS}" "$CFG" || { echo "ERROR: failed to set C++ flags to ${CPP_STD} ${WARN_FLAGS}"; exit 1; }; \
    grep -q -- "-std=${C_STD} ${WARN_FLAGS}" "$CFG" || { echo "ERROR: failed to set C flags to ${C_STD} ${WARN_FLAGS}"; exit 1; }; \
    VAL="$(dirname "$CFG")/../support/default_output_validator.cpp"; \
    sed -i '1i #pragma GCC diagnostic ignored "-Wshadow"' "$VAL"

# File I/O: start every C, C++, Python and Java program through cs30-fileio, which,
# for a problem with a file_io.yaml, runs each test case in its own folder with its
# starting files and grades the files the program writes; otherwise it execs the
# program untouched. Java goes through a symlink named `java`: bt skips its memory
# rlimit for a command named java (the JVM cannot start under one), so the wrapper
# must keep that name. The guards fail the build if a bt version bump changes these
# run lines, or if a tool the wrapper needs is missing.
COPY cs30-fileio /usr/local/bin/cs30-fileio
RUN set -e; \
    chmod 0755 /usr/local/bin/cs30-fileio; \
    mkdir -p /opt/cs30/java; \
    ln -s /usr/local/bin/cs30-fileio /opt/cs30/java/java; \
    CFG="$(python3 -c 'import bapctools, os; print(os.path.join(os.path.dirname(bapctools.__file__), "resources/config/languages.yaml"))')"; \
    for line in "run: '{binary}'" "run: 'python3 {mainfile}'" "run: 'java "; do \
        grep -qF -- "$line" "$CFG" || { echo "ERROR: expected \"$line\" in bt languages.yaml ($CFG) — re-check the cs30-fileio patch"; exit 1; }; \
    done; \
    sed -i \
        -e "s|run: '{binary}|run: 'cs30-fileio {binary}|" \
        -e "s|run: 'python3 {mainfile}'|run: 'cs30-fileio python3 {mainfile}'|" \
        -e "s|run: 'java |run: '/opt/cs30/java/java java |" \
        "$CFG"; \
    for line in "run: 'cs30-fileio {binary}'" "run: 'cs30-fileio python3 {mainfile}'" "run: '/opt/cs30/java/java java "; do \
        grep -qF -- "$line" "$CFG" || { echo "ERROR: cs30-fileio patch did not apply: $line"; exit 1; }; \
    done; \
    setpriv --pdeathsig KILL -- true || { echo "ERROR: setpriv --pdeathsig unavailable; cs30-fileio needs it to kill timed-out programs"; exit 1; }; \
    for tool in awk cp grep ls readlink sed sort tr; do \
        command -v "$tool" > /dev/null || { echo "ERROR: cs30-fileio needs $tool"; exit 1; }; \
    done

RUN useradd --create-home --uid 1000 --shell /bin/bash judge

COPY entry.sh /usr/local/bin/entry.sh
RUN chmod 0755 /usr/local/bin/entry.sh

# Pre-warm bt's default output validator into a baked, read-only cache.
# bt's cache is tied to the TMPDIR path it was built at, so warm at the same path
# the runtime uses (/work/btcache); the orchestrator restores the cache there.
COPY seed-problem /opt/seed-problem
RUN mkdir -p /opt/bt-cache-seed /work \
    && chown -R 1000:1000 /opt/bt-cache-seed /opt/seed-problem /work

USER 1000:1000
RUN set -e; export TMPDIR=/work/btcache; \
    mkdir -p /work/btcache /work/problem; \
    cp -r /opt/seed-problem/. /work/problem; \
    cd /work/problem; \
    bt run -ve --no-bar submissions/accepted/sol.py >/dev/null 2>&1 || true; \
    cp -a /work/btcache/. /opt/bt-cache-seed/; \
    find /opt/bt-cache-seed -path '*output_validator*' -type f -executable | grep -q . \
      || { echo 'ERROR: output validator not baked'; ls -R /opt/bt-cache-seed; exit 1; }; \
    rm -rf /work/btcache /work/problem

WORKDIR /work

ENTRYPOINT ["/usr/local/bin/entry.sh"]
