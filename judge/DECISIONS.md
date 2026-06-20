# Judge Decisions

Design decisions for the judge service. Decisions **D1–D12** are referenced inline
in the code comments (their rationale predates this file); new decisions are
recorded here.

---

## D13 — Cache the compiled validators per problem (judge execution latency)

### Context / problem
A single `/run` or `/submit` took ~30s wall-clock, far too slow for interactive use.

Measured breakdown (babyshark, python accepted solution, on the judge host):

| phase | time |
|---|---|
| container cold-start (`docker run`) | ~0.4s |
| `bt run` **cold** (compiles validators) | ~16.9s |
| `bt run` **warm** (validators cached) | ~2.0s |

So the dominant cost is **compiling the default output validator** (~15s), paid on
*every* request. (Measured: removing the input validator entirely changed nothing —
the input validator is negligible; the **output** validator is the cost.)

### Root cause
`bt` compiles validators on first use and caches the linked binaries in its
tmpdir, `$TMPDIR/bapctools_<hash>/` (where `<hash>` is derived from the problem's
**path**). Our hardened sandbox gives each run a fresh **tmpfs `/tmp`**, so that
cache is empty every run → recompile (~15s) every time.

### Decision
Persist `bt`'s compiled **default output validator** across runs by pointing
`TMPDIR` at a persistent, pre-warmed dir mounted into the container, so the ~15s
compile happens once instead of every run.

- `bt`'s cache key `bapctools_<hash>` is derived from the in-container problem
  **path**, and every problem stages to the same `/work/problem`, so the key is
  identical across problems. The default output validator's source is also
  identical across (default/pass-fail) problems → **one global cache serves them
  all**; no per-problem cache is needed.
- The input validator is negligible (see Root cause), so it harmlessly recompiles
  per run even when it differs — it doesn't justify per-problem caches.
- **Custom** output validators (problem-specific source) won't share globally and
  would recompile; they're the minority. Per-problem caching can be added for them
  later if needed.

Proven:
- a fresh container with a pre-populated cache: **1.9s vs 15.7s** (~8×);
- a **different** problem reusing the **same shared cache**: **1.9s** (confirms
  global reuse);
- through the real judge, a second `/run` dropped ~30s → ~16s (residual is the
  multi-`bt`-call overhead, below).

### Alternatives rejected
- **ccache (a shared compiler cache).** We tried it: no speedup, 0 cache hits.
  ccache only caches a plain compile step (source → object, `g++ -c`), but `bt`
  builds the validator in one combined **compile+link** of multiple files, which
  ccache treats as uncacheable — so it never kicks in. (This also rules out making
  the default output validator "global" via ccache.)
- **Bake a *standalone* validator binary into the image.** Doesn't work: `bt`
  only looks in its own tmpdir cache, not at an arbitrary path. (But baking `bt`'s
  *own cache* — its `bapctools_<hash>` dir, pre-warmed at `/work/problem` — **does**
  work, and is the recommended global form: read-only → copy into tmpfs per run.)
- **Warm container pool / long-lived compile server.** Would amortize compile
  cost but conflicts with the one-ephemeral-hardened-container-per-run model
  (D3/D12); not pursued.

### Security constraint (important)
The cache holds the compiled validator, which is *trusted* — but it must **not**
be a writable cache shared with the untrusted container. Student code runs as
uid 1000 with a writable `TMPDIR` and could overwrite the cached validator binary,
poisoning grading for the next submission. This matters **more** for a global
cache, since one poisoned validator would taint every problem. Therefore the
production form is:

1. **Pre-warm once (trusted):** compile the default output validator with a problem
   staged at `/work/problem` (so the hash matches runtime), or bake it into the image.
2. Per run, mount the pre-warmed cache **read-only**, **copy it into the run's
   tmpfs**, and point `TMPDIR` at the writable copy. Student code can only poison
   its own ephemeral copy.

### Status
- **Dev (current):** `runner._invoke` mounts a *writable* per-problem cache at
  `/var/tmp/judge-cache/<problem>` with `TMPDIR=/cache`. Fast, but **unsafe for
  grading** (poisoning) — marked `DEV ONLY` in code.
  (Per-problem here is just the dev shortcut; the production cache is global.)
- **Production (pending):** a global pre-warmed cache (config-driven path, or baked
  into the image) + read-only-mount + copy-into-tmpfs staging in the runner.

### Related future work (separate from the cache)
- The residual ~16s/run is **multiple `bt` invocations** per request (one `bt run`
  for verdicts + one `bt test` per case for output), each ~2s. Reducing the number
  of `bt` calls is a separate optimization.
- **Skipping input validation doesn't help speed.** The input validator compile is
  negligible (the output validator is the cost), so `--no-generate
  --no-testcase-sanity-checks` gives no measurable gain — not worth adding.
