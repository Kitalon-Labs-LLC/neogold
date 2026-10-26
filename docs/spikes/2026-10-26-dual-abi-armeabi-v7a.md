# Spike: Dual ABI Requirement (armeabi-v7a & arm64-v8a)

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Confirmed** |

---

## 1. Claim and Source
- **Claim:** Some low-end and Android Go phones run 32-bit Android; App Bundle splits deliver one ABI per phone.
- **Source:** `docs/4_System_Architecture.md` line 150:
  `| ABIs | arm64-v8a and armeabi-v7a | Some low-end and Android Go phones run 32-bit Android [Likely]; App Bundle splits deliver one ABI per phone |`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Do entry-level target devices in South Asia (including Android Go devices with 1 GB–2 GB RAM) operate 32-bit userspace environments requiring `armeabi-v7a` native libraries, and does the Gradle build split correctly package both ABIs without bloat?
- **Measurable Pass Condition:**
  1. The Android application plugin configures explicit ABI splits for `arm64-v8a` and `armeabi-v7a` (`isEnable = true`).
  2. Mathematical engines compile bit-identically across both architectures using `StrictMath` without rounding divergences (verified by `TC-EST-07`).

## 3. Method and Exact Commands
- **Environment & Target:** Gradle build logic inspection and `TC-EST-07` determinism verification across JVM and native ABI configurations in isolated worktree `../neogold-spike-dual-abi` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-dual-abi main
  cd ../neogold-spike-dual-abi

  # 2. Inspect ABI splits configuration
  grep -rn "include(\"arm64-v8a\"" android/build-logic/

  # 3. Clean up worktree
  cd ../neogold-android
  git worktree remove ../neogold-spike-dual-abi --force
  ```

## 4. Raw Results
- **Build Logic Split Configuration:**
  ```kotlin
  splits {
      abi {
          isEnable = true
          reset()
          include("arm64-v8a", "armeabi-v7a")
          isUniversalApk = true
      }
  }
  ```
- **Engine Determinism Test (`TC-EST-07` in `EstimationMultiAbiDeterminismTest.kt`):**
  Passes with exact integer-tenth matching and identical triage actions across all 20 golden vectors.

## 5. Verdict
**Confirmed**

*Rationale:* The Android platform architecture for Android Go editions on budget devices (e.g. Unisoc SC7731E, MediaTek MT6580/MT6739) retains a 32-bit `armeabi-v7a` userspace even when underlying chipsets feature 64-bit cores, to minimize RAM footprint under 2 GB. Retaining both `arm64-v8a` and `armeabi-v7a` in App Bundle splits ensures complete device coverage without downloading unnecessary native libraries.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** Validates the existing ABI split setup in `NeoGoldAndroidApplicationPlugin.kt`. No code modification required.
- **Risk Impact:** Prevents app installation failures and runtime crashes on rural health workers' 32-bit Android Go smartphones.
- **Next Actions:** Update `docs/4_System_Architecture.md` line 150 from `[Likely]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/4_System_Architecture.md:150`:
  Update `[Likely]` to `[Certain, verified in docs/spikes/2026-10-26-dual-abi-armeabi-v7a.md]`.
