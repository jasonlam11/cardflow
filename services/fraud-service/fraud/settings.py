import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    db_host: str = os.environ.get("DB_HOST", "localhost")
    db_port: int = int(os.environ.get("DB_PORT", "5432"))
    db_name: str = os.environ.get("DB_NAME", "fraud")
    db_user: str = os.environ.get("DB_USER", "fraud_svc")
    db_password: str = os.environ.get("DB_PASSWORD", "")
    model_dir: Path = Path(os.environ.get("MODEL_DIR", Path(__file__).resolve().parents[1] / "model"))

    @property
    def dsn(self) -> str:
        return (f"host={self.db_host} port={self.db_port} dbname={self.db_name} "
                f"user={self.db_user} password={self.db_password}")
