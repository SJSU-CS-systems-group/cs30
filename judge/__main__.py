from __future__ import annotations
import argparse
import sys
from pathlib import Path

from .runner import run_all, run_raw_custom, run_judged_custom


def main() -> int:
    p = argparse.ArgumentParser(prog="python -m judge")
    sub = p.add_subparsers(dest="cmd", required=True)

    p_all = sub.add_parser("all", help="Judge against all sample + secret cases")
    p_all.add_argument("problem_dir", type=Path)
    p_all.add_argument("code_path", type=Path)
    p_all.add_argument("--wall-timeout", type=int, default=60)

    p_raw = sub.add_parser("custom", help="Run on custom stdin, show output (no verdict)")
    p_raw.add_argument("problem_dir", type=Path)
    p_raw.add_argument("code_path", type=Path)
    p_raw.add_argument("--input-file", type=Path, required=True)
    p_raw.add_argument("--ans-file", type=Path, default=None)
    p_raw.add_argument("--wall-timeout", type=int, default=30)

    args = p.parse_args()

    if args.cmd == "all":
        v = run_all(args.problem_dir, args.code_path, wall_timeout=args.wall_timeout)
        print(f"verdict: {v.status}  passed {v.passed}/{v.total}  max {v.max_time_s:.3f}s")
        for tc in v.testcases:
            line = f"  {tc.name:20} {tc.status} {tc.time_s:.3f}s"
            if tc.detail:
                line += f"   {tc.detail}"
            print(line)
        return 0 if v.status.name == "AC" else 1

    if args.cmd == "custom":
        in_text = args.input_file.read_text()
        if args.ans_file:
            v = run_judged_custom(
                args.problem_dir, args.code_path, in_text,
                args.ans_file.read_text(), wall_timeout=args.wall_timeout,
            )
            print(f"verdict: {v.status}  time {v.max_time_s:.3f}s")
            for tc in v.testcases:
                if tc.detail:
                    print(f"  {tc.detail}")
            return 0 if v.status.name == "AC" else 1
        r = run_raw_custom(args.problem_dir, args.code_path, in_text,
                           wall_timeout=args.wall_timeout)
        sys.stdout.write(r.stdout)
        if r.stderr:
            sys.stderr.write(r.stderr)
        return r.returncode

    return 2


if __name__ == "__main__":
    raise SystemExit(main())
