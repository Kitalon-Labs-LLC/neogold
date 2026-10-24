#!/usr/bin/env bash
# Validate the server templates in backend/deploy (doc 12 Appendices C and D).
# Run after ANY change to backend/deploy; CI runs it in backend-ci.yml (deploy-lint job).
# Needs: bash, shellcheck, docker with the compose plugin, systemd-analyze, sudo (to create
# empty /etc/neogold/*.env files when absent, because compose requires env_file paths to exist).
#
# Checks:
#   1. ShellCheck on every script
#   2. docker compose config (with placeholder values)
#   3. caddy validate on the Caddyfile, using the pinned Caddy base image
#   4. the derived Caddy image (caddy/Dockerfile) runs as 1000:1000 with ALL capabilities
#      dropped and no-new-privileges (the defect described in doc 12 Section 7.1, TC-OPS-11)
#   5. systemd-analyze calendar on the backup timer
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
deploy="${here}/deploy"
# Keep in step with CADDY_BASE in docs/test-reports/staging-setup.md.
CADDY_BASE="${CADDY_BASE:-caddy:2.11-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f}"   # Caddy 2.11.7

echo "== 1/5 ShellCheck"
shellcheck -x \
  "${deploy}/neogold-deploy" "${deploy}/smoke.sh" "${deploy}/neogold-backup" \
  "${deploy}/neogold-bootstrap" "${deploy}/neogold-admin" "${BASH_SOURCE[0]}"

echo "== 2/5 docker compose config"
for f in api.env db.env migrate.env; do
  if [[ ! -e "/etc/neogold/${f}" ]]; then
    sudo mkdir -p /etc/neogold
    sudo touch "/etc/neogold/${f}"
  fi
done
CADDY_BASE="${CADDY_BASE}" PUBLIC_HOST=staging.neogold.example ACME_EMAIL=ops@neogold.example \
  POSTGRES_IMAGE=postgres:16-alpine API_IMAGE=ghcr.io/example/neogold-intake \
  API_DIGEST="sha256:$(printf '0%.0s' {1..64})" \
  docker compose -f "${deploy}/docker-compose.yml" --profile ops config --quiet

echo "== 3/5 caddy validate"
docker run --rm -e PUBLIC_HOST=staging.neogold.example -e ACME_EMAIL=ops@neogold.example \
  -v "${deploy}/Caddyfile:/etc/caddy/Caddyfile:ro" "${CADDY_BASE}" \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

echo "== 4/5 Caddy image runs with all capabilities dropped"
docker build -q --build-arg "CADDY_BASE=${CADDY_BASE}" -t neogold-caddy:validate "${deploy}/caddy" > /dev/null
docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  --user 1000:1000 neogold-caddy:validate caddy version

echo "== 5/5 Backup timer calendar"
systemd-analyze calendar "$(sed -n 's/^OnCalendar=//p' "${deploy}/neogold-backup.timer")" > /dev/null

echo "deploy templates ok"
