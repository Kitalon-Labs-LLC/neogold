import os

from pydantic import ConfigDict
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    model_config = ConfigDict(extra="ignore")

    DATABASE_URL: str = os.getenv(
        "DATABASE_URL", "postgresql://postgres:test@localhost:5432/postgres"
    )
    PUBLIC_BASE_URL: str = os.getenv(
        "PUBLIC_BASE_URL", "https://staging.neogold.example/v1"
    )
    ENROLLMENT_CODE_SECRET: str = os.getenv(
        "ENROLLMENT_CODE_SECRET", "default-study-enrollment-secret-key-32b"
    )
    FORWARDED_ALLOW_IPS: str = os.getenv(
        "FORWARDED_ALLOW_IPS", "127.0.0.1,172.30.0.10"
    )
    API_VERSION: str = "1.0.0"
    TRUST_LIST_PATH: str = os.getenv("TRUST_LIST_PATH", "config/trust-list.json")


settings = Settings()
