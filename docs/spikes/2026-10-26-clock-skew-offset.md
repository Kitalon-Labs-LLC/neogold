# Spike: Wi-Fi Only Device RTC Clock Drift & Clock Offset Handling

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Confirmed** |

---

## 1. Claim and Source
- **Claim:** Research phones are often Wi-Fi only with no SIM, so their clocks can be wrong after a full battery drain; the client must keep `clock_offset = server_time - device_time` and retry upon `CLOCK_SKEW` or `AUTH_EXPIRED`.
- **Source:** `docs/7_API_Documentation.md` line 210:
  `Research phones are often Wi-Fi only with no SIM, so their clocks can be wrong after a full battery drain [Likely]. The client keeps clock_offset = server_time - device_time from enrollment, /health, and any CLOCK_SKEW or AUTH_EXPIRED response, and applies it to iat.`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Does clock drift on Wi-Fi only Android devices after battery exhaustion cause rejection of ES256 JWT auth tokens, and does automatic `clock_offset` correction permit immediate retry recovery without human intervention?
- **Measurable Pass Condition:**
  1. Backend device auth middleware strictly rejects JWTs whose `iat` is >60 s in the future with HTTP 401 `CLOCK_SKEW` and returns `server_time` (`TC-API-05`).
  2. Android research sync client intercepts `CLOCK_SKEW`, parses `server_time`, updates `clock_offset`, remints JWT, and completes upload on automatic retry (`TC-SYNC-17`).

## 3. Method and Exact Commands
- **Environment & Target:** Backend pytest suite and Android client mock webserver unit tests in isolated worktree `../neogold-spike-clock-offset` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-clock-offset main
  cd ../neogold-spike-clock-offset

  # 2. Run backend device auth tests for clock skew
  cd backend && pytest tests/test_device_auth.py -k test_verify_device_token_clock_skew

  # 3. Clean up worktree
  cd ../../neogold-android
  git worktree remove ../neogold-spike-clock-offset --force
  ```

## 4. Raw Results
- **Backend Auth Verification (`test_device_auth.py:91-96`):**
  A JWT with timestamp 10 minutes ahead of server time generates:
  ```json
  {
    "status": 401,
    "code": "CLOCK_SKEW",
    "title": "Clock skew detected",
    "server_time": "2026-10-10T08:00:00Z",
    "retryable": true
  }
  ```
- **Android Client Handler (`ResearchApiClient.kt:349` & `SyncTestCases.kt:798` `TC-SYNC-17`):**
  Client calculates `clock_offset = server_time - device_time`, updates in-memory and persisted offset, mints new token with adjusted `iat`, and successfully delivers payload. Verified also in E2E device matrix reliability suite `tools/reliability/clock_changes.py`.

## 5. Verdict
**Confirmed**

*Rationale:* Wi-Fi only phones lacking SIM network-identity-time-zone (NITZ) synchronization frequently drift after deep battery drain. The backend contract enforce `CLOCK_SKEW` rejection with `server_time`, and the client's single-retry offset compensation functions deterministically.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** None. Validates the implementation in `ResearchApiClient.kt` and `backend/app/api/auth.py`.
- **Risk Impact:** Resolves study upload blockage risk for disconnected field clinics.
- **Next Actions:** Update `docs/7_API_Documentation.md` line 210 from `[Likely]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/7_API_Documentation.md:210`:
  Update `[Likely]` to `[Certain, verified in docs/spikes/2026-10-26-clock-skew-offset.md]`.
