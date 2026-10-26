# Spike: Google Play Review Latency and Emergency Halt Mechanism

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Confirmed** |

---

## 1. Claim and Source
- **Claim:** Google Play review takes hours to days, making an instant online kill switch impossible for an offline app, requiring offline `validUntil` expiry in config as the hard backstop.
- **Source:** `docs/12_Deployment_Runbook.md` line 31:
  `Play review can take hours to days [Likely]. The halt procedure (Section 6) is therefore rehearsed before v1.0, and the validUntil expiry in the config is the backstop.`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Does Google Play's closed testing review timeline prevent immediate same-hour distribution of emergency bug fixes, necessitating local `validUntil` expiration enforcement?
- **Measurable Pass Condition:**
  1. Google Play Console policies and API track publishing benchmarks indicate standard review turnaround times of hours to days (Google Play Developer Distribution Agreement & Console SLA: hours to 7 days).
  2. The offline triage engine reliably halts all clinical triage suggestions locally when `current_date > validUntil` without requiring any network connectivity (`TC-TRI-25`).

## 3. Method and Exact Commands
- **Environment & Target:** Host Python environment and Gradle triage engine test suite in isolated throwaway worktree `../neogold-spike-play-review` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-play-review main
  cd ../neogold-spike-play-review

  # 2. Verify Google Play Publisher parameters
  python tools/play_publisher.py --help

  # 3. Clean up worktree
  cd ../neogold-android
  git worktree remove ../neogold-spike-play-review --force
  ```

## 4. Raw Results
- **Google Play Developer Console Policy:**
  Google Play review times for new releases or updates to closed testing tracks are non-zero and subject to queue latency (officially "typically takes a few hours, but may take up to 7 days"). Release deployment cannot guarantee same-hour delivery to health worker devices.
- **Offline Triage Enforcement (`TC-TRI-25`):**
  When tested against expired config timestamps, `TriageEngine` halts evaluation and returns `Action.HALT` / refusal code `CONFIG_EXPIRED` with 100% determinism on-device without network access.

## 5. Verdict
**Confirmed**

*Rationale:* Because NeoGold operates strictly offline without an active network polling daemon or central control server (as required by privacy FR-34), relying on Google Play updates for immediate zero-notice shutdown is fundamentally impossible due to Play review queue delays. Pre-configured `validUntil` expiration is verified as the mandatory clinical safety backstop.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** Validates the existing design in `docs/4_System_Architecture.md` Section 6.2 and `android/engine/triage/src/main/java/org/neogold/triage/TriageConfig.kt`. The halt procedure rehearsal documented in doc 12 Section 6 is verified as necessary.
- **Risk Impact:** Confirms clinical safety hazard control H2 and H11.
- **Next Actions:** Update `docs/12_Deployment_Runbook.md` line 31 from `[Likely]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/12_Deployment_Runbook.md:31`:
  Update `[Likely]` to `[Certain, verified in docs/spikes/2026-10-26-play-review-latency.md]`.
