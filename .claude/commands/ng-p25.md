---
description: "P25: Guardrails as Code (NeoGold work package; plan first, then wait for approval)"
---
# P25: Guardrails as code (must-not-trade-off rules that enforce themselves)

Phase 11, Agent quality loop. Depends on: P24. Backlog: none (process). Tests: tooling and hook tests in tools/tests/ and .claude/hooks/tests/ (no TC IDs).

## Read first
- CLAUDE.md "Non-negotiable rules" (every line)
- docs/8_Security_Compliance_Plan.md Sections 3, 6.1, and 10
- docs/9_Test_Plan_Strategy.md Sections 2, 7, 9.1, and 10
- docs/6_UI_UX_Guidelines.md Sections 10.2 and 10.3
- docs/12_Deployment_Runbook.md Section 3 and Appendix B
- The Claude Code hooks reference for the installed version: input fields, exit code 2, `permissionDecision` (allow, deny, ask), matcher syntax, and the project directory variable. Don't write hooks from memory.

## Goal
Every rule in CLAUDE.md is either enforced by a check that fails, or listed as human-only with the name of who checks it. Rules that live only in prose get forgotten in long sessions; checks don't.

## Build
1. Replace "Non-negotiable rules" in CLAUDE.md with a "Must not trade off" table. Columns:
   - Rule
   - Why (doc reference)
   - Enforced by: hook, test, CI check, release gate, or a named human
   - Runs at: edit time, PR, nightly, or release

   Keep every existing rule, and add none without asking. Below the table, add a short "We do trade off" list for me to approve in the plan. For example: polish on screens outside the result path, code style in tools/, speed of non-core features, Could items.
2. Fill the gaps: every rule without a mechanical check gets one. Expect at least these:
   - **StrictMath only in engines:** detekt ForbiddenImport or ForbiddenMethodCall config for `kotlin.math` powers, roots, and exponentials, and for `java.lang.Math`, in `:engine:*`.
   - **No skipped tests:** a check that fails on `@Ignore`, `@Disabled`, `assumeTrue(false)`, `pytest.mark.skip`, `xfail`, or `pytest.skip(` in Tier 0 and Tier 1 test paths. It also fails on any drop in the per-TC test count against `quality/tc-baseline.json`.
   - **No new dependency without an ADR:** `tools/check_dependencies.py`, checked against `quality/dependency-allowlist.txt`. It covers the resolved Gradle runtime classpath per module, the pip lockfile, and npm if any. It also re-checks the P01 rule that only `:sync:research` has an HTTP client.
   - **Request models forbid unknown fields:** a pytest that walks every Pydantic request model and asserts `extra="forbid"`.
   - **Banned words:** a string lint over values/ and values-bn/. Reuse P13's if it exists, and prove it fails on one banned word in each language.
   - **Actions pinned by SHA:** a check over .github/workflows. Use zizmor if I approve it, or a short script if not.
   - **Forward-only migrations:**
     - Committed Room schema files never change once released.
     - Alembic migrations containing `op.drop_`, or a `nullable=False` change on an existing column, fail unless the PR has the `contract-migration` label.
   - **Download size under 25 MB:** a bundletool size check on the release build.
3. Claude Code hooks in `.claude/settings.json` (merge with any existing file), with scripts in `.claude/hooks/` and tests for each:
   - `protect_clinical.py` (PreToolUse on Edit, Write, and NotebookEdit):
     - Denies edits to config/triage-config.json, CLINICAL_SIGNOFF.md, the trust list, released model files, and the clinical strings. Find the real paths and list them in the plan.
     - The denial message is: "Clinical values change only with the clinical lead's signed record (PRD 9b)."
   - `protect_tests.py`:
     - Denies an edit that adds a skip marker to a Tier 0 or Tier 1 test file.
     - Returns `ask` for a skip marker in Tier 2 or Tier 3. Doc 9 Section 7 allows quarantining a flaky test there with a linked defect, so I decide.
     - Denies a Bash `rm`, `git rm`, or `mv` of a test file in Tier 0 or Tier 1.
   - `protect_guardrails.py`: returns `ask`, not `deny`, so I confirm each edit to any of these:
     - `.claude/settings.json`
     - `.claude/hooks/` and `.claude/agents/`
     - `quality/`
     - `tools/check_*`
     - `.github/workflows/`
   - `bash_guard.py`: denies:
     - `--no-verify`
     - `git push --force`
     - commits on `main`
     - `-x test` or `-x check` in Gradle calls
     - any command that reads a keystore or a `.env` file

   Every hook must run in under 200 ms, deny the action if its input doesn't parse, and work on macOS and Linux.
4. Quality tiers (the slop budget). `quality/tiers.yaml` maps paths to tiers:
   - **Tier 0, clinical:** `:engine:triage`, `:engine:estimation`, `:engine:colorscience`, `:engine:cardtrust`, the quality gate in `:engine:vision`, config/, the clinical UI strings, and the result screen.
   - **Tier 1, data and trust:** `:core:security`, `:core:data`, `:sync:research`, export, backend auth, idempotency and admin code, and backend/deploy.
   - **Tier 2, app:** the other `:feature:*` and `:core:*` modules, and the rest of the backend.
   - **Tier 3, tooling:** tools/, training notebooks, test harnesses, and scripts.

   `tools/quality_gate.py --base origin/main` finds the strictest tier a PR touches and requires that tier's checks:

   | Tier | Required checks |
   |---|---|
   | 0 | Unit tests; golden vectors; differential triage when relevant; a branch coverage floor (Kover; propose the number from today's value, never lower); a mutation check (PIT on the engines if I approve it, otherwise the P03 manual mutation step documented in the PR); a safety-reviewer section in the PR body |
   | 1 | The privacy-security-reviewer section in the PR body and a clean P26 security scan |
   | 2 | Lint, tests, and screenshot tests for UI |
   | 3 | Lint and tests |
5. `tools/check_guardrails.py`: checks that every row in the CLAUDE.md table points to a file, task, or test that exists. CI fails if a row's enforcement disappears.
6. `.github/CODEOWNERS`: Tier 0 paths, `.claude/`, `quality/`, and the workflows require the second approver.
7. Register the package in docs/14 and the CLAUDE.md Commands table.

## Constraints
- Hooks only guard Claude Code sessions. The clinical lead's changes still go through a normal PR with the signed record. Don't build a bypass that a session could trigger itself.
- No rule gets weaker. If today's code fails a new check, list the failures in the plan; don't loosen the check to make it pass.

## Done when
- A demo PR (closed, not merged) shows each new check failing on a deliberate violation and passing once it is reverted.
- The hook tests pass. In a live session, trying to edit config/triage-config.json is denied with the message above.
- `tools/check_guardrails.py` passes in CI.

## Stop and ask me
- In the plan: the "We do trade off" list, the tier map, the coverage floors, and any tool to add (Kover, PIT, zizmor).

