"""Runtime configuration, loaded once at startup from a YAML file.

Operational knobs (resource limits, concurrency, timeouts, languages, image
tag) live in `config.yaml`, NOT hardcoded. Override the path with env
`JUDGE_CONFIG`. Any field omitted from the YAML falls back to the defaults here.

NOTE — build-time vs runtime: the **bapctools version** is baked into the
`judge-sandbox` image and is NOT a runtime knob; it is a Docker build arg
(`BT_VERSION` in the Dockerfile). Changing it requires rebuilding the image.
"""
from __future__ import annotations
import os
from functools import lru_cache
from pathlib import Path

import yaml
from pydantic import BaseModel, Field


# Language -> source file extension bt uses to detect the language. v1 image
# ships c/cpp/java/python (DECISIONS.md D5); the rest are forward-compat.
DEFAULT_LANGUAGES = {
    "c": ".c",
    "cpp": ".cpp",
    "java": ".java",
    "python": ".py",
    "javascript": ".js",
    "rust": ".rs",
    "csharp": ".cs",
    "kotlin": ".kt",
}


class SandboxConfig(BaseModel):
    """Per-run Docker resource limits (SECURITY.md S4–S8)."""
    memory_mb: int = 1024
    cpus: float = 1.0
    pids_limit: int = 256
    fsize_bytes: int = 33_554_432   # 32 MB max single-file write
    work_tmpfs_mb: int = 512
    tmp_tmpfs_mb: int = 128
    uid: int = 1000
    gid: int = 1000


class ConcurrencyConfig(BaseModel):
    # Containers in flight (≈ #CPU cores — Judge0 per-machine sweet spot).
    max_workers: int = Field(default_factory=lambda: os.cpu_count() or 4, ge=1)
    # Total jobs accepted (queued + running) before backpressure (HTTP 429), S15.
    max_queue_size: int = Field(default=100, ge=1)


class TimeoutConfig(BaseModel):
    run_all_wall_seconds: int = Field(default=60, ge=1)
    custom_wall_seconds: int = Field(default=30, ge=1)


class Config(BaseModel):
    image: str = "judge-sandbox:latest"
    problems_dir: Path = Path("problems")
    sandbox: SandboxConfig = SandboxConfig()
    concurrency: ConcurrencyConfig = ConcurrencyConfig()
    timeouts: TimeoutConfig = TimeoutConfig()
    languages: dict[str, str] = Field(default_factory=lambda: dict(DEFAULT_LANGUAGES))


DEFAULT_CONFIG_PATH = Path(__file__).parent / "config.yaml"


@lru_cache(maxsize=1)
def get_config() -> Config:
    """Load + validate the config once (cached for the process lifetime)."""
    path = Path(os.environ.get("JUDGE_CONFIG", DEFAULT_CONFIG_PATH))
    data: dict = {}
    if path.is_file():
        data = yaml.safe_load(path.read_text()) or {}
    return Config(**data)
