import os
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Settings:
    db_host: str = os.environ.get("DB_HOST", "localhost")
    db_port: int = int(os.environ.get("DB_PORT", "5432"))
    db_name: str = os.environ.get("DB_NAME", "assistant")
    db_user: str = os.environ.get("DB_USER", "assistant_svc")
    db_password: str = os.environ.get("DB_PASSWORD", "")
    ledger_url: str = os.environ.get("LEDGER_URL", "http://localhost:8081")
    docs_dir: Path = Path(os.environ.get("DOCS_DIR", ROOT / "docs" / "benefits"))
    # "anthropic", "fake", or "auto" (anthropic when credentials exist, else fake demo mode)
    provider: str = os.environ.get("ASSISTANT_PROVIDER", "auto")
    model: str = os.environ.get("ASSISTANT_MODEL", "claude-haiku-4-5")
    max_rounds: int = int(os.environ.get("ASSISTANT_MAX_ROUNDS", "5"))
    top_k: int = int(os.environ.get("ASSISTANT_TOP_K", "4"))
    extra: dict = field(default_factory=dict)

    @property
    def dsn(self) -> str:
        return (f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
                f"user={self.db_user} password={self.db_password}")
