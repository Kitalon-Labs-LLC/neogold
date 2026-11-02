---
name: ng-break
description: Starts the disposable breaker environment, runs the white-box breaker subagent across the attack catalog (breaker/catalog.md) on synthetic data, files findings and failing regression tests, tears the environment down to zero volumes, and prints findings by severity.
disable-model-invocation: true
argument-hint: "[area] [--rounds N] [--commit <ref>] [--recheck]"
---

# Breaker Quality Loop (`/ng-break`, P27)

Run the adversarial white-box `breaker` agent against disposable copies of NeoGold using synthetic data only (`breaker/env/`), file findings in `breaker/findings/BRK-YYYYMMDD-nn.md` with failing regression tests, tear down all disposable state, and drive the main-session fix loop.

## Hard Constraints
1. **Disposable copies & synthetic data only.** Every attack runs `bash breaker/env/guard.sh` first. It refuses to run if the target URL is not the local breaker stack (`localhost`, `127.0.0.1`, `10.0.2.2`, `api`, `caddy`), if a device serial is not the `neogold-breaker` AVD or listed in `breaker/env/devices.txt`, or if any WireGuard interface is up.
2. **Never staging or production.** Staging load tests are run manually by the owner outside `/ng-break`.
3. **The breaker never edits app code.** Its `PreToolUse` hooks (`breaker_guard.py`) restrict `Write`/`Edit` strictly to `breaker/findings/` and the breaker test folders (`breaker/tests/`, `backend/tests/test_brk_*.py`, `android/**/Brk*Test.kt`).
4. **Stop and ask on any `Clinical: Yes`, `S1`, or `Tier 0` finding.** Immediately halt the round and report to the owner before continuing or applying any fix.
5. **Never weaken validation to make a regression test pass.** On any Tier 0 fix, the `safety-reviewer` subagent checks that no threshold, rule, or validation was weakened.
6. **Zero leftover volumes.** Always run `bash breaker/env/down.sh` in a `trap ... EXIT` / `finally` block and verify `docker volume ls` shows no `neogold-breaker` volumes.

## Arguments
- `[area]` (optional): `all` (default), `clinical`, `data-loss`, `api`, `privacy`, or `ui`.
- `--rounds N` (optional): number of adversarial rounds to execute (default `1`, maximum `3`; values `> 3` are rejected).
- `--commit <ref>` (optional): git commit or branch to inspect and test (default `main`).
- `--recheck`: reruns all breaker regression tests (`pytest breaker/tests`) and re-executes the catalog attacks that previously produced findings in `breaker/findings/BRK-*.md`.

## Procedure

### Step 1: Pre-flight guard & argument check
Verify `--rounds` is in `1..3` (default `1`) and run the safety guard:
```bash
bash breaker/env/guard.sh --api-url "http://127.0.0.1:18000"
```

### Step 2: `--recheck` mode (when `--recheck` is passed)
If `--recheck` is specified:
1. Run the breaker runner in recheck mode:
   ```bash
   python3 breaker/run_round.py --recheck
   ```
2. Run the regression test suite for filed findings:
   ```bash
   pytest breaker/tests -q
   ```
3. Tear down any disposable resources with `bash breaker/env/down.sh` and report which findings remain failing (`OPEN`) vs fixed (`VERIFIED`). Stop here.

### Step 3: Start disposable environment (`breaker/env/up.sh`)
Ensure `bash breaker/env/down.sh` is registered to run on exit, then start the disposable stack (add `--with-android` when running on-device UI/data-loss attacks with the `neogold-breaker` AVD):
```bash
trap 'bash breaker/env/down.sh' EXIT
bash breaker/env/up.sh
```
This starts the `neogold-breaker` Docker Compose stack on `127.0.0.1:18000` with isolated volumes (`pgdata`, `images`, `exports`), applies migrations, and runs `python3 breaker/env/seed.py` to seed synthetic study `SKP-BRK`, `is_test=true` sites, fresh EC P-256 device keys, and synthetic participants/sessions.

### Step 4: Run the `breaker` subagent and attack runner (`1..N` rounds, max 3)
For each round `r` from `1` to `N` (at most `3`):
1. Execute the automated white-box attack runner across the requested `[area]` on the target commit (`--commit`, default `main`):
   ```bash
   python3 breaker/run_round.py --area "${AREA:-all}" --rounds "$N" --commit "${COMMIT:-main}"
   ```
2. Invoke the `breaker` subagent (`.claude/agents/breaker.md`, `isolation: worktree`) scoped to `[area]` and `breaker/catalog.md` (`BRK-001` through `BRK-030`):
   - The `breaker` subagent inspects the code at `<commit>`, executes white-box probes against the disposable environment, files every discovered break as `breaker/findings/BRK-YYYYMMDD-nn.md`, and commits a failing regression test citing the `TC-xxx`, `T1`–`T17`, or `H1`–`H11` ID it protects.
   - **Immediate Stop Rule:** If any finding has `Clinical: Yes` or `Severity: S1`, **STOP THE ROUND IMMEDIATELY**, run Step 5 teardown, and report the finding to the owner.

### Step 5: Tear down disposable environment and verify zero volumes
```bash
bash breaker/env/down.sh
docker volume ls --format '{{.Name}}' | grep -E 'neogold-breaker' && exit 1 || true
```
Confirm that zero `neogold-breaker` containers or volumes remain.

### Step 6: Print findings ordered by severity
Print a summary table of all findings in `breaker/findings/BRK-*.md` ordered by severity (`S1` Critical, `S2` Major, `S3` Minor, `S4` Trivial), showing:
- Finding ID (`BRK-YYYYMMDD-nn`) & Catalog ID (`BRK-nnn`)
- Severity (`S1`–`S4`) & `Clinical: Yes|No`
- Protected IDs (`TC-xxx`, `T1`–`T17`, `H1`–`H11`)
- Regression test path and current status (`FAILING` / `PASSING`)

## The Fix Loop (Main Session Only)
1. **Breaker never fixes code.** All application and backend fixes happen in the main session after the breaker round completes and the environment is torn down.
2. **Stop Gate on Tier 0 / `Clinical: Yes` / `S1`:**
   - Any finding with `Clinical: Yes`, `Severity: S1`, or touching a Tier 0 path (`quality/tiers.yaml`: `:engine:triage`, `:engine:estimation`, `:engine:colorscience`, `:engine:cardtrust`, `:core:security`, `config/triage-config.json`, `config/trust-list.json`) **stops the loop** until the owner has reviewed the finding and approved the fix plan.
3. **Normal PR per Finding (or logical group):**
   - Check out a fix branch in the main session.
   - Promote or keep the failing regression test from `breaker/tests/` (and/or `backend/tests/` or `android/**/test/`) and verify it fails before the fix.
   - Implement the minimal surgical fix in application/backend code without weakening any validation, schema constraint, or clinical threshold.
   - If the fix touches Tier 0, run the `safety-reviewer` subagent (`.claude/agents/safety-reviewer.md`) to verify that validation was not weakened to make the regression test pass.
   - Re-run `/ng-break --recheck` and `python3 tools/quality_gate.py` to confirm the regression test and the original attack now pass.
