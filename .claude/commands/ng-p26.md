---
description: "P26: Security Scan and Security-Debt Ledger (NeoGold work package; plan first, then wait for approval)"
---
# P26: Security scan command and security-debt ledger

Phase 11, Agent quality loop. Depends on: P25. Backlog: none (process). Tests: TC-SEC-10 groundwork (MobSF step), tooling tests in tools/tests/.

## Read first
- docs/8_Security_Compliance_Plan.md Sections 9 (T1 to T17), 10, and 12
- docs/9_Test_Plan_Strategy.md Section 7
- docs/12_Deployment_Runbook.md Section 3 and Appendix A
- docs/7_API_Documentation.md Sections 4.3 and 12
- .claude/agents/privacy-security-reviewer.md
- The DeepSec getting-started docs (vercel-labs/deepsec), and the docs for the pinned version of every scanner you propose

## Goal
One command, `/ng-secscan`, that does three things:
- runs every scanner doc 8 Section 10 promises
- adds an agent review of what scanners can't see
- keeps a ledger of accepted risks, so security debt is visible instead of silent

## Build
1. `tools/secscan/run.sh [--changed | --full]`. Pin every tool version in `tools/secscan/versions.txt`. Output SARIF or JSON into `reports/security/YYYY-MM-DD/`. It covers:
   - **Secrets:** gitleaks over the working tree, plus the full history with `--full`.
   - **Dependencies:** OSV-Scanner on the Gradle lockfiles (or the dependency verification metadata) and the pip lockfile, and Trivy on the API and Caddy images.
   - **Static analysis:**
     - Android Lint security checks and detekt (already in CI; collect their results)
     - Ruff and Bandit on backend/ and training/
     - Semgrep for Kotlin, Python, and Dockerfiles, if I approve it
   - **Workflows:** zizmor, or the P25 check, on .github/workflows.
   - **App:** a MobSF static scan of the release APK with `--full`. It runs in a local container and uploads nothing.
   - **API:** Schemathesis against a local compose stack with `--full`. Never staging from this command.
2. Agent pass: run the privacy-security-reviewer subagent on the diff since the last scan, with T1 to T17 as its checklist. It marks each threat Covered, Weakened, or Not checked.
3. DeepSec, as a trial and not a dependency:
   a. Run it on `backend/` and the `:sync:research` module, with `--max-cost-usd` set to a figure I give in the plan. Run `process --limit 50` first.
   b. Record the trial with `/ng-spike` (P23):
      - the findings it raised
      - how many held up once you checked the code path
      - whether step 1 had missed any of them
   c. Add it to `--full` only if it found at least one real issue the other tools missed.
   d. Kotlin support is unconfirmed, so report Kotlin findings separately.
4. `security/exceptions.yaml` records every accepted finding: ID, tool, rule, file, reason, approver, and an expiry date no more than 90 days out.
   - The scan fails on any high or critical finding that isn't in the file.
   - It also fails on any expired exception.
   - Inline suppressions in code are allowed only when they cite an exception ID.
5. `reports/security/ledger.md`, regenerated on every run: open findings by severity and age, with fix deadlines from doc 8 Section 10 (critical 7 days, high 30, medium 90).
6. `.claude/skills/ng-secscan/SKILL.md` (`disable-model-invocation: true`):
   - Runs `--changed` by default and `--full` on request.
   - Lists new findings first.
   - Never edits code. Fixes come afterwards, in a normal PR.
7. CI: `security-scan.yml`.
   - Runs `--changed` on every PR and `--full` nightly on `main`.
   - Scanner jobs get no secrets.
   - Results go up as workflow artifacts, never as public comments.
8. Register the package in docs/14 and the CLAUDE.md Commands table.

## Constraints
- No scanner gets the signing keys, the Play service account, or droplet access. This command never scans staging or production.
- Reports redact matched secret values. Never commit a report that contains a secret.

## Done when
- `--full` runs clean, or every finding is fixed or listed in exceptions.yaml with my approval.
- The DeepSec trial record is merged with a recommendation.
- On a test branch, a planted fake secret fails CI, and so does a planted vulnerable dependency.

## Stop and ask me
- Before adding any scanner, with its version and licence.
- On any critical finding: stop and report at once, because doc 8 Section 12 may apply.

