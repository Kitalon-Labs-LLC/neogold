---
description: "P01: Android Skeleton and Build Guardrails (NeoGold work package; plan first, then wait for approval)"
---
# P01: Android skeleton and build guardrails

Phase 0, Foundation. Depends on: P00. Backlog: NG-001. Tests: TC-SEC-01, TC-SEC-02 (static part), TC-SEC-04.

## Read first
- docs/4_System_Architecture.md Sections 4.1 to 4.3, 4.6, 12, 14, 15
- docs/8_Security_Compliance_Plan.md Sections 6.1 and 5.3
- docs/12_Deployment_Runbook.md Section 2 (versioning) and Appendix B.2 to B.4
- docs/6_UI_UX_Guidelines.md Section 2 (tokens only)

## Goal
An Android Gradle project with every module from doc 4 Section 4.2, empty but wired, and build-time guardrails that make the privacy and size rules impossible to break by accident.

## Build
1. Gradle version catalog; Kotlin 2.x; AGP current stable; JDK 21 toolchain; `minSdk` 26, `targetSdk` 36; ABI splits `arm64-v8a`, `armeabi-v7a`; R8 full mode; dependency locking on with committed `gradle.lockfile` files; Gradle dependency verification metadata.
2. Modules: `:app`, `:feature:onboarding`, `:feature:calibration`, `:feature:screening`, `:feature:result`, `:feature:log`, `:feature:research`, `:feature:settings`, `:engine:camera`, `:engine:vision`, `:engine:colorscience`, `:engine:estimation`, `:engine:triage`, `:engine:cardtrust`, `:core:model`, `:core:data`, `:core:security`, `:core:designsystem`, `:core:common`, `:core:testing`, `:sync:research`. Pure engines are Kotlin JVM modules with no Android plugin.
3. Convention plugins in `build-logic/` for Android library, JVM library, Compose, Hilt, and test setup, so modules stay one screen long.
4. Hilt, Compose, Material 3, a placeholder theme from doc 6 Section 2 tokens, and a single-activity app with Compose Navigation and empty destinations for S01 to S25 (including S10a) and R01 to R09.
5. `applicationId` `com.kitalonlabs.neogold`; `versionName` and `versionCode` exactly as in doc 12 Section 2, plus the `printVersionName` task.
6. Manifest per doc 8 Section 6.1: `allowBackup="false"`, both backup rule files excluding everything, network security config (cleartext off, system CAs only), no exported components but the launcher. Permissions only as each package needs them; for now just `CAMERA`.
7. Guardrails as Gradle checks wired into `check`:
   - A module dependency rule: only `:sync:research` may depend on OkHttp or any HTTP client; any `:engine:*` or `:feature:*` dependency on one fails the build.
   - Pure engine modules may not depend on any `androidx` or `android` artifact.
   - `tools/check_manifest.py` on the merged release manifest; `tools/check_network_config.py` on the network config; `tools/check_16kb.sh` on the release APK.
8. detekt and Android Lint with warnings as errors for security checks; a baseline only if a generated file needs one.
9. Enable `android-ci.yml` from `docs/ci-templates/` with the tasks that exist now; leave clearly marked TODO steps for tasks later packages add (`verifyGoldenVectors`, `triageDifferentialTest`, `verifyConfigSchema`).

## Constraints
- No engine logic yet; empty modules with one placeholder test each.
- Ask before adding any library beyond: Kotlin stdlib, coroutines, Compose, Material 3, Navigation, Hilt, Lifecycle, JUnit, Truth, Turbine, Robolectric, detekt.

## Tests
- A JVM test in each pure engine module proving it has no Android classes on its classpath.
- TC-SEC-01: `check_manifest.py` passes on the merged manifest.
- TC-SEC-02 static: `check_network_config.py` passes.
- TC-SEC-04: `check_16kb.sh` passes on the release APK (no native libraries yet).

## Done when
- `./gradlew check assembleRelease` passes locally and in CI.
- Adding OkHttp to `:engine:triage` makes the build fail with a clear message (show this in the PR, then revert).
- Release APK download size reported in the PR.

## Stop and ask me if
- AGP or Kotlin versions force a `minSdk` or `targetSdk` change.
