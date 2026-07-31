# kt-judge

The code-execution judge. It compiles and runs one student submission inside a throwaway, hardened Docker
container and returns a verdict. It is a separate service with its own jar and its own port; nothing is
persisted, so the caller is the system of record.

```bash
./gradlew :kt-judge:bootJar                          # -> kt-judge/build/libs/kt-judge.jar
docker build -t judge-sandbox:latest kt-judge/sandbox
java -jar kt-judge/build/libs/kt-judge.jar           # listens on judge.port, default 8000
```

Requires Java 21 and Docker. The problem pool must be readable by both the service user and the container
uid.

Documentation lives in the docs site:

- **Endpoints, status codes, how a job runs, concurrency** —
  <https://cs30.app/internal/architecture/components/>
- **Every `judge.*` setting and its default** — <https://cs30.app/internal/deployment/configuration/>
- **Sizing workers, and the kernel setting interactive problems need** —
  <https://cs30.app/internal/deployment/runbook/>
- **Setting a problem's per-testcase time limit** — <https://cs30.app/external/usage/>
