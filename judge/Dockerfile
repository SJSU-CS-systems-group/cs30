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
# Bumping it requires a rebuild AND re-testing parser.py (output format, D8/S16).
ARG BT_VERSION=2026.4.0
RUN pip install --no-cache-dir bapctools==${BT_VERSION}

RUN useradd --create-home --uid 1000 --shell /bin/bash judge

COPY entry.sh /usr/local/bin/entry.sh
RUN chmod 0755 /usr/local/bin/entry.sh

USER 1000:1000
WORKDIR /work

ENTRYPOINT ["/usr/local/bin/entry.sh"]
