#!/usr/bin/env bash
# Post-deploy smoke test (doc 12 Section 7.3). Exits non-zero on any failure.
# NEOGOLD_SMOKE_INTERNAL_ONLY=1 skips the public check (Caddy stopped during a restore).
# NEOGOLD_ALLOW_NEWER_SCHEMA=1 lets an older image accept a newer expand-only schema.
set -euo pipefail
cd /srv/neogold/compose
if [[ "${NEOGOLD_SMOKE_INTERNAL_ONLY:-0}" != 1 ]]; then
  host="$(grep -E '^PUBLIC_HOST=' .env | cut -d= -f2-)"
  curl -fsS --max-time 10 "https://${host}/v1/health" | grep -q '"status":"ok"'
fi
# Self-test: DB reachable, schema at this image's head (or newer if allowed), image store writable,
# last backup under 26 hours old.
docker compose exec -T -e NEOGOLD_ALLOW_NEWER_SCHEMA="${NEOGOLD_ALLOW_NEWER_SCHEMA:-0}" api neogold-admin selftest
echo "smoke ok"
