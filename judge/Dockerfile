FROM python:3.12-slim

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        openjdk-21-jdk-headless \
        ca-certificates \
        time \
    && rm -rf /var/lib/apt/lists/*

# bapctools version is build-time config (NOT runtime — it is baked into this
# image). Override per build: `docker build --build-arg BT_VERSION=X.Y.Z`.
# Bumping it requires a rebuild AND re-testing parser.py
ARG BT_VERSION=2026.4.0
RUN pip install --no-cache-dir bapctools==${BT_VERSION}

RUN useradd --create-home --uid 1000 --shell /bin/bash judge

COPY entry.sh /usr/local/bin/entry.sh
RUN chmod 0755 /usr/local/bin/entry.sh

# Pre-warm bt's default output validator into a baked, read-only cache (D13).
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
