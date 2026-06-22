from __future__ import annotations
import json
import subprocess
import tempfile
from contextlib import ExitStack
from pathlib import Path

from .config import get_config
from .models import RawRun, RunCase, RunResult, Status, SubmitCase, SubmitResult, Verdict
from .parser import clean_compile_output, is_memory_error, parse_run_output, strip_bt_noise


# Overall verdict precedence (worst first) for recomputing after MLE relabeling.
_ORDER = ["CE", "JE", "RTE", "MLE", "TLE", "WA", "AC"]


def _worst(statuses: list[str]) -> str:
    return min(statuses, key=lambda s: _ORDER.index(s) if s in _ORDER else len(_ORDER)) if statuses else "AC"


def _docker_flags() -> list[str]:
    """Hardened `docker run` flags built from the loaded config."""
    s = get_config().sandbox
    return [
        "--rm",
        "--network=none",
        "--cap-drop=ALL",
        "--security-opt=no-new-privileges",
        "--read-only",
        "--tmpfs", f"/work:rw,exec,size={s.work_tmpfs_mb}m,nr_inodes=16384,uid={s.uid},gid={s.gid}",
        "--tmpfs", f"/tmp:rw,exec,size={s.tmp_tmpfs_mb}m,nr_inodes=4096,uid={s.uid},gid={s.gid}",
        f"--pids-limit={s.pids_limit}",
        f"--memory={s.memory_mb}m", f"--memory-swap={s.memory_mb}m",
        f"--cpus={s.cpus}",
        "--ulimit", f"fsize={s.fsize_bytes}",
        "-u", f"{s.uid}:{s.gid}",
    ]


def _invoke(
    problem_dir: Path,
    mounts: list[tuple[Path, str]],
    bt_args: list[str],
    wall_timeout: int,
    entrypoint: str | None = None,
) -> subprocess.CompletedProcess:
    cmd: list[str] = ["docker", "run", *_docker_flags()]
    if entrypoint is not None:
        cmd += ["--entrypoint", entrypoint]
    cmd += ["-v", f"{problem_dir.resolve()}:/problem:ro"]
    for host, container in mounts:
        cmd += ["-v", f"{host.resolve()}:{container}:ro"]
    cmd += [get_config().image, *bt_args]
    # errors="replace": a program emitting non-UTF-8 bytes degrades to U+FFFD
    # instead of crashing the capture, so output can be returned as plain text.
    return subprocess.run(
        cmd, capture_output=True, text=True, errors="replace", timeout=wall_timeout
    )


def run_all(problem_dir: Path, code_path: Path, *, wall_timeout: int | None = None) -> Verdict:
    if wall_timeout is None:
        wall_timeout = get_config().timeouts.run_all_wall_seconds
    sub_name = code_path.name
    mounts = [(code_path, f"/in/{sub_name}")]
    # -v emits per-testcase lines (including ACs) so total/passed counts are accurate;
    # -e expands failure detail (Got/wanted snippet).
    proc = _invoke(problem_dir, mounts, ["run", "-ve", sub_name], wall_timeout)
    return parse_run_output(proc.stdout, proc.stderr, proc.returncode)


# /run and /submit both run their bt commands inside ONE container via the
# mounted orchestrator (see incontainer.py) so the validator compiles once.
_ORCH = Path(__file__).parent / "incontainer.py"


def run_submit(problem_dir: Path, code_path: Path, *, wall_timeout: int | None = None) -> SubmitResult:
    """Grade against ALL testcases. Sample cases get full detail; secret cases
    get status + time only (their detail stays None — no leak)."""
    if wall_timeout is None:
        wall_timeout = get_config().timeouts.run_all_wall_seconds
    sub_name = code_path.name
    mounts = [(code_path, f"/in/{sub_name}"), (_ORCH, "/in/orch.py")]
    proc = _invoke(
        problem_dir, mounts, ["/in/orch.py", sub_name, "--mode", "submit"],
        wall_timeout, entrypoint="python3",
    )
    return _parse_submit(proc.stdout, proc.stderr)


def run_samples(
    problem_dir: Path,
    code_path: Path,
    custom_stdins: list[str] | None = None,
    *,
    wall_timeout: int | None = None,
) -> RunResult:
    if wall_timeout is None:
        wall_timeout = get_config().timeouts.run_all_wall_seconds
    customs = custom_stdins or []
    sub_name = code_path.name
    with ExitStack() as stack:
        mounts = [(code_path, f"/in/{sub_name}"), (_ORCH, "/in/orch.py")]
        # Each custom stdin is mounted as /in/custom_<n>.in; the orchestrator
        # discovers them by glob and runs one ungraded "custom/<n>" case each.
        for i, stdin in enumerate(customs, start=1):
            in_path = _write_temp(stack, stdin, ".in")
            mounts.append((in_path, f"/in/custom_{i}.in"))
        proc = _invoke(
            problem_dir, mounts, ["/in/orch.py", sub_name, "--mode", "run"], wall_timeout,
            entrypoint="python3",
        )
    return _parse_samples(proc.stdout, proc.stderr)


