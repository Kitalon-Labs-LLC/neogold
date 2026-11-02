#!/usr/bin/env bash
# breaker/env/guard.sh
#
# Mandatory pre-flight safety guard called first by every breaker attack,
# environment script, and harness (P27).
#
# Refuses to run (exits 2) if ANY of the following is true:
#   1. The API URL is not the disposable breaker stack (localhost, 127.0.0.1,
#      10.0.2.2, or the compose service name `api` / `caddy` / `neogold-breaker-api`).
#   2. An Android device serial is provided (or required) and is neither an
#      emulator running the `neogold-breaker` AVD nor listed in `breaker/env/devices.txt`.
#   3. A WireGuard interface to the staging/production droplet is up.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVICES_FILE="${BREAKER_DEVICES_FILE:-${SCRIPT_DIR}/devices.txt}"
API_URL="${BREAKER_API_URL:-http://127.0.0.1:18000/v1}"
DEVICE_SERIAL="${BREAKER_DEVICE_SERIAL:-${ANDROID_SERIAL:-}}"
REQUIRE_DEVICE=0
MOCK_WG_INTERFACES="${BREAKER_TEST_WG_INTERFACES:-}"
MOCK_AVD_NAME="${BREAKER_TEST_AVD_NAME:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --api-url)
      API_URL="${2:-}"
      shift 2
      ;;
    --api-url=*)
      API_URL="${1#*=}"
      shift
      ;;
    --serial|--device)
      DEVICE_SERIAL="${2:-}"
      shift 2
      ;;
    --serial=*|--device=*)
      DEVICE_SERIAL="${1#*=}"
      shift
      ;;
    --devices-file)
      DEVICES_FILE="${2:-}"
      shift 2
      ;;
    --require-device)
      REQUIRE_DEVICE=1
      shift
      ;;
    -h|--help)
      echo "Usage: breaker/env/guard.sh [--api-url URL] [--serial SERIAL] [--require-device]"
      exit 0
      ;;
    *)
      echo "breaker/env/guard.sh: unknown argument '$1'" >&2
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------------------
# 1. Check WireGuard interfaces (must NOT be up)
# ---------------------------------------------------------------------------
check_wireguard() {
  if [[ -n "${MOCK_WG_INTERFACES}" ]]; then
    echo "BREAKER GUARD REFUSED: WireGuard interface '${MOCK_WG_INTERFACES}' is up (never run breaker while connected to droplet)." >&2
    exit 2
  fi

  if command -v wg >/dev/null 2>&1; then
    local wg_out=""
    wg_out="$(wg show interfaces 2>/dev/null || true)"
    if [[ -n "${wg_out// /}" ]]; then
      echo "BREAKER GUARD REFUSED: WireGuard interface '${wg_out}' is active via 'wg show interfaces'." >&2
      exit 2
    fi
  fi

  if command -v ip >/dev/null 2>&1; then
    local ip_wg=""
    ip_wg="$(ip -o link show type wireguard 2>/dev/null || true)"
    if [[ -n "${ip_wg// /}" ]]; then
      echo "BREAKER GUARD REFUSED: WireGuard link detected via 'ip link show type wireguard'." >&2
      exit 2
    fi
  fi

  if [[ -d /sys/class/net ]]; then
    for netdev in /sys/class/net/*; do
      [[ -e "${netdev}" ]] || continue
      local devname
      devname="$(basename "${netdev}")"
      if [[ "${devname}" == wg* ]]; then
        echo "BREAKER GUARD REFUSED: WireGuard interface '${devname}' present in /sys/class/net." >&2
        exit 2
      fi
      if [[ -f "${netdev}/uevent" ]] && grep -q "^DEVTYPE=wireguard" "${netdev}/uevent" 2>/dev/null; then
        echo "BREAKER GUARD REFUSED: WireGuard interface '${devname}' (DEVTYPE=wireguard) is up." >&2
        exit 2
      fi
    done
  fi
}

# ---------------------------------------------------------------------------
# 2. Check API URL (must be localhost, 127.0.0.1, 10.0.2.2, or compose service)
# ---------------------------------------------------------------------------
check_api_url() {
  if [[ -z "${API_URL}" ]]; then
    echo "BREAKER GUARD REFUSED: API URL is empty." >&2
    exit 2
  fi

  # Extract scheme and authority (host[:port])
  if [[ ! "${API_URL}" =~ ^https?://([^/:?#]+)(:[0-9]+)?(/.*)?$ ]]; then
    echo "BREAKER GUARD REFUSED: Malformed API URL '${API_URL}'." >&2
    exit 2
  fi

  local host="${BASH_REMATCH[1],,}"

  # Explicitly reject any staging, production, or cloud hostnames
  case "${host}" in
    localhost|127.0.0.1|10.0.2.2|api|caddy|neogold-breaker-api|neogold-breaker-caddy)
      ;;
    *)
      echo "BREAKER GUARD REFUSED: API host '${host}' (in '${API_URL}') is not the breaker stack (allowed: localhost, 127.0.0.1, 10.0.2.2, api, caddy)." >&2
      exit 2
      ;;
  esac
}

# ---------------------------------------------------------------------------
# 3. Check Android device serial (breaker AVD or listed in breaker/env/devices.txt)
# ---------------------------------------------------------------------------
is_in_devices_file() {
  local needle="$1"
  [[ -f "${DEVICES_FILE}" ]] || return 1
  while IFS= read -r line || [[ -n "${line}" ]]; do
    # Strip comments and surrounding whitespace
    line="${line%%#*}"
    line="$(echo "${line}" | tr -d '\r' | xargs 2>/dev/null || true)"
    [[ -z "${line}" ]] && continue
    if [[ "${line}" == "${needle}" ]]; then
      return 0
    fi
  done < "${DEVICES_FILE}"
  return 1
}

resolve_avd_name() {
  local serial="$1"
  if [[ -n "${MOCK_AVD_NAME}" ]]; then
    echo "${MOCK_AVD_NAME}"
    return 0
  fi
  if ! command -v adb >/dev/null 2>&1; then
    return 0
  fi
  local avd=""
  avd="$(adb -s "${serial}" shell getprop ro.boot.qemu.avd_name 2>/dev/null | tr -d '\r\n' || true)"
  if [[ -z "${avd}" ]]; then
    avd="$(adb -s "${serial}" emu avd name 2>/dev/null | head -n 1 | tr -d '\r\n' || true)"
  fi
  echo "${avd}"
}

check_device_serial() {
  if [[ -z "${DEVICE_SERIAL}" ]]; then
    if [[ "${REQUIRE_DEVICE}" -eq 1 ]]; then
      echo "BREAKER GUARD REFUSED: --require-device set but no device serial provided." >&2
      exit 2
    fi
    return 0
  fi

  # Direct match in devices.txt (e.g. `neogold-breaker` or an allow-listed bench phone serial)
  if is_in_devices_file "${DEVICE_SERIAL}"; then
    return 0
  fi

  # If it is an emulator serial (e.g. emulator-5554), check if its AVD name is neogold-breaker
  if [[ "${DEVICE_SERIAL}" == emulator-* ]]; then
    local avd_name
    avd_name="$(resolve_avd_name "${DEVICE_SERIAL}")"
    if [[ "${avd_name}" == "neogold-breaker" ]]; then
      return 0
    fi
    echo "BREAKER GUARD REFUSED: Emulator '${DEVICE_SERIAL}' is running AVD '${avd_name:-unknown}', expected 'neogold-breaker'." >&2
    exit 2
  fi

  echo "BREAKER GUARD REFUSED: Device serial '${DEVICE_SERIAL}' is neither the 'neogold-breaker' AVD nor listed in '${DEVICES_FILE}'." >&2
  exit 2
}

check_wireguard
check_api_url
check_device_serial

exit 0
