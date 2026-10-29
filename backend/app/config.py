import os

from pydantic import ConfigDict
from pydantic_settings import BaseSettings

# The secret that used to be the default. It is in the repository, so it protects nothing; refuse it by name.
RETIRED_ENROLLMENT_SECRETS = frozenset({"default-study-enrollment-secret-key-32b"})
MIN_ENROLLMENT_SECRET_CHARS = 32
# A repeated character or a run of spaces is long enough but is not random; random hex or base64 uses far more than this.
MIN_ENROLLMENT_SECRET_DISTINCT_CHARS = 10


class ConfigurationError(RuntimeError):
    """The server is missing or has an unsafe setting, so it must not start."""


class Settings(BaseSettings):
    model_config = ConfigDict(extra="ignore")

    DATABASE_URL: str = os.getenv(
        "DATABASE_URL", "postgresql://postgres:test@localhost:5432/postgres"
    )
    PUBLIC_BASE_URL: str = os.getenv(
        "PUBLIC_BASE_URL", "https://staging.neogold.example/v1"
    )
    # K12 (doc 8 Section 4): 256-bit random, in the server's environment file. There is no default: a default would
    # let anyone who reads this repository compute the code hash of any enrollment code (BUG-2026-063).
    ENROLLMENT_CODE_SECRET: str | None = os.getenv("ENROLLMENT_CODE_SECRET") or None
    FORWARDED_ALLOW_IPS: str = os.getenv(
        "FORWARDED_ALLOW_IPS", "127.0.0.1,172.30.0.10"
    )
    API_VERSION: str = "1.0.0"
    TRUST_LIST_PATH: str = os.getenv("TRUST_LIST_PATH", "config/trust-list.json")
    IMAGE_STORE_DIR: str = os.getenv("IMAGE_STORE_DIR", "/data/images")
    PURGE_DATABASE_URL: str | None = os.getenv("PURGE_DATABASE_URL", None)
    EXPORT_DATABASE_URL: str | None = os.getenv("EXPORT_DATABASE_URL", None)

    def enrollment_code_key(self) -> bytes:
        """The HMAC key for enrollment codes, or ConfigurationError when it is missing, short or the retired default.

        Alembic and other tools import this module without needing K12, so the check runs where the key is used and
        once at server start (app.main), not at import.
        """
        secret = self.ENROLLMENT_CODE_SECRET
        if not secret:
            raise ConfigurationError(
                "ENROLLMENT_CODE_SECRET is not set. Put a random secret of at least "
                f"{MIN_ENROLLMENT_SECRET_CHARS} characters in the server's environment file (doc 8 K12)."
            )
        if secret in RETIRED_ENROLLMENT_SECRETS:
            raise ConfigurationError("ENROLLMENT_CODE_SECRET is the old published default; generate a new one.")
        secret = secret.strip()
        if len(secret) < MIN_ENROLLMENT_SECRET_CHARS or len(set(secret)) < MIN_ENROLLMENT_SECRET_DISTINCT_CHARS:
            raise ConfigurationError(
                f"ENROLLMENT_CODE_SECRET must be at least {MIN_ENROLLMENT_SECRET_CHARS} characters of random text "
                "(for example `openssl rand -hex 32`)."
            )
        return secret.encode("utf-8")


settings = Settings()
