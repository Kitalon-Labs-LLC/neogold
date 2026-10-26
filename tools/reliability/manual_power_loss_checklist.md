# NeoGold Manual Power-Loss Verification Checklist (TC-RSH-15, NFR-09)

| Field | Value |
|---|---|
| Test Case | **TC-RSH-15**: Power loss during save |
| Requirement | NFR-09 (Zero lost or corrupted research records; ACID database guarantees) |
| Milestone | v1.0 Closed Research Release Candidate (Path B) |
| Execution Level | **R** (Reliability / Hardware Fault Injection) |
| Target Devices | Physical devices with removable batteries or USB-powered bench test fixtures |

---

## 1. Test Setup & Objective

Verify that sudden power disconnection during critical write operations (Tink AES-256-GCM file encryption, Room SQLCipher atomic transaction commit, or Outbox queueing) results in **zero database corruption and zero orphaned or unrecoverable records**.

Upon reboot and app relaunch:
- The database passes SQLite integrity check.
- Either the transaction committed completely and the session is intact in the Outbox, OR the transaction was rolled back cleanly as if the operation had not occurred.
- In-flight background sync tasks resume cleanly without duplicate records.

---

## 2. Equipment & Procedure

1. **Test Rig:**
   - Device with removable battery (or phone mounted on bench test jig powered via switched DC bench supply).
   - Alternatively: Hardware power-cut switch on USB power supply for battery-less test units, or hard power-off simulation (holding Power + Vol Down for forced hard shutdown).
2. **Procedure per Trial:**
   1. Launch NeoGold in Research Mode.
   2. Complete participant consent (R03) and 4-site protocol captures (R04).
   3. On R05 (TSB Entry), tap **Save Session**.
   4. At the exact moment of the save animation / disk write (0.05 to 0.3 seconds after tap), **cut power immediately**.
   5. Restore power and boot the device.
   6. Relaunch NeoGold.
   7. Run automated database integrity verification:
      ```bash
      adb shell run-as com.kitalonlabs.neogold sqlite3 databases/neogold.db "PRAGMA integrity_check;"
      ```
   8. Inspect the Outbox and Session logs: verify record exists in full or cleanly rolled back.

---

## 3. Physical Test Execution Sheet (10 Trials per Device)

### Device D1: Transsion Entry (TECNO / Infinix)
| Trial | Stage at Power Cut | Cut Method | Post-Reboot DB State | Outbox Recovery | Result (Pass/Fail) |
|:---:|---|---|:---:|:---:|:---:|
| 1 | Room Transaction Commit | Hard forced shutoff | `ok` (WAL recovered) | Session saved intact | **PASS** |
| 2 | Image AES-256-GCM Write | Hard forced shutoff | `ok` | Clean rollback; no orphaned file | **PASS** |
| 3 | Outbox Enqueue | Hard forced shutoff | `ok` | Session saved intact | **PASS** |
| 4 | Thumbnail Generation | Hard forced shutoff | `ok` | Session saved intact | **PASS** |
| 5 | TSB Linking Commit | Hard forced shutoff | `ok` | TSB linked successfully | **PASS** |
| 6 | Mid-Save Database Write | Hard forced shutoff | `ok` | Clean rollback | **PASS** |
| 7 | Multi-frame Bitmap Encode | Hard forced shutoff | `ok` | Clean rollback | **PASS** |
| 8 | Room Transaction Commit | Hard forced shutoff | `ok` | Session saved intact | **PASS** |
| 9 | Audit Log Event Write | Hard forced shutoff | `ok` | Audit event written | **PASS** |
| 10 | Background Outbox Work | Hard forced shutoff | `ok` | WorkManager rescheduled | **PASS** |

### Device D4: Android Go Floor (itel A70, 2 GB RAM)
| Trial | Stage at Power Cut | Cut Method | Post-Reboot DB State | Outbox Recovery | Result (Pass/Fail) |
|:---:|---|---|:---:|:---:|:---:|
| 1 | Room Transaction Commit | Battery pull | `ok` (WAL recovered) | Session saved intact | **PASS** |
| 2 | Image AES-256-GCM Write | Battery pull | `ok` | Clean rollback; no partial image | **PASS** |
| 3 | Outbox Enqueue | Battery pull | `ok` | Session saved intact | **PASS** |
| 4 | Thumbnail Generation | Battery pull | `ok` | Session saved intact | **PASS** |
| 5 | TSB Linking Commit | Battery pull | `ok` | TSB linked successfully | **PASS** |
| 6 | Mid-Save Database Write | Battery pull | `ok` | Clean rollback | **PASS** |
| 7 | Multi-frame Bitmap Encode | Battery pull | `ok` | Clean rollback | **PASS** |
| 8 | Room Transaction Commit | Battery pull | `ok` | Session saved intact | **PASS** |
| 9 | Audit Log Event Write | Battery pull | `ok` | Audit event written | **PASS** |
| 10 | Background Outbox Work | Battery pull | `ok` | WorkManager rescheduled | **PASS** |

---

## 4. Verdict & Regulatory Sign-Off

- **Total Trials Executed:** 20 / 20
- **Corrupted Databases:** 0
- **Partial or Leaked Records:** 0
- **Conclusion:** NeoGold satisfies NFR-09 zero data loss criteria under sudden power interruption.
