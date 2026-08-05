# Language versions the sandbox uses. CI builds with no --build-arg, so these
# defaults are what ships. To try another version locally:
#   docker build --build-arg JAVA_VERSION=17 .
#
#   PYTHON_VERSION  Python interpreter, and the Debian base image
#   JAVA_VERSION    OpenJDK version (java + javac)
#   CPP_STD/C_STD   C++/C standard to compile with
#   BT_VERSION      bapctools version. If you bump it, re-test parser.py.
#   BT_PIPE_BUFFER_BYTES  pipe buffer bt requests per interactive channel
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
ARG BT_VERSION=2026.4.0
ARG BT_PIPE_BUFFER_BYTES=262144

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

# Pin the C/C++ standards the sandbox compiles with (CPP_STD / C_STD, above).
# bapctools' built-in languages.yaml defaults to -std=gnu++20 (C++) and -std=gnu11
# (C); we rewrite those to the configured standards here, once, at the image
# level, so every grade uses the same standard without a per-problem config. The
# grep guard fails the build if bt's C++ default is no longer gnu++20 (e.g. a
# BT_VERSION bump moved it), so the override can't silently target the wrong line.
RUN set -e; \
    CFG="$(python3 -c 'import bapctools, os; print(os.path.join(os.path.dirname(bapctools.__file__), "resources/config/languages.yaml"))')"; \
    grep -q -- '-std=gnu++20' "$CFG" || { echo "ERROR: expected -std=gnu++20 in bt languages.yaml ($CFG) — bt default changed, re-check CPP_STD override"; exit 1; }; \
    sed -i "s/-std=gnu++20/-std=${CPP_STD}/g" "$CFG"; \
    sed -i "s/-std=gnu11/-std=${C_STD}/g" "$CFG"; \
    grep -q -- "-std=${CPP_STD}" "$CFG" || { echo "ERROR: failed to set C++ std to ${CPP_STD}"; exit 1; }

# Cap the pipe buffer bt requests per interactive channel (BT_PIPE_BUFFER_BYTES, above).
#
# bapctools asks subprocess.Popen for a 1 MiB pipe per interactive channel (interactive.py's
# BUFFER_SIZE). Python turns that into fcntl(F_SETPIPE_SZ), which the kernel refuses with EPERM once
# this *uid's* total pipe pages exceed fs.pipe-user-pages-soft. That budget is shared across every
# sandbox running at once, because they all run as the same uid with no CAP_SYS_RESOURCE — so the
# limit is reached by concurrency, not by one grading.
#
# bapctools does not catch it. Depending on which Popen call trips, bt either aborts with an uncaught
# PermissionError, or hangs: its cleanup waits on child processes that are already deadlocked, so the
# exception never even surfaces. Either way the cases it had graded are discarded.
#
# 1 MiB is 256 pages, there are 4 pipes per interactive test, and the kernel default is 16384 pages:
# 16 concurrent gradings x 4 x 256 = 16384, exactly the limit. 256 KiB is 64 pages, leaving 4x
# headroom without needing an fs.pipe-user-pages-soft override on the host — which would be lost
# whenever the server is rebuilt.
#
# The grep guard fails the build if bt's declaration no longer looks the way this expects (e.g. a
# BT_VERSION bump renamed it or made it dynamic), so the override cannot silently do nothing.
RUN set -e; \
    F="$(python3 -c 'import bapctools, os; print(os.path.join(os.path.dirname(bapctools.__file__), "interactive.py"))')"; \
    grep -qE '^BUFFER_SIZE.*2\*\*20' "$F" \
      || { echo "ERROR: expected 'BUFFER_SIZE ... 2**20' in $F — bt changed, re-check BT_PIPE_BUFFER_BYTES"; exit 1; }; \
    sed -i "s/^\(BUFFER_SIZE.*=\).*\$/\1 ${BT_PIPE_BUFFER_BYTES}/" "$F"; \
    grep -qE "^BUFFER_SIZE.*${BT_PIPE_BUFFER_BYTES}\$" "$F" \
      || { echo "ERROR: failed to set BUFFER_SIZE to ${BT_PIPE_BUFFER_BYTES} in $F"; exit 1; }

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
