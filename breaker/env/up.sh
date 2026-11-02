#!/usr/bin/env bash
# breaker/env/up.sh
#
# Starts the disposable `neogold-breaker` backend stack with its own volumes,
# runs migrations and synthetic data seeding (`seed.py`), and optionally starts
# the `neogold-breaker` Android AVD with `-wipe-data` and the synthetic config.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
PROJECT_NAME="neogold-breaker"
API_PORT="${BREAKER_API_PORT:-18000}"
DB_PORT="${BREAKER_DB_PORT:-15432}"
export BREAKER_API_URL="${BREAKER_API_URL:-http://127.0.0.1:${API_PORT}/v1}"

WITH_ANDROID=0
SKIP_SEED=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-android)
      WITH_ANDROID=1
      shift
      ;;
    --skip-seed)
      SKIP_SEED=1
      shift
      ;;
    -h|--help)
      echo "Usage: breaker/env/up.sh [--with-android] [--skip-seed]"
      exit 0
      ;;
    *)
      echo "breaker/env/up.sh: unknown argument '$1'" >&2
      exit 2
      ;;
  esac
done

# 1. Always run the safety guard first
bash "${SCRIPT_DIR}/guard.sh" --api-url "${BREAKER_API_URL}"

mkdir -p "${SCRIPT_DIR}/.runtime"

# 2. Start the backend compose stack under project `neogold-breaker`
echo "[breaker/up] Starting disposable Docker Compose stack '${PROJECT_NAME}'..."
docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" up -d --build --wait

echo "[breaker/up] Running Alembic migrations on '${PROJECT_NAME}'..."
docker compose -p "${PROJECT_NAME}" -f "${COMPOSE_FILE}" exec -T api alembic upgrade head

# 3. Seed synthetic study, test sites, devices, participants, and sessions
if [[ "${SKIP_SEED}" -eq 0 ]]; then
  PYTHON_BIN="python3"
  if [[ -x "${REPO_ROOT}/.venv/bin/python" ]]; then
    PYTHON_BIN="${REPO_ROOT}/.venv/bin/python"
  elif [[ -x "${REPO_ROOT}/.venv/Scripts/python.exe" ]]; then
    PYTHON_BIN="${REPO_ROOT}/.venv/Scripts/python.exe"
  elif ! command -v python3 >/dev/null 2>&1; then
    PYTHON_BIN="python"
  fi
  echo "[breaker/up] Seeding synthetic study data via breaker/env/seed.py..."
  "${PYTHON_BIN}" "${SCRIPT_DIR}/seed.py" \
    --api-url "${BREAKER_API_URL}" \
    --db-url "postgresql+psycopg://postgres:${BREAKER_DB_PASSWORD:-breaker-disposable-pg-pass-only}@127.0.0.1:${DB_PORT}/neogold_breaker" \
    --out "${SCRIPT_DIR}/.runtime/seed-state.json"
fi

# 4. Optionally start the `neogold-breaker` AVD with -wipe-data and synthetic config
if [[ "${WITH_ANDROID}" -eq 1 ]]; then
  AVD_NAME="neogold-breaker"
  echo "[breaker/up] Starting Android AVD '${AVD_NAME}' with -wipe-data..."
  if command -v emulator >/dev/null 2>&1; then
    nohup emulator -avd "${AVD_NAME}" -wipe-data -no-snapshot-save -no-boot-anim -netdelay none -netspeed full \
      >"${SCRIPT_DIR}/.runtime/emulator.log" 2>&1 &
    echo $! > "${SCRIPT_DIR}/.runtime/emulator.pid"
    adb wait-for-device
    # Verify serial with guard.sh
    SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
    export BREAKER_DEVICE_SERIAL="${SERIAL}"
    bash "${SCRIPT_DIR}/guard.sh" --api-url "http://10.0.2.2:${API_PORT}/v1" --serial "${BREAKER_DEVICE_SERIAL}" --require-device

    echo "[breaker/up] Building and installing debug APK with synthetic config..."
    (cd "${REPO_ROOT}/android" && ./gradlew :app:assembleDebug)
    adb -s "${BREAKER_DEVICE_SERIAL}" install -r -t "${REPO_ROOT}/android/app/build/outputs/apk/debug/app-universal-debug.apk"

    if command -v maestro >/dev/null 2>&1; then
      echo "[breaker/up] Seeding synthetic research data on '${BREAKER_DEVICE_SERIAL}' via Maestro flows..."
      maestro --device "${BREAKER_DEVICE_SERIAL}" test -e APP_ID=com.kitalonlabs.neogold.debug "${REPO_ROOT}/android/maestro/research_enrollment_flow.yaml"
      maestro --device "${BREAKER_DEVICE_SERIAL}" test -e APP_ID=com.kitalonlabs.neogold.debug "${REPO_ROOT}/android/maestro/j4_research_capture_flow.yaml"
    fi
  else
    echo "[breaker/up] ERROR: Android 'emulator' binary not found on PATH." >&2
    exit 2
  fi
fi

echo "[breaker/up] Breaker environment '${PROJECT_NAME}' is ready at ${BREAKER_API_URL}."
