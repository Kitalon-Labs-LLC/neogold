# syntax=docker/dockerfile:1
# NeoGold Study Intake API image (doc 12 Section 7.1, doc 8 Section 8.2).
# Built in CI (backend-ci.yml, image job) with an SBOM and provenance; deployed by digest only.
#
# The card-batch trust list lives at the repository root (config/trust-list.json), outside
# this build context, so it comes in through a named build context:
#   docker build --build-context repo-config=config -t neogold-intake:dev backend
#
# Runtime contract (docker-compose.yml, service "api" and "migrate"):
#   - runs as 10001:10001 on a read-only root filesystem; writes only to /data/images,
#     /data/exports (volumes) and /tmp (tmpfs)
#   - "neogold-admin" is on PATH (doc 7 Section 10; used by smoke.sh and the host wrapper)
#   - "alembic upgrade head" works from the working directory (migrate service)
#   - GET /v1/health on port 8000 is the health check

ARG PYTHON_BASE=python:3.12-slim-trixie@sha256:a0f57064cbdcd6285fa45f51237ab0b35ea8ebaed06a1f5973e89e19dfb86704

# ---------------------------------------------------------------------------------------
FROM ${PYTHON_BASE} AS build
ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=5
COPY requirements-runtime.lock /tmp/requirements-runtime.lock
# Venv without pip: the base image's pip installs into it, so no installer ships in /opt/venv.
# Hash-checked, wheels only (nothing compiled at build time).
RUN python -m venv --without-pip /opt/venv \
 && pip --python /opt/venv/bin/python install --require-hashes --only-binary=:all: \
        -r /tmp/requirements-runtime.lock

COPY alembic.ini /app/alembic.ini
COPY alembic/ /app/alembic/
COPY app/ /app/app/
COPY --from=repo-config trust-list.json /app/config/trust-list.json

# Entry point for the admin CLI. The source tree stays at /app (not installed into
# site-packages) because the self-test locates alembic.ini relative to the app package.
RUN printf '%s\n' '#!/opt/venv/bin/python' 'import sys' 'from app.cli import main' \
        'sys.exit(main())' > /opt/venv/bin/neogold-admin \
 && chmod 0755 /opt/venv/bin/neogold-admin \
 && /opt/venv/bin/python -m compileall -q /opt/venv/lib /app/app /app/alembic

# ---------------------------------------------------------------------------------------
FROM ${PYTHON_BASE}

# No package installer in the runtime image; non-root user 10001 with no home and no shell.
RUN python -m pip uninstall -y -q pip \
 && groupadd --system --gid 10001 neogold \
 && useradd --system --uid 10001 --gid 10001 --no-create-home \
        --home-dir /nonexistent --shell /usr/sbin/nologin neogold \
 && mkdir -p /data/images/.tmp /data/exports \
 && chown -R 10001:10001 /data

# Code and dependencies stay root-owned, so the service user cannot modify them.
COPY --from=build /opt/venv /opt/venv
COPY --from=build /app /app

ENV PATH=/opt/venv/bin:$PATH \
    PYTHONPATH=/app \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/tmp \
    IMAGE_STORE_DIR=/data/images \
    TRUST_LIST_PATH=/app/config/trust-list.json

WORKDIR /app
USER 10001:10001
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/v1/health', timeout=3)"]

# One worker: the rate limiters are in-process (doc 7 Section 8), and staging has 1 vCPU.
# --no-proxy-headers: the app itself trusts X-Forwarded-For only from FORWARDED_ALLOW_IPS
# (Caddy, 172.30.0.10; TC-API-33); uvicorn must not rewrite the client address first.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1", \
     "--no-proxy-headers", "--no-server-header", "--timeout-graceful-shutdown", "20"]
