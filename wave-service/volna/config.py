from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

SERVICE_DIR = Path(__file__).resolve().parent.parent


def load_env_file(path: Path) -> None:
    """Читает KEY=VALUE из .env; уже заданные переменные окружения не трогает."""
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


@dataclass(frozen=True)
class Settings:
    demo: bool
    navidrome_url: str = ""
    navidrome_user: str = ""
    navidrome_password: str = ""
    audiomuse_url: str = ""
    audiomuse_token: str = ""
    db_path: Path = SERVICE_DIR / "data" / "volna.db"
    host: str = "127.0.0.1"
    port: int = 8765

    @classmethod
    def from_env(cls, force_demo: bool = False) -> "Settings":
        load_env_file(Path.cwd() / ".env")
        load_env_file(SERVICE_DIR / ".env")
        env = os.environ.get
        navidrome_url = env("NAVIDROME_URL", "").rstrip("/")
        demo = force_demo or env("VOLNA_DEMO", "").lower() in ("1", "true", "yes") or not navidrome_url
        default_db = SERVICE_DIR / "data" / ("demo.db" if demo else "volna.db")
        return cls(
            demo=demo,
            navidrome_url=navidrome_url,
            navidrome_user=env("NAVIDROME_USER", ""),
            navidrome_password=env("NAVIDROME_PASSWORD", ""),
            audiomuse_url=env("AUDIOMUSE_URL", "").rstrip("/"),
            audiomuse_token=env("AUDIOMUSE_TOKEN", ""),
            db_path=Path(env("VOLNA_DB", "")) if env("VOLNA_DB") else default_db,
            host=env("VOLNA_HOST", "127.0.0.1"),
            port=int(env("VOLNA_PORT", "8765")),
        )
