#!/usr/bin/env bash
# tools/secscan/run.sh [--changed | --full]
#
# Unified security scan entrypoint for /ng-secscan (P26, docs/8_Security_Compliance_Plan.md Section 10).
#
# Hard constraints:
# - Never receives or forwards signing keys, Play service account credentials, or droplet SSH keys.
# - Never scans staging or production (local compose stack only for --full Schemathesis).
# - Redacts matched secret values in all reports under reports/security/YYYY-MM-DD/.
# - Regenerates reports/security/ledger.md on every run.
# - Exits non-zero on any unaccepted HIGH/CRITICAL finding, expired exception, or uncited inline suppression.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

MODE="changed"
SCAN_DATE="$(date +%F)"
REPORT_DIR=""
EXCEPTIONS_FILE="${REPO_ROOT}/security/exceptions.yaml"
LEDGER_FILE="${REPO_ROOT}/reports/security/ledger.md"
VERSIONS_FILE="${SCRIPT_DIR}/versions.txt"
TARGET_URL="${SECSCAN_TARGET_URL:-http://127.0.0.1:8000/v1}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --changed)
      MODE="changed"
      shift
      ;;
    --full)
      MODE="full"
      shift
      ;;
    --date)
      SCAN_DATE="$2"
      shift 2
      ;;
    --report-dir)
      REPORT_DIR="$2"
      shift 2
      ;;
    --exceptions)
      EXCEPTIONS_FILE="$2"
      shift 2
      ;;
    --ledger)
      LEDGER_FILE="$2"
      shift 2
      ;;
    --root)
      REPO_ROOT="$2"
      shift 2
      ;;
    --target-url)
      TARGET_URL="$2"
      shift 2
      ;;
    -h|--help)
      echo "Usage: tools/secscan/run.sh [--changed | --full] [--date YYYY-MM-DD] [--report-dir DIR]"
      exit 0
      ;;
    *)
      echo "ERROR: Unknown argument '$1'. Expected --changed or --full." >&2
      exit 2
      ;;
  esac
done

if [[ -z "${REPORT_DIR}" ]]; then
  REPORT_DIR="${REPO_ROOT}/reports/security/${SCAN_DATE}"
fi

# Strip all signing, Play Store, and cloud droplet credentials from scanner environment
unset KEYSTORE_BASE64 KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD
unset PLAY_SERVICE_ACCOUNT_JSON DROPLET_SSH_KEY DO_API_TOKEN NEOGOLD_SIGNING_KEY
unset PRODUCTION_DATABASE_URL STAGING_DATABASE_URL

# Guard against scanning staging or production
case "${TARGET_URL,,}" in
  *intake.neogold*|*staging.*|*prod.*|*digitalocean*|*kitalonlabs.com*)
    echo "SECURITY CONSTRAINT VIOLATION: /ng-secscan never scans staging or production (${TARGET_URL})." >&2
    exit 2
    ;;
esac

if [[ ! -f "${VERSIONS_FILE}" ]]; then
  echo "ERROR: Missing pinned versions file at ${VERSIONS_FILE}" >&2
  exit 2
fi

# Resolve Python interpreter
PYTHON_BIN=""
if [[ -x "${REPO_ROOT}/.venv/bin/python" ]]; then
  PYTHON_BIN="${REPO_ROOT}/.venv/bin/python"
elif command -v python3 >/dev/null 2>&1 && python3 -c "import yaml" >/dev/null 2>&1; then
  PYTHON_BIN="python3"
elif command -v python >/dev/null 2>&1 && python -c "import yaml" >/dev/null 2>&1; then
  PYTHON_BIN="python"
elif [[ -x "/c/Program Files/LibreOffice/program/python.exe" ]]; then
  PYTHON_BIN="/c/Program Files/LibreOffice/program/python.exe"
  export PYTHONPATH="${REPO_ROOT}/.venv/Lib/site-packages${PYTHONPATH:+:${PYTHONPATH}}"
else
  echo "ERROR: Could not locate a Python 3 interpreter with PyYAML installed." >&2
  exit 2
fi

mkdir -p "${REPORT_DIR}"

echo "[ng-secscan] Running security scanners in --${MODE} mode (report dir: ${REPORT_DIR})..."
"${PYTHON_BIN}" "${SCRIPT_DIR}/scanners.py" \
  --mode "${MODE}" \
  --root "${REPO_ROOT}" \
  --report-dir "${REPORT_DIR}" \
  --versions-file "${VERSIONS_FILE}" \
  --target-url "${TARGET_URL}"

echo "[ng-secscan] Evaluating findings against ${EXCEPTIONS_FILE} and updating ${LEDGER_FILE}..."
"${PYTHON_BIN}" "${SCRIPT_DIR}/evaluate_secscan.py" \
  --mode "${MODE}" \
  --root "${REPO_ROOT}" \
  --report-dir "${REPORT_DIR}" \
  --exceptions "${EXCEPTIONS_FILE}" \
  --ledger "${LEDGER_FILE}" \
  --date "${SCAN_DATE}"

echo "[ng-secscan] Security scan (--${MODE}) completed cleanly."