def _parse_submit(orch_stdout: str, orch_stderr: str) -> SubmitResult:
    if not orch_stdout.strip():
        raise RuntimeError(f"submit orchestrator produced no output: {orch_stderr[:500]}")
    data = json.loads(orch_stdout)
    verdict = parse_run_output(data["verdict_text"], "", 0)   # ALL cases
    if verdict.status is Status.CE and not verdict.testcases:
        return SubmitResult(
            status="CE", passed=0, total=0, max_time_s=0.0, cases=[],
            compile_output=clean_compile_output(data["verdict_text"]),
        )
    detail = {c["bt_name"]: c for c in data["cases"]}   # sample + secret-RTE detail
    cases = []
    for tc in verdict.testcases:
        d = detail.get(tc.name)
        is_sample = tc.name.startswith("sample/")
        if is_sample and d:
            # Public case: full detail.
            inp, exp = d["input"], d["expected"]
            out, err = d["stdout"], strip_bt_noise(d["stderr"])
        elif d:
            # Secret case with captured detail (only crashed/RTE cases get this).
            # Show the error output for debugging, but NEVER the problem's own
            # input/expected (the actual secret data).
            inp = exp = None
            out, err = d["stdout"], strip_bt_noise(d["stderr"])
        else:
            inp = exp = out = err = None
        status = str(tc.status)
        if status == "RTE" and is_memory_error(err):
            status = "MLE"   # OOM surfaces as RTE; relabel it
        cases.append(SubmitCase(
            name=tc.name, status=status, time_s=tc.time_s,
            input=inp, expected=exp, stdout=out, stderr=err,
        ))
    # passed/total are unaffected by relabeling (MLE still isn't AC); recompute
    # the overall verdict in case relabeling changed the worst status.
    overall = _worst([c.status for c in cases])
    return SubmitResult(
        status=overall,
        passed=verdict.passed,
        total=verdict.total,
        max_time_s=verdict.max_time_s,
        cases=cases,
    )


def _parse_samples(orch_stdout: str, orch_stderr: str) -> RunResult:
    if not orch_stdout.strip():
        raise RuntimeError(f"run orchestrator produced no output: {orch_stderr[:500]}")
    data = json.loads(orch_stdout)
    verdict = parse_run_output(data["verdict_text"], "", 0)
    if verdict.status is Status.CE and not verdict.testcases:
        return RunResult(cases=[], compile_output=clean_compile_output(data["verdict_text"]))
    by_name = {tc.name: tc for tc in verdict.testcases}
    cases: list[RunCase] = []
    for c in data["cases"]:
        tc = by_name.get(c["bt_name"])
        status = str(tc.status) if tc else None
        err = strip_bt_noise(c["stderr"])
        if status == "RTE" and is_memory_error(err):
            status = "MLE"
        cases.append(RunCase(
            name=c["name"],
            status=status,
            time_s=tc.time_s if tc else None,
            input=c["input"],
            expected=c["expected"],
            stdout=c["stdout"],
            stderr=err,
        ))
    return RunResult(cases=cases)


def run_judged_custom(
    problem_dir: Path,
    code_path: Path,
    custom_in: str,
    custom_ans: str,
    *,
    wall_timeout: int | None = None,
) -> Verdict:
    if wall_timeout is None:
        wall_timeout = get_config().timeouts.custom_wall_seconds
    with ExitStack() as stack:
        in_path = _write_temp(stack, custom_in, ".in")
        ans_path = _write_temp(stack, custom_ans, ".ans")
        sub_name = code_path.name
        mounts = [
            (code_path, f"/in/{sub_name}"),
            (in_path,   "/in/custom.in"),
            (ans_path,  "/in/custom.ans"),
        ]
        proc = _invoke(
            problem_dir, mounts,
            ["run", "-ve", sub_name, "data/sample/_custom.in"],
            wall_timeout,
        )
        return parse_run_output(proc.stdout, proc.stderr, proc.returncode)


def run_raw_custom(
    problem_dir: Path,
    code_path: Path,
    custom_in: str,
    *,
    wall_timeout: int | None = None,
) -> RawRun:
    if wall_timeout is None:
        wall_timeout = get_config().timeouts.custom_wall_seconds
    with ExitStack() as stack:
        in_path = _write_temp(stack, custom_in, ".in")
        sub_name = code_path.name
        mounts = [
            (code_path, f"/in/{sub_name}"),
            (in_path,   "/in/custom.in"),
        ]
        proc = _invoke(
            problem_dir, mounts,
            ["test", "--no-bar", sub_name, "data/sample/_custom.in"],
            wall_timeout,
        )
        # Strip bt's own chatter so a clean run returns empty stderr (not a
        # false "error"); the program's real stderr is preserved.
        return RawRun(
            stdout=proc.stdout,
            stderr=strip_bt_noise(proc.stderr),
            returncode=proc.returncode,
        )


def _write_temp(stack: ExitStack, content: str, suffix: str) -> Path:
    f = tempfile.NamedTemporaryFile(mode="w", suffix=suffix, delete=False)
    stack.callback(lambda p=f.name: Path(p).unlink(missing_ok=True))
    f.write(content)
    f.close()
    path = Path(f.name)
    # NamedTemporaryFile is created 0600 (owner-only). The sandbox container runs
    # as a different uid than the host service user, so make the mounted input
    # world-readable or the container gets "Permission denied" on /in/custom.*.
    # these hold the student's own submitted input, nothing secret.
    path.chmod(0o644)
    return path
