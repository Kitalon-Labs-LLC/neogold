from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

from app.config import settings

db_url = settings.DATABASE_URL
# If postgresql:// is provided without a driver specification, use psycopg (psycopg 3) or psycopg2
if db_url.startswith("postgresql://"):
    # SQLAlchemy 2 default for postgresql:// is psycopg (psycopg 3)
    pass

engine = create_engine(
    db_url,
    pool_pre_ping=True,
)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

Base = declarative_base()


def configure_engine(url: str | None = None):
    global engine
    if url is None:
        url = settings.DATABASE_URL
    engine = create_engine(url, pool_pre_ping=True)
    SessionLocal.configure(bind=engine)
    return engine


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()

