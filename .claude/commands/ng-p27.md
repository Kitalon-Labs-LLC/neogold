---
description: "P27: Breaker Agent (NeoGold work package; plan first, then wait for approval)"
---
# P27: Breaker agent (attack our own app, on copies of the data)

Phase 11, Agent quality loop. Depends on: P25. Backlog: none (process). Tests: regression tests it produces cite the TC, T, or H ID they protect; tooling tests in tools/tests/.

## Read first
- docs/8_Security_Compliance_Plan.md Sections 9 and 14 (T1 to T17, H1 to H11)
- docs/7_API_Documentation.md Sections 4.3, 4.4, 5, 7, and 8
- docs/9_Test_Plan_Strategy.md Sections 3 (the reliability and chaos row), 9.1, and 11
- docs/10_Test_Cases.md Sections 6, 12, 13, and 16
- docs/4_System_Architecture.md Sections 4.4, 6.2, and 9
- The Claude Code subagent docs for the installed version (`tools`, `hooks`, `isolation`)

## Goal
An agent whose job is to make NeoGold fail in any of these ways:
- give a wrong but confident answer
- lose or duplicate research data
- leak something personal
- accept a forged request
- fall over under realistic study load

It works only on disposable copies with synthetic data. Every break it finds becomes a failing regression test before anyone fixes it.

## Build
1. A disposable environment in `breaker/env/`:
   - `up.sh` starts the backend stack under the compose project `neogold-breaker`, with its own volumes.
   - `seed.py` creates synthetic data: a study, test sites (`is_test`), devices with freshly generated keys, participants, and sessions.
   - `down.sh` removes the volumes.
   - Android: an AVD named `neogold-breaker`, started with `-wipe-data`, running the debug build with the synthetic config. Enter synthetic research data through the debug seeding path if one exists; otherwise use Maestro flows.
   - `guard.sh`, called first by every attack. It refuses to run if any of these is true:
     - the API URL isn't the breaker stack (localhost, 10.0.2.2, or the compose service name)
     - the device serial isn't the breaker AVD or a phone listed in `breaker/env/devices.txt`
     - a WireGuard interface to the droplet is up
2. `.claude/agents/breaker.md`:
   - Tools: Read, Grep, Glob, Bash, and Write, with `isolation: worktree`.
   - Frontmatter hooks deny:
     - network targets other than the breaker stack
     - `ssh`, `scp`, `wg`, `neogold-deploy`, `neogold-admin`, `restic`, `git push`, `gh release`, and Play uploads
     - any Write or Edit outside `breaker/findings/` and the breaker test folders
   - It reads code freely, because it is a white-box attacker. It can't fix anything.
3. `breaker/catalog.md`. Each entry has an ID (BRK-nnn), a target, a method, the safe behavior the docs require, and how the test decides pass or fail. Seed it with the entries below, then extend it from T1 to T17 and H1 to H11.
   - **Clinical:**
     - inputs in tenths around every threshold and zone boundary
     - edges of gestational age and age in hours
     - a config whose `validUntil` has just passed
     - `triageEnabled` flipped
     - a `DRAFT` config, and a bad signature
     - a card batch that expires mid-session, and a swapped trust list
     - corrupt or truncated images
     - a card QR with a valid signature but altered reference values
     - the same photo submitted twice
   - **Data loss:**
     - killing the process at each step of a research save, scripted, 50 times
     - storage filling up during a save
     - the clock moved back and forward, and the time zone changed
     - an app update with items still in the outbox
     - a Keystore reset, and WorkManager being killed
     - the network flapping during upload (toxiproxy)
   - **API:**
     - the same idempotency key in parallel requests
     - a replayed `jti`
     - `iat` and `exp` exactly at the skew and expiry limits
     - a body hash mismatch
     - uploads that break manifest-first order
     - a participant withdrawn during an upload
     - `data_generation` changing mid-batch
     - unknown fields, and oversized or slow headers
     - 50 simulated devices, each uploading a full study day at once (P20's day: 5 participants, 20 sessions, 120 images)
   - **Privacy:**
     - personal values in logs, crash files, notifications, the recent-apps screenshot, exports, and server logs
     - screening images anywhere on disk after a session
   - **UI robustness:**
     - Maestro and `adb shell monkey` with fixed seeds
     - rotation and the back button during capture and save
     - font scale 2.0, and Bangla
4. Findings go in `breaker/findings/BRK-YYYYMMDD-nn.md`. Each one has:
   - steps to reproduce, and evidence
   - severity from doc 9 Section 9.1, and the `Clinical` flag
   - the hazard or threat ID
   - a failing regression test, committed on the breaker branch
5. `.claude/skills/ng-break/SKILL.md` (`disable-model-invocation: true`; arguments `[area] [--rounds N]`, default 1 round, at most 3):
   - Starts the environment, runs the breaker subagent on the commit I name (default `main`), tears everything down, and prints the findings by severity.
   - `--recheck` reruns the regression tests and the attacks that found something.
6. The fix loop, written into the skill:
   - The main session fixes each finding in a normal PR that includes the regression test. The breaker never edits app code.
   - A Tier 0 or `Clinical: Yes` finding stops the loop until I have seen it.
7. Register the package in docs/14 and the CLAUDE.md Commands table.

## Constraints
- Never staging or production. A load test on staging is a separate step that I run by hand.
- Synthetic data only.
- A fix may not weaken validation to make a regression test pass. The safety-reviewer checks this on Tier 0 fixes.
- New tools (toxiproxy, a load tool, a fuzzer such as Jazzer or Hypothesis) need my approval in the plan.

## Done when
- One full round across all areas has run and the findings are filed.
- Every S1 and S2 finding has a failing regression test.
- The environment tears down to nothing: `docker volume ls` shows no breaker volumes.
- In a test, the guard refuses a staging URL.

## Stop and ask me
- On any `Clinical: Yes` or S1 finding: stop the round and report.

