---
name: ng-secscan
description: Unified security scan and security-debt ledger command. Runs every scanner promised in docs/8 Section 10, runs the privacy-security-reviewer subagent against threats T1 to T17, and regenerates reports/security/ledger.md. Never edits source code.
disable-model-invocation: true
argument-hint: "[--changed | --full]"
---

# Security Scan & Debt Ledger (`/ng-secscan`, P26)

Run every security scanner promised in `docs/8_Security_Compliance_Plan.md` Section 10, perform an agent review of what automated scanners cannot see against threats `T1` to `T17`, and maintain the security-debt ledger (`security/exceptions.yaml` and `reports/security/ledger.md`).

## Hard constraints
1. **Never edit code.** This command only executes scanners, writes reports under `reports/security/`, and updates `reports/security/ledger.md`. Fixes and new `security/exceptions.yaml` entries come afterwards in a normal PR with owner approval.
2. **Zero secrets to scanners.** Never pass signing keys (`KEYSTORE_*`), the Google Play service account (`PLAY_SERVICE_ACCOUNT_JSON`), or droplet SSH credentials (`DROPLET_SSH_KEY`) to any scanner job or subprocess.
3. **Never scan staging or production.** Dynamic API checks (`schemathesis` in `--full` mode) run strictly against a local compose stack (`http://127.0.0.1:8000/v1`).
4. **Redact all matched secrets.** Never write or commit a report containing an unredacted secret value.
5. **Stop immediately on any Critical finding.** If any scanner or the agent pass identifies an unaccepted `CRITICAL` finding, stop at once and report to the owner because `docs/8_Security_Compliance_Plan.md` Section 12 (Incident Response) may apply.

## Arguments
- `--changed` (default): scans the working tree and changed paths (`gitleaks` working tree, `osv-scanner`, `trivy fs`, Android Lint security checks, `detekt`, `ruff`, `bandit`, workflow checks).
- `--full`: runs everything in `--changed` plus full git history secrets scan (`gitleaks --log-opts="--all"`), `trivy` container checks on the API (`neogold-intake`) and Caddy (`neogold-caddy`) images, local container MobSF static scan of the release APK (`TC-SEC-10`), and local compose Schemathesis OpenAPI fuzzing (`TC-API-30`).

## Procedure

### Step 1: Record pre-scan git status and determine diff range
```bash
git status --porcelain > /tmp/ng-secscan-before.txt
```
Determine `DATE=$(date +%F)` and `OUT=reports/security/$DATE`. Find the commit or date of the previous `reports/security/*/summary.json` (or `main` if none exists) to scope the diff for the agent review pass.

### Step 2: Run the automated scanner suite and ledger evaluator
Run `--changed` by default, or `--full` when requested:
```bash
bash tools/secscan/run.sh --changed   # default
# or:
bash tools/secscan/run.sh --full      # when --full is requested
```
This executes all pinned tools from `tools/secscan/versions.txt`, writes SARIF and JSON outputs into `reports/security/$DATE/`, validates `security/exceptions.yaml` (failing on any unaccepted `HIGH`/`CRITICAL` finding, any expired or `>90`-day exception, or any inline suppression missing a `SEC-EXC-XXXX` citation), and regenerates `reports/security/ledger.md`.

If `summary.json` reports `"critical_incident_stop": true`, **STOP AT ONCE** and alert the owner per `docs/8_Security_Compliance_Plan.md` Section 12.

### Step 3: Agent review pass (`privacy-security-reviewer` on `T1` to `T17`)
Invoke the `privacy-security-reviewer` subagent (`.claude/agents/privacy-security-reviewer.md`) on the diff since the last scan (`git diff <last-scan-ref>...HEAD`, or full security surface on initial `--full` scan) with `docs/8_Security_Compliance_Plan.md` Section 9 threats `T1` through `T17` as its mandatory checklist:
- The subagent marks each threat `T1`..`T17` as **`Covered`**, **`Weakened`**, or **`Not checked`**, citing `file:line` evidence.
- Save the subagent review output to `reports/security/$DATE/agent-review.md`.
- If any threat is marked **`Weakened`**, treat it as a high-priority finding in the user summary.

### Step 4: Present findings (New findings first)
Report to the user in this exact order:
1. **New / Unaccepted Findings First:** List any new or unaccepted findings from `reports/security/$DATE/summary.json` and `agent-review.md` ordered by severity (`CRITICAL`, `HIGH`, `MEDIUM`, `LOW`), with `file:line`, tool, rule, and remediation deadline (`Critical: 7d`, `High: 30d`, `Medium: 90d`).
2. **Agent Threat Checklist (`T1` to `T17`):** Summary of `Covered`, `Weakened`, and `Not checked` threats from `reports/security/$DATE/agent-review.md`.
3. **Security-Debt Ledger Status (`reports/security/ledger.md`):** Counts of accepted exceptions in `security/exceptions.yaml`, upcoming expiries within 14 days, and any policy violations (expired exceptions or uncited inline suppressions).

### Step 5: Verify read-only integrity on source tree
```bash
git status --porcelain
```
Verify that no source files outside `reports/security/` were modified by the scan. Never edit application code during `/ng-secscan`.

