from __future__ import annotations
import subprocess
import tempfile
from contextlib import ExitStack
from pathlib import Path

from .config import get_config
from .models import RawRun, Verdict
from .parser import parse_run_output


def _docker_flags() -> list[str]:
    """Hardened `docker run` flags built from the loaded config (SECURITY.md S4–S8)."""
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
) -> subprocess.CompletedProcess:
    cmd: list[str] = ["docker", "run", *_docker_flags()]
    cmd += ["-v", f"{problem_dir.resolve()}:/problem:ro"]
    for host, container in mounts:
        cmd += ["-v", f"{host.resolve()}:{container}:ro"]
    cmd += [get_config().image, *bt_args]
    return subprocess.run(cmd, capture_output=True, text=True, timeout=wall_timeout)


def run_all(problem_dir: Path, code_path: Path, *, wall_timeout: int | None = None) -> Verdict:
    if wall_timeout is None:
        wall_timeout = get_config().timeouts.run_all_wall_seconds
    sub_name = code_path.name
    mounts = [(code_path, f"/in/{sub_name}")]
    # -v emits per-testcase lines (including ACs) so total/passed counts are accurate;
    # -e expands failure detail (Got/wanted snippet).
    proc = _invoke(problem_dir, mounts, ["run", "-ve", sub_name], wall_timeout)
    return parse_run_output(proc.stdout, proc.stderr, proc.returncode)


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
            ["test", sub_name, "data/sample/_custom.in"],
            wall_timeout,
        )
        return RawRun(stdout=proc.stdout, stderr=proc.stderr, returncode=proc.returncode)


def _write_temp(stack: ExitStack, content: str, suffix: str) -> Path:
    f = tempfile.NamedTemporaryFile(mode="w", suffix=suffix, delete=False)
    stack.callback(lambda p=f.name: Path(p).unlink(missing_ok=True))
    f.write(content)
    f.close()
    return Path(f.name)
