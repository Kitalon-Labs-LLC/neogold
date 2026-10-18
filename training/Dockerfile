# Pinned reproducible Dockerfile for NeoGold model training pipeline (TC-OPS-07, NFR-11)
# No network access at train time (run with --network none).

FROM python:3.13-slim-bookworm@sha256:d9b23db1b7fcf76c221cf19582dbf46dc3cb9c084ad9a6e133d162bc2d5069a3

# Install make for build targets
RUN apt-get update && \
    apt-get install -y --no-install-recommends make && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install dependencies using pinned hashes
COPY training/requirements.txt /app/training/
RUN pip install --no-cache-dir --require-hashes -r /app/training/requirements.txt

# Non-root user for execution security
RUN useradd -u 1000 -m -s /bin/bash neogold && \
    chown -R neogold:neogold /app

# Copy application sources
COPY --chown=neogold:neogold . /app/

USER neogold
WORKDIR /app

# Default command reproduces model.json and asserts byte-identical output
CMD ["make", "reproduce"]
