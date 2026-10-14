---
description: "P14: Research Mode (NeoGold work package; plan first, then wait for approval)"
---
# P14: Research mode

Phase 6, Research. Depends on: P05, P11, P12, P13. Backlog: NG-028, NG-029, NG-030, NG-031, NG-041. Tests: TC-RSH-01 to TC-RSH-16.

## Read first
- docs/2_PRD.md J4, FR-25 to FR-29
- docs/6_UI_UX_Guidelines.md Sections 5 (R01 to R09), 6.2
- docs/8_Security_Compliance_Plan.md Sections 6.2, 6.4, 7.1
- docs/5_Database_Schema.md research tables
- docs/7_API_Documentation.md Section 4.1 (study profile fields)

## Goal
Everything a study nurse needs to run a blinded validation session, with no data ever lost or shown when it should not be.

## Build
1. R01 unlock: scan the signed study QR (P05 verify), show study name, partner, and host for confirmation, set PIN. Store the study in `research_study`.
2. PIN: minimum 6 digits, reject repeated digits, sequences, and the top-100 list; Argon2id m=19 MiB, t=2, p=1 (fall back to m=12 MiB, t=3 if D4 takes over 1 s), then HMAC-SHA256 with a Keystore key; failure counter written before each check; lockout 1 minute at 5, 15 minutes at 10, then 1 hour each; no wipe; re-lock after 5 minutes in the background or screen off.
3. R02 to R06: participant code and consent form ID (formats from doc 7 Section 7), blinded capture protocol (sternum, forehead, sclera flash and no-flash), TSB entry with method and draw time, later entry by participant code, linking to a session later (outbox row re-queued as a new revision), session complete.
4. Blinding: the estimate is never rendered, never in the accessibility tree, never logged.
5. Crash-safe writes using P11's transactional store.
6. Card status for each session: `VERIFIED`, `EXPIRED`, or `UNVERIFIED` from P05.

## Tests
- TC-RSH-01 to TC-RSH-16; TC-RSH-14 and TC-RSH-15 scripted for the device matrix.

## Done when
- A full research session completes on an emulator and on D1, offline.
- safety-reviewer (blinding) and privacy-security-reviewer (PIN, storage) subagents have reviewed the diff.
