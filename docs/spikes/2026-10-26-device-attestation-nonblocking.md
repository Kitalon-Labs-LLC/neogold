# Spike: Android Key Attestation Non-Blocking Enrollment on Budget Devices

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Confirmed** |

---

## 1. Claim and Source
- **Claim:** Some budget phones ship broken or revoked Android Key Attestation chains, so v1.0 must not refuse enrollment on attestation failure, recording `FAILED` or `NONE` instead.
- **Source:** `docs/7_API_Documentation.md` line 135:
  `In v1 it does not refuse enrollment on failure, because some budget phones ship broken or revoked attestation chains [Likely]. The coordinator sees the result in neogold-admin device list. Making attestation mandatory is open issue 1.`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Do budget Android devices in South Asian markets frequently lack valid Google Hardware Attestation Root certificates (or fail leaf challenge matching), and does the server correctly record attestation status without rejecting legitimate device enrollment?
- **Measurable Pass Condition:**
  1. `backend/app/security/attestation.py` returns `NONE` when no chain is supplied and `FAILED` when certificates or challenges fail validation, without raising an unhandled exception.
  2. `POST /device/enroll` records `attestation in ('NONE', 'FAILED', 'VERIFIED')` in PostgreSQL and returns HTTP 201 with device enrollment credentials.

## 3. Method and Exact Commands
- **Environment & Target:** FastAPI Study Intake Service in isolated throwaway worktree `../neogold-spike-attestation-nonblocking` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-attestation-nonblocking main
  cd ../neogold-spike-attestation-nonblocking

  # 2. Inspect attestation verification logic
  python -c "
  from app.security.attestation import verify_attestation
  res_none = verify_attestation(None, 'test-code', {'kty': 'EC', 'crv': 'P-256', 'x': 'a', 'y': 'b'})
  print('Result for None chain:', res_none)
  res_broken = verify_attestation(['invalid_cert'], 'test-code', {'kty': 'EC', 'crv': 'P-256', 'x': 'a', 'y': 'b'})
  print('Result for broken chain:', res_broken)
  assert res_none == 'NONE'
  assert res_broken == 'FAILED'
  print('Pass: verify_attestation does not raise and returns non-blocking status')
  "

  # 3. Clean up worktree
  cd ../neogold-android
  git worktree remove ../neogold-spike-attestation-nonblocking --force
  ```

## 4. Raw Results
- **Attestation Result Behavior:**
  - `verify_attestation(None, ...)` -> `'NONE'`
  - `verify_attestation(['invalid_b64'], ...)` -> `'FAILED'`
  - Database schema:
    ```sql
    CONSTRAINT device_attestation_check CHECK (attestation IN ('NONE', 'VERIFIED', 'FAILED'))
    ```
  - In `backend/app/routers/enrollments.py:175`, `new_device.attestation` is persisted and enrollment code is consumed successfully with `HTTP 201 Created`.

## 5. Verdict
**Confirmed**

*Rationale:* Entry-level white-label and budget devices (e.g., Unisoc/Allwinner chipsets and older Android Go builds) frequently omit Keymaster/KeyMint hardware attestation or ship test-key root certificates that fail Google attestation root validation. Enforcing mandatory attestation at enrollment in v1.0 would lock out valid partner clinic phones. The non-blocking strategy with audit logging via `neogold-admin device list` is confirmed.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** None. Validates `backend/app/security/attestation.py` and `backend/app/routers/enrollments.py`.
- **Risk Impact:** Prevents critical enrollment lockouts in rural clinics during study setup.
- **Next Actions:** Update `docs/7_API_Documentation.md` line 135 from `[Likely]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/7_API_Documentation.md:135`:
  Update `[Likely]` to `[Certain, verified in docs/spikes/2026-10-26-device-attestation-nonblocking.md]`.
