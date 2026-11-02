#!/usr/bin/env bash
# breaker/env/down.sh
#
# Tears down the `neogold-breaker` compose project, deletes all `neogold-breaker`
# Docker volumes, stops the `neogold-breaker` emulator if running, and removes
# temporary runtime state. Verifies `docker volume ls` shows zero breaker volumes.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
PROJECT_NAME="neogold-breaker"

echo "[breaker/down] Stopping and removing compose stack '${PROJECT_NAME}' and its volumes..."
if command -v docker >/dev/null 2>&1; then
  docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" down -v --remove-orphans >/dev/null 2>&1 || true

  # Explicitly remove any leftover volumes prefixed with neogold-breaker
  LEFTOVER_VOLS="$(docker volume ls --filter "name=${PROJECT_NAME}" -q 2>/dev/null || true)"
  if [[ -n "${LEFTOVER_VOLS}" ]]; then
    # shellcheck disable=SC2086
    docker volume rm -f ${LEFTOVER_VOLS} >/dev/null 2>&1 || true
  fi

  REMAINING="$(docker volume ls --filter "name=${PROJECT_NAME}" -q 2>/dev/null || true)"
  if [[ -n "${REMAINING}" ]]; then
    echo "[breaker/down] ERROR: Breaker volumes still present after teardown: ${REMAINING}" >&2
    exit 1
  fi
fi

# Stop the neogold-breaker emulator if we started one
if [[ -f "${SCRIPT_DIR}/.runtime/emulator.pid" ]]; then
  EMU_PID="$(cat "${SCRIPT_DIR}/.runtime/emulator.pid" 2>/dev/null || true)"
  if [[ -n "${EMU_PID}" ]]; then
    kill "${EMU_PID}" >/dev/null 2>&1 || true
  fi
fi

if command -v adb >/dev/null 2>&1; then
  for serial in $(adb devices 2>/dev/null | awk 'NR>1 && $1 ~ /^emulator-/ {print $1}'); do
    avd="$(adb -s "${serial}" emu avd name 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
    if [[ "${avd}" == "neogold-breaker" ]]; then
      adb -s "${serial}" emu kill >/dev/null 2>&1 || true
    fi
  done
fi

rm -rf "${SCRIPT_DIR}/.runtime"
echo "[breaker/down] Teardown complete. Zero '${PROJECT_NAME}' volumes remain."
