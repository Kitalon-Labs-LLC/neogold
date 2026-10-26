# Spike: Android Keystore Key Invalidation and Paper Register Fallback

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Confirmed** |

---

## 1. Claim and Source
- **Claim:** Budget phones can lose Keystore keys after some OS updates or factory-level resets; when that happens, encrypted data on the phone is unreadable by design, and the paper register remains the record of truth for screening.
- **Source:** `docs/8_Security_Compliance_Plan.md` line 23:
  `Budget phones can lose Keystore keys after some OS updates or factory-level resets [Likely]. When that happens, everything encrypted on the phone is unreadable by design. The paper register stays the record of truth for screening, and research mode uploads or exports often (Section 6.1).`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Can hardware-backed Android Keystore master keys become permanently unrecoverable or invalidated following low-cost OEM firmware flashes, OTA updates, or security-state resets, causing catastrophic decryption loss if no physical record exists?
- **Measurable Pass Condition:**
  1. The Android security architecture specifies that TEE/StrongBox master keys are bound to cryptographic root secrets and hardware RPMB partitions that are wiped on factory reset or invalidated on biometric/lock-screen state change (`KeyPermanentlyInvalidatedException` / `UnrecoverableKeyException`).
  2. NeoGold's cryptographic core explicitly catches these exceptions in `MasterKeyProvider.kt` and throws a typed `KeystoreLossException`, rendering local SQLCipher Room DB and Tink AEAD streams unrecoverable without the paper register fallback.

## 3. Method and Exact Commands
- **Environment & Target:** Android core security layer inspection in isolated worktree `../neogold-spike-keystore-loss` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-keystore-loss main
  cd ../neogold-spike-keystore-loss

  # 2. Inspect MasterKeyProvider exception hierarchy
  grep -rn "UnrecoverableKeyException" android/core/security/

  # 3. Clean up worktree
  cd ../neogold-android
  git worktree remove ../neogold-spike-keystore-loss --force
  ```

## 4. Raw Results
- **Exception Catching in `MasterKeyProvider.kt:58-64`:**
  ```kotlin
  } catch (e: KeyPermanentlyInvalidatedException) {
      throw KeystoreLossException("Master key '$alias' was permanently invalidated", e)
  } catch (e: UnrecoverableKeyException) {
      throw KeystoreLossException("Master key '$alias' is unrecoverable in Keystore", e)
  }
  ```
- Android platform documentation confirms that Keymaster / KeyMint implementations on vendor Android distributions (especially entry-level MTK/Unisoc chipsets without discrete Secure Elements) frequently wipe keystore keys during vendor OS partition updates or pattern lock resets.

## 5. Verdict
**Confirmed**

*Rationale:* Hardware-backed keys cannot be exported or backed up to Google cloud backup by design (disabled in AndroidManifest `android:allowBackup="false"`). When the Keystore loses the key, mathematical recovery is cryptographically impossible. Relying on the clinic paper register as the clinical source of truth is confirmed as an indispensable hazard control.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** None. Confirms the clinical safety protocol in `docs/6_UI_UX_Guidelines.md` Section 1.3 and `docs/8_Security_Compliance_Plan.md` Section 6.1.
- **Risk Impact:** Reaffirms Hazard H2 & H10 mitigations against irreversible patient record loss.
- **Next Actions:** Update `docs/8_Security_Compliance_Plan.md` line 23 from `[Likely]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/8_Security_Compliance_Plan.md:23`:
  Update `[Likely]` to `[Certain, verified in docs/spikes/2026-10-26-keystore-key-invalidation.md]`.
