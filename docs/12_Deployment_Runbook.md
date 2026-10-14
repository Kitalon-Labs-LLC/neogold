# NeoGold: Deployment Runbook

| Field | Value |
|---|---|
| Document | 12 of 13 |
| Version | 0.1.2 (organization Play account and second approver confirmed; v1.0 follows Path B, PRD D10) |
| Date | 11 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| Depends on | `4_System_Architecture.md` v0.2.4 (Sections 6, 10, 12, 14), `5_Database_Schema.md` v0.3.1, `7_API_Documentation.md` v0.2, `8_Security_Compliance_Plan.md` v0.2.1, `9_Test_Plan_Strategy.md` v0.1 |
| Status | Awaiting founder review |

Confidence tags: **[Certain]** verified by running it or from official documentation, **[Likely]** strong inference, **[Guessing]** confirm before relying on it.

**Validation of the files in this runbook (11 October 2026)** [Certain for each item]:

- The three GitHub Actions workflows pass actionlint with ShellCheck; every shell script passes ShellCheck.
- `docker-compose.yml` passes `docker compose config`; the Caddyfile passes `caddy validate` (Caddy 2.11.7); the backup timer passes `systemd-analyze calendar`.
- The release gates (G1, S4, S5, S6) were run against good and bad fixtures and failed exactly where they should.
- `neogold-deploy` was run with stubbed Docker through a first deploy, a normal deploy, a failed smoke test, a container that fails its health check, `--rollback`, and `--restore`; it rolled back correctly in every failure case, never ran migrations with an older image (old code cannot migrate a newer schema), and left Caddy stopped in restore mode.
- The `neogold-bootstrap` SQL was run on PostgreSQL 16: the migrator created the full schema, the application role could read and write but not delete, the export role could only read, and the purge role could delete.
- Two real bugs were found and fixed in testing: the 16 KB script treated `2**` as a wildcard and passed every library, and the schema needed a privilege the migrator should not have (the `pgcrypto` extension, now dropped because PostgreSQL 13 and later have `gen_random_uuid()` built in).
- An independent reviewer then found 15 problems in the first draft of this runbook and its test cases, all fixed before delivery. The most serious: Caddy would not have started at all, a restore would have silently undone revocations and consent withdrawals, and a deploy whose new image failed to start would not have rolled back.

What could not be tested here: no Docker daemon was available, so the containers were not actually started. The Caddy fix below rests on the official image's Dockerfile, which sets a file capability on the binary [Certain, read 11 October 2026]; TC-OPS-11 confirms it on the first staging setup.

---

## 0. Read This First

1. **The first production release cannot be staged** [Certain, Play Console help]. For v1.0.0 the stages are the testing tracks, plus Bangladesh-only availability and managed publishing. Staged percentages apply from v1.0.1.
2. **There is no fast kill switch, by design.** The app is offline, so the quickest way to stop a dangerous triage result is a new release through Play review with an immediate-update priority. Play review can take hours to days [Likely]. The halt procedure (Section 6) is therefore rehearsed before v1.0, and the `validUntil` expiry in the config is the backstop.
3. **Play cannot roll back a version** [Certain that halting leaves updated users on the new version; Play Console help]. Rollback means a new, higher `versionCode` with the bad change reverted. Because the on-device database migrates forward only, a "rollback" build must keep the newest schema. It is made by reverting commits on the release branch, never by rebuilding an old tag.
4. **v1.0 follows Path B** (PRD D10, 11 October 2026): a closed research build on Play closed testing, with triage hidden. Path A stays documented for a later public release, which needs the study results and the legal opinion (G3) first.

---

## 1. Environments and Access

| Environment | Where | Who has access | Data |
|---|---|---|---|
| Source and CI | GitHub repository, GitHub Actions | Maintainers with hardware-key or TOTP 2FA | None |
| Play Console | Kitalon Labs developer account | Founder (owner) and the second approver (release manager role) | Store listing, vitals |
| Staging server | Dedicated DigitalOcean droplet, BLR1, 1 vCPU and 2 GiB, Volume for data (doc 4 Section 10.2a) | Founder over WireGuard and SSH | Synthetic only |
| Production study server | Partner infrastructure or Bangladeshi data center | Partner IT and coordinator; founder only if the partner grants it | Real study data |
| Offline signing machine | Air-gapped laptop (doc 8 Section 4) | Two named key holders | Card issuer and study issuer keys |

**Identifiers.** Application ID `com.kitalonlabs.neogold` (matching the existing `com.kitalonlabs.payradar` pattern). Backend image `ghcr.io/<github-org>/neogold-intake`, deployed by digest only.

---

## 2. Versioning and Branching

- **App versions:** Semantic Versioning in `versionName` (`1.0.0`). Tags are `v1.0.0`; the release workflow refuses a tag that does not match `versionName`.
- **`versionCode`:** `major × 1,000,000 + minor × 10,000 + patch × 100 + build`, with minor, patch, and build from 0 to 99. v1.0.0 build 1 is `1000001`. The scheme stays far below Play's ceiling for `versionCode` and always increases [Likely; Play's maximum is about 2.1 billion].

```kotlin
// android/app/build.gradle.kts (excerpt)
val appVersionName = "1.0.0"   // single source of truth
val buildNumber = providers.gradleProperty("buildNumber").map(String::toInt).getOrElse(1)
fun versionCodeOf(name: String, build: Int): Int {
    val (major, minor, patch) = name.split(".").map(String::toInt)
    require(minor in 0..99 && patch in 0..99 && build in 0..99) { "version part out of range" }
    return major * 1_000_000 + minor * 10_000 + patch * 100 + build
}
android { defaultConfig { versionName = appVersionName; versionCode = versionCodeOf(appVersionName, buildNumber) } }
tasks.register("printVersionName") { doLast { println(appVersionName) } }
```

- **Backend versions:** tags `intake-v1.0.0`; deployments always name the image digest, never a tag.
- **Branches:** trunk-based on `main`. A release branch `release/1.0` is cut at code freeze; fixes go to `main` first and are cherry-picked. Hotfixes branch from the release branch.
- **Database changes:** on the phone, Room migrations only move forward. On the server, migrations are expand-only within a release (add columns or tables, never drop or rename), and the contract step that removes old structures ships one release later. This keeps the previous backend image runnable after a migration, which makes image rollback safe (Section 7.5). The rule is enforced, not just reviewed: the `schema-compat` CI job runs the previous release's test suite against the new schema and blocks the image build if it fails.

---

## 3. CI/CD Pipelines

| Workflow | Trigger | What it does | Secrets |
|---|---|---|---|
| `android-ci.yml` (Appendix A.1) | Pull request; push to `main` and `release/**`; called by the release workflow | Lint, unit, golden vectors, differential triage, config schema, unsigned release build, gates S4, S5, S6, requirement coverage; separate scan job with gate S9 (gitleaks) and Trivy | None |
| `android-release.yml` (Appendix A.2) | Tag `v*.*.*` | Re-runs all of `android-ci.yml` on the tagged commit first; then gate G1, tag check, signed bundle, signed asset manifest, SBOM, gates S4 and S5 on that bundle, upload of **that same file** to the internal track, draft GitHub release with checksums of the uploaded files | Upload key, release manifest key (K3), Play service account; only in the protected `release` environment; deleted in an always-run final step |
| `backend-ci.yml` (Appendix A.3) | Pull request, push to `main`, tag `intake-v*.*.*` | Ruff, Bandit, pytest (with the ten authentication cases), spec validation and server-spec diff, `schema-compat`, image build with SBOM and provenance, image scan | `GITHUB_TOKEN` with `packages: write` in the image job only |

Two gates are not automated in CI and are recorded in the test summary instead: the MobSF scan (S1, TC-SEC-10) and the instrumented UI tests on emulators, which run nightly. Gradle dependency locking is on, with `gradle.lockfile` files committed, so the Trivy scan can see Maven dependencies.

**Supply-chain rules (doc 8 T12):**

- Every action is pinned to a full commit SHA, with the version in a comment. The SHAs in Appendix A were resolved from the actions' release tags on 11 October 2026 [Certain]. Dependabot proposes updates.
- Scanner jobs run with no secrets and a read-only token. This is not hypothetical: on 19 March 2026, attackers force-pushed malicious code to 75 of 76 version tags of `aquasecurity/trivy-action`, and the payload stole CI secrets while the scan appeared to run normally [Certain, Snyk analysis]. A pinned SHA plus a secret-free job makes that class of attack harmless here. Verify the pinned Trivy commit against Aqua's advisory before adopting it.
- The `release` environment lists the founder and the second approver as required reviewers, with GitHub's "prevent self-review" setting on, so whoever pushed the tag cannot approve it. Branch protection on `main` and `release/**` requires the same two-person review.
- CI never holds server credentials. Deployment is pull-based: an admin on the server runs `neogold-deploy` with the digest that CI printed (Section 7.3).

---

## 4. Android Release Procedure

### 4.1 Release candidate (every release)

| Step | Action | Evidence |
|---|---|---|
| 1 | Cut `release/X.Y` from `main`; set `versionName`; freeze strings | Commit |
| 2 | Clinical lead signs the config: `CLINICAL_SIGNOFF.md` with the canonical config hash, config `clinicalSignoff.status` set to `SIGNED`, `validUntil` within 12 months (Appendix B.1) | Signed record in the repository |
| 3 | Run the release-candidate suite (doc 9 Section 7) and the device matrix (doc 9 Section 4.2) | Test summary report |
| 4 | Push tag `vX.Y.Z`; approve the `release` environment | Workflow run |
| 5 | Workflow uploads to the **internal testing** track | Play Console release |
| 6 | Install from the internal track on D1 to D4; run the smoke suite (TC-ONB-01, TC-DEV-01, TC-CARD-01, a full screening, a research session, TC-SYNC-01) | Signed smoke checklist |

**Builds with triage off.** Research builds (v0.5, Path B) and clinical-emergency builds set `triageEnabled` to false in the config. Such a build can never show a triage result, so G1 passes it without a sign-off record (Appendix B.1, TC-TRI-26).

**Hash canonicalization.** `CLINICAL_SIGNOFF.md` records the SHA-256 of the config as canonical JSON (sorted keys, no whitespace, UTF-8) **with the `clinicalSignoff` block removed**, and the config's `clinicalSignoff.signoffRecordSha256` records the hash of the sign-off file. Hashing the whole config would be circular, since the config contains the hash of the record that contains the hash of the config.

### 4.2 Path A: later public release with triage (needs G3)

| Stage | Track | Audience | Minimum duration | Exit criteria |
|---|---|---|---|---|
| A1 | Internal testing | Founder and team, up to 100 testers [Certain this is the internal track's limit] | 2 days | Smoke suite passes on D1 to D4 |
| A2 | Closed testing, "partner-pilot" list | Partner nurses and study staff | 14 days | Doc 9 Section 8 closed-testing exit. No minimum tester count, because Kitalon Labs publishes from an organization account [Certain that Play's 12-tester rule covers only new personal accounts] |
| A3 | Production, **Bangladesh only**, 100%, with managed publishing | Public in Bangladesh | Go-live at a chosen time | Production checklist (Section 9) complete; release notes from doc 13 |

Managed publishing holds an approved release until you press publish, so go-live can be timed for a working day morning in Dhaka when the founder and clinical lead are reachable.

### 4.3 Path B: the v1.0 plan (closed research build)

| Stage | Track | Audience |
|---|---|---|
| B1 | Internal testing | As A1 |
| B2 | Closed testing, research build with `triageEnabled` false (PRD Section 13, v0.5 scope) | Partner study sites under ethics approval |

No production listing shows a triage result. Source code, model, card design, and docs are still released on GitHub under the open licenses. Path B becomes Path A only after the study results, a signed config, and the legal opinion, starting again at A1 with a new build. Partner ethics approval must be in hand before any study site installs the build.

### 4.4 Play Console declarations (before A2)

| Item | Setting |
|---|---|
| Health apps declaration | Matches the legal opinion (doc 8 Section 13.2): medical device declared only if counsel says so, with proof of approval held |
| Store listing disclaimer | If not declared a medical device, Google's required wording that the app is not a medical device and does not diagnose, treat, cure, or prevent any medical condition [Certain, Play policy] |
| Data safety form | Screening: no data collected. Research: health information and photos sent to the study server, encrypted in transit (doc 8 Section 13.2) |
| Privacy policy | Public web page, not a PDF |
| Target audience | Adults |
| Target API level | Android 16 (API 36), required for new apps and updates since 31 August 2026 [Certain, multiple sources on Play's 2026 requirement] |
| Countries | Bangladesh only for v1.0 |

---

## 5. Staged Rollouts for Updates (v1.0.1 Onward)

| Stage | Users | Hold before next stage |
|---|---|---|
| 1 | 5% | 48 hours |
| 2 | 20% | 48 hours |
| 3 | 50% | 72 hours |
| 4 | 100% | |

Percentages do not rise automatically [Certain]; each step is a deliberate action after checking the criteria below. With a small user base the percentages mean little, so each step also needs a confirmation from at least one partner site that the new version works on their phones [Likely; a few hundred installs give Android vitals too little data to rely on].

**Halt immediately if any of these happens:**

| Signal | Source |
|---|---|
| Any report that NeoGold missed a baby who needed treatment, or showed a result it should not have | Partner, health worker, Play review, GitHub |
| Any S1 defect in the new version | Defect log |
| Any report of lost research data | Partner, outbox telemetry on the study server (`status`) |
| User-perceived crash rate above 1.09% or ANR rate above 0.47% overall, or above 8% on any phone model [Certain, Android vitals bad-behavior thresholds] | Play Console vitals |
| Crash rate above 2% on any device-matrix model (stricter internal limit) | Play Console vitals by device |

**Hold (do not advance) if** two or more new S2 defects arrive within 48 hours, or a partner site reports a problem that cannot be reproduced yet.

---

## 6. Halt, Roll Forward, and Emergency Release

### 6.1 Halting a rollout

1. Play Console, Production, **Halt rollout** (the founder or the second approver, whoever sees the signal first). No new users receive the version; users who already have it keep it [Certain, Play Console help].
2. Log the incident (doc 8 Section 12); if clinical, notify the clinical lead within 1 hour.
3. Post a short notice to partner sites: what happened, whether to keep using the app, and when to expect a fix. Never include baby details.

### 6.2 Rolling forward ("rollback")

1. On the release branch, revert the commits that caused the problem. Keep any database migration that already shipped.
2. Bump `patch`, rebuild, and run the release-candidate suite for the touched areas plus the smoke suite.
3. Upload with in-app update priority 4 (or 5 for clinical problems), which makes NeoGold start the immediate update flow (NG-055).
4. Release to the affected audience at 100%; the halted version is superseded.

Priority can only be set when the release is created through the Play Developer API, not changed later [Certain, in-app updates guide]. The release workflow sets it with the Gradle Play Publisher `updatePriority` property [Likely supported in version 4.x]. TC-OPS-10 checks whether the priority survives promotion between tracks; if it does not, emergency releases are uploaded straight to production with the priority set.

### 6.3 Clinical emergency

| Time from report | Action |
|---|---|
| 0 to 1 hour | Halt any rollout in progress; incident record; clinical lead informed |
| Within 24 hours | Clinical lead decides: disable triage (`triageEnabled` false, which G1 accepts at once), change thresholds (needs a new sign-off), or no change |
| Within 48 hours | Emergency build from the release branch, triage disabled if decided; internal track smoke on D1 to D4 for at least 2 hours; production at 100% with priority 5 |
| After release | Partner notice; hazard log updated; post-incident review within 14 days |

A config change is an app release in v1.0, because the config ships inside the APK (doc 4 Section 12). Over-the-air config updates (NG-062) would shorten this path; they require offline signing of K3 first (doc 8 Section 4).

---

## 7. Backend Deployment

### 7.1 Server layout

```
/srv/neogold/                 all on the encrypted Volume
  compose/                    docker-compose.yml, Caddyfile, caddy/Dockerfile, .env (includes API_DIGEST),
                              neogold-deploy, smoke.sh, .current-digest, .previous-digest
  images/                     research images, owner 10001; upload temp files in images/.tmp
  exports/                    server exports, owner 10001; excluded from backups; emptied after hand-over
  pg/                         PostgreSQL data, owner 70
  backup/                     dump staging for the nightly backup, owner 70
  caddy/data, caddy/config    certificates and Caddy state, owner 1000
/etc/neogold/                 api.env, db.env, migrate.env, roles.env, backup.env (root-owned, mode 0600)
/usr/local/bin/               neogold-admin (host wrapper), neogold-backup, neogold-bootstrap
/etc/systemd/system/          neogold-backup.service, neogold-backup.timer
```

`docker-compose.yml`, the `Caddyfile`, and the Caddy `Dockerfile` are in Appendix C. Every container runs as a non-root user with all capabilities dropped, `no-new-privileges`, a read-only root filesystem, and size-capped `/tmp`. PostgreSQL is on an internal network with no published port.

- **Caddy.** The official image gives `/usr/bin/caddy` the file capability `cap_net_bind_service` [Certain, official Dockerfile]; with all capabilities dropped, the kernel refuses to run such a binary. NeoGold builds a two-line derived image that removes the capability, and Caddy listens on unprivileged ports 8080 and 8443 inside the container, mapped from host ports 80 and 443.
- **Client addresses.** Caddy has a fixed address (172.30.0.10) on the edge network, and `api.env` sets `FORWARDED_ALLOW_IPS=172.30.0.10`, so the API trusts `X-Forwarded-For` from Caddy and from nothing else. Without it, every request would appear to come from Caddy, and the per-IP enrollment limit would block every site at once (TC-API-33).
- **Image digest.** `API_DIGEST` lives in `.env`, written only by `neogold-deploy`, so every `docker compose` command (backups, admin commands, restarts) uses the image that is actually deployed.

### 7.2 First-time server setup (staging)

| Step | Command or action |
|---|---|
| 1 | Create the droplet (Ubuntu 24.04 LTS, BLR1, 1 vCPU and 2 GiB), a Volume for `/srv/neogold`, and a DigitalOcean cloud firewall allowing only 80, 443, and the WireGuard UDP port |
| 2 | Create named admin users with SSH keys; set `PasswordAuthentication no` and `PermitRootLogin no`; configure WireGuard; bind SSH to the WireGuard interface |
| 3 | `apt install docker.io docker-compose-v2 restic unattended-upgrades`; enable unattended security upgrades with a 03:00 Asia/Dhaka reboot window |
| 4 | Set journald `MaxRetentionSec=90day` (request log retention, doc 8 Section 11) |
| 5 | Create `/srv/neogold/{images,images/.tmp,exports}` (owner 10001), `/srv/neogold/{pg,backup}` (owner 70), `/srv/neogold/caddy/{data,config}` (owner 1000); create `/etc/neogold/*.env` with generated secrets (K12, K13, K14), including `POSTGRES_DB=neogold` in `db.env` and the four role passwords in `roles.env` |
| 6 | Copy the compose directory; set `.env` with `PUBLIC_HOST`, `ACME_EMAIL`, `CADDY_BASE`, `POSTGRES_IMAGE`, `API_IMAGE`, and the first `API_DIGEST`; `docker compose build caddy` |
| 7 | Initialize the restic repository (`restic init`) at the backup location; enable `neogold-backup.timer` |
| 8 | `sudo neogold-bootstrap` (Appendix D.5): starts the database and creates the roles and default privileges |
| 9 | First deploy: `sudo neogold-deploy sha256:<digest>`. With no earlier deploy recorded, it skips the pre-deploy backup |
| 10 | `neogold-admin study create` for a synthetic study, then run the staging part of the release-candidate suite and TC-OPS-11 |

The partner's production setup follows the same steps on its own infrastructure, with backups to a second location inside Bangladesh (doc 8 Section 13.1).

### 7.3 Deploying a new backend image

1. CI prints the image digest in the workflow summary (`backend-ci.yml`, image job).
2. Read the release notes for any migration. Expand-only migrations need no maintenance window; anything else needs `neogold-admin study pause` first and a notice to sites.
3. On the server: `sudo neogold-deploy sha256:<digest>` (Appendix D.1). The script takes a backup, writes the new digest into `.env`, pulls, migrates, restarts, and runs the smoke test (Appendix D.2). **Any** failure after the migration starts (a failed migration, a container that fails its health check, or a failed smoke test) restores the previous digest, restarts it, and re-runs the smoke test.
4. Staging only: run Schemathesis and the ten authentication cases against the new deployment (TC-API-08, TC-API-30).

### 7.4 Production smoke test

After a production deploy, the coordinator runs the phone side of the smoke test with a phone enrolled at a **test site** (`ZZTEST`), created with `site add --test`, which every export skips: one participant with a `ZZT-` code, one session with two images, one TSB value. Then `neogold-admin participant withdraw ZZT-<n>` removes the session, images, and TSB value. No real participant is touched.

### 7.5 Rolling back the backend

| Situation | Action |
|---|---|
| New image misbehaves, schema fine | `sudo neogold-deploy --rollback` (no migration, because the older code cannot migrate the newer schema; expand-only migrations keep it working; the script updates `.env` and the digest files and re-runs the smoke test, whose self-test accepts the newer schema) |
| Migration damaged data | Section 8.2, which redeploys the previous digest before Caddy is restarted |
| Server compromised | Doc 8 Section 12; rebuild from scratch rather than repair |

---

## 8. Backups and Restore

### 8.1 Backups

The nightly timer runs `neogold-backup` (Appendix D.3). It writes a `pg_dump` file and the database roles (`pg_dumpall --globals-only`), refuses a truncated dump (`pg_restore --list`), and puts the dump, the roles, and the image store into **one** restic snapshot, so a restore always gets a matching database and image set. Retention is 7 daily, 5 weekly, and 12 monthly snapshots on fixed paths, so pruning works, and a weekly integrity check reads 5% of the data. restic encrypts with AES-256 and authenticates with Poly1305-AES [Certain]. Every deploy also takes a backup first.

### 8.2 Restore procedure

A restore puts the database back to the snapshot, which also undoes every admin action taken since: device revocations, participant withdrawals, study pauses, and minimum-version changes. The order below re-applies them before any phone can connect.

1. `neogold-admin study pause`, then `docker compose stop caddy`. **Caddy stays stopped until step 8**, so no phone reaches the server mid-restore.
2. Record what the restore will undo: `neogold-admin audit since <snapshot time> > /root/restore-<date>-actions.txt` from the live database if it is readable, and in any case the admin command lines in the host journal (`journalctl -t neogold-admin --since <snapshot time>`).
3. Check the snapshot's age. If it is older than 13 days, phones have already deleted some acknowledged data (14-day local retention, doc 7 Section 5.4); write down what will be lost and get the coordinator's sign-off before continuing.
4. `docker compose stop api`; restore the one snapshot: `restic restore <snapshot-id> --target /restore`; replace `/srv/neogold/images` with `/restore/srv/neogold/images`; copy `/restore/srv/neogold/backup/*` into `/srv/neogold/backup/` (mounted in the database container at `/backup`); then `docker compose exec -T db psql -U postgres -f /backup/globals.sql` ("role already exists" messages are expected on a server that still has its roles) and `docker compose exec -T db pg_restore -U postgres -d neogold --clean /backup/neogold.dump`.
5. Start the API only, on the last good image: `sudo neogold-deploy --restore sha256:<digest>`. Restore mode skips the backup and migrations, leaves Caddy stopped, and runs only the internal self-test.
6. Re-apply every action from step 2 (`device revoke`, `participant withdraw`, `study close`, `set-min-app-version`) and confirm each in `audit_log`. Withdrawal also deletes images the restore brought back.
7. `neogold-admin restore mark`: sets `data_generation` to the time of the restore, so phones re-send everything they still hold, including TSB links and participant aliases (doc 7 Section 5.4).
8. `docker compose start caddy`; `neogold-admin study resume`.
9. Record the restore, the re-applied actions, and any accepted loss in the incident register.

### 8.3 Monthly restore test (gate S8)

Each environment tests **its own** repository where it lives. Staging restores its repository on staging. Production restores the partner's repository on scratch infrastructure **inside Bangladesh**, never on the DigitalOcean staging droplet, because study data must not leave the country or touch staging (doc 8 Section 13.1). The test restores the latest snapshot into a scratch database and directory, runs `neogold-admin selftest` against it, compares row counts and image hashes with the source, and records the repository and snapshot ID. A backup that has never been restored does not count (doc 8 Section 5.4).

---

## 9. Production Release Checklist (Go or No-Go)

| # | Item | Owner | Evidence |
|---|---|---|---|
| 1 | G1: clinical sign-off verified by the release workflow | Clinical lead, founder | Workflow log |
| 2 | G2: verified card batches distributed to every launch site | Founder | Batch register and signing log |
| 3 | G3: legal opinion received and Play declarations aligned (Path A only) | Founder | Opinion letter on file |
| 4 | S1 to S9 passed (doc 8 Section 13.3) | Founder | Test summary report |
| 5 | Doc 9 exit criteria for closed testing met; no open S1 or S2 | Founder | Defect log summary |
| 6 | Usability round 2 passed (doc 9 Section 13) | Founder, nurse panel | Session notes |
| 7 | Release notes in English and Bangla, each under 500 characters for the Play field [Certain, Play limit] | Founder | Doc 13 |
| 8 | Signing certificate SHA-256 fingerprint published on the website and README (doc 8 T3) | Founder | Link |
| 9 | SBOM and checksums attached to the GitHub release | CI | Release page |
| 10 | Halt drill done within the last 90 days (TC-OPS-06) | Founder | Drill record |
| 11 | Clinical lead and founder reachable for 72 hours after go-live | Both | Calendar |
| 12 | Partner sites told the go-live date and how to report problems | Founder | Message sent |
| 13 | Second approver's go decision recorded | Second approver | Approval in the `release` environment |

Any "no" stops the release.

---

## 10. Study Operations

| Task | Steps |
|---|---|
| Set up a study | `study create` with ethics reference, retention date, and time zone; `site add` per site; offline machine: `neogold-sign study-profile` with host and export public key; print enrollment sheets (signed QR) |
| Enroll phones | `code issue --site <code> --count <n>`; staff scan the study QR, enter the code, confirm study name and host (doc 7 Section 4.1); check `device list` for attestation results |
| Lost or stolen phone | `device revoke <id> --reason LOST`; check `status` for uploads after the loss; incident record |
| Phone handed back | Upload now (or USB export) until nothing is waiting; then Leave study in the app; then delete local data. Leaving first would delete the device key and make the upload impossible |
| Pause or close | `study pause` (phones keep data and retry later); `study close` at the end (phones offer USB export) |
| Raise minimum app version | `study set-min-app-version`, at least 14 days after announcing it (doc 7 Section 9) |
| Export for analysis | `export --study <code> --out /data/exports/<name>`; verify the SHA-256 manifest; hand over under the data sharing agreement; delete from `/srv/neogold/exports` |
| Decrypt a USB export from a phone | On the coordinator's own offline machine, never the server: `neogold-admin decrypt-export --key <study export key> <file>` |
| Retention | Monthly `retention check`; `retention purge` only with the coordinator's written approval |
| Consent withdrawn | `participant withdraw <participant code>`: rejects every session, deletes images and TSB values, and refuses later uploads for that participant |

---

## 11. Card Batch Release

| Step | Action |
|---|---|
| 1 | Print batch at the print partner per the Card Production Kit (NG-045) |
| 2 | Lab measures a sample of cards; results within batch tolerance (TC-CARD-10) |
| 3 | On the offline machine: `neogold-sign card-batch` with batch ID, measured values, issue and expiry dates; record in the signing log |
| 4 | Print the signed QR onto the batch; spot-check 5 cards with the app (TC-CARD-01) |
| 5 | Distribute with a batch register entry: batch ID, quantity, sites |

New batches need no app release, because the app trusts the issuer key, not a list of batches. **Revoking** a batch does need an app release, because the revocation list ships in the signed trust list. Until that release reaches phones, tell sites to withdraw the cards physically.

---

## 12. Monitoring After Release

| What | Where | How often |
|---|---|---|
| Crash and ANR rates, by device model | Play Console, Android vitals | Daily during a rollout, weekly after |
| Reviews and ratings | Play Console | Daily during a rollout |
| Field reports | Partner sites, GitHub issues (form in doc 11 Section 8) | As they arrive |
| Security reports | `SECURITY.md` contact | As they arrive; acknowledge within 3 working days |
| Server health, disk, backups, certificates, failed authentication | Alerts from doc 8 Section 8.5 | Automatic |
| Upload backlog per device | `neogold-admin status` | Weekly during a study |

NeoGold has no analytics by design (FR-34), so Play's own vitals and direct reports are all there is. That is a deliberate privacy trade-off, and it makes the partner check-ins in Section 5 more important.

---

## 13. Secrets and Key Rotation

| Key (doc 8 Section 4) | Rotation | Procedure |
|---|---|---|
| K2 Upload key | On compromise | Play Console upload key reset; update the `release` environment secret |
| K3 Release manifest key | Each major version | Generate, update the public key in the app, update the secret; move to offline signing before NG-062 |
| K4 Card issuer, K5 Study issuer | Every 2 years or on compromise | Generate on the offline machine; add the new public key to the trust list in an app release; keep the old key valid until its batches or profiles expire |
| K11 TLS | Automatic | Caddy renews; alert at 14 days to expiry |
| K12 Enrollment code secret | On compromise | Replace in `api.env`; unused codes become invalid; reissue |
| K13 Database passwords | Yearly | `ALTER ROLE ... PASSWORD`; update env files; `docker compose up -d` (safe, because `.env` always names the deployed digest) |
| K14 Backup password | Per study, or on compromise | New repository: `restic init` at a new location, `restic copy` the snapshots into it, delete the old repository. Adding and removing a password keeps the same master key, so it does not protect old copies of the repository |
| K15 SSH and WireGuard | On staff change | Remove the person's keys and peer entry the same day |

---

## 14. Open Issues for Review

1. **Update priority through promotion:** confirm in TC-OPS-10 whether in-app update priority set on the internal track carries into production.
2. **Production hosting partner:** the runbook assumes the partner runs Docker Compose; if the partner requires another platform, Section 7 needs a variant.

---

## Sources

- [Play Console help: staged rollouts](https://support.google.com/googleplay/android-developer/answer/6346149?hl=en)
- [Android developers: in-app updates](https://developer.android.com/guide/playcore/in-app-updates/kotlin-java)
- [Android vitals: bad behavior thresholds](https://developer.android.com/games/optimize/vitals)
- [Google Play target API requirement for 2026](https://median.co/blog/google-plays-target-api-level-requirement-for-android-apps)
- [Closed testing requirement for new personal accounts](https://extendsclass.com/blog/google-plays-closed-testing-requirement-what-developers-need-to-know-in-2026)
- [Play release notes 500-character limit](https://deku.posstree.com/ko/share/google-play/release-notes-character-limit/)
- [Snyk: Trivy GitHub Actions supply chain compromise (March 2026)](https://snyk.io/articles/trivy-github-actions-supply-chain-compromise/)
- [Android developers: 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [restic: cryptography reference](https://restic.readthedocs.io/en/stable/100_references.html)

---

## Appendix A: CI Workflows

### A.1 `.github/workflows/android-ci.yml`

```yaml
name: android-ci
on:
  pull_request:
    paths: ["android/**", "config/**", "testdata/**", "tools/**", "docs/**", ".github/workflows/android-ci.yml"]
  push:
    branches: [main, "release/**"]
  workflow_call: {}
permissions:
  contents: read
concurrency:
  group: android-ci-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build-test:
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0
      - name: Lint, unit, golden vectors, differential triage
        working-directory: android
        run: ./gradlew --no-daemon lintRelease detekt testReleaseUnitTest verifyGoldenVectors triageDifferentialTest verifyConfigSchema
      - name: Build unsigned release
        working-directory: android
        run: ./gradlew --no-daemon assembleRelease
      - name: Gate S6, network security config
        run: python3 tools/check_network_config.py android/app/src/main/res/xml/network_security_config.xml
      - name: Gate S5, merged manifest allow-lists
        run: python3 tools/check_manifest.py "$(find android/app/build/intermediates -path '*release*' -name AndroidManifest.xml | head -n 1)"
      - name: Gate S4, 16 KB alignment
        run: |
          PATH="$(dirname "$(find "$ANDROID_HOME/build-tools" -name zipalign | sort -V | tail -n 1)"):$PATH"
          PATH="$(dirname "$(find "$ANDROID_NDK_LATEST_HOME" -name llvm-objdump | head -n 1)"):$PATH"
          tools/check_16kb.sh android/app/build/outputs/apk/release/app-release-unsigned.apk
      - name: Requirement coverage (doc 9 Section 10)
        run: python3 tools/trace.py docs
  scan:
    # No secrets in this job: a compromised scanner has nothing to steal (doc 8 T12).
    # Trivy reads Maven dependencies from the committed gradle.lockfile files (dependency locking is on).
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          fetch-depth: 0
      - name: Gate S9, secrets scan
        run: |
          curl -fsSL -o "$RUNNER_TEMP/gitleaks.tgz" \
            https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz
          echo "551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb  $RUNNER_TEMP/gitleaks.tgz" | sha256sum -c -
          tar -xzf "$RUNNER_TEMP/gitleaks.tgz" -C "$RUNNER_TEMP" gitleaks
          "$RUNNER_TEMP/gitleaks" git --redact --exit-code 1 .
      - uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0, verify before adopting
        with:
          scan-type: fs
          scan-ref: android
          severity: CRITICAL,HIGH
          exit-code: "1"
```

### A.2 `.github/workflows/android-release.yml`

```yaml
name: android-release
on:
  push:
    tags: ["v*.*.*"]
permissions:
  contents: read
jobs:
  verify:
    # The tagged commit is re-tested here; a green pull request is not enough (doc 9 Section 7).
    uses: ./.github/workflows/android-ci.yml
  release:
    needs: verify
    runs-on: ubuntu-24.04
    timeout-minutes: 60
    environment: release   # requires reviewer approval in repository settings
    permissions:
      contents: write      # create the GitHub release
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-java@de7274f081f381c8f8158605e0321c36c376e2e6 # v6.0.1
        with:
          distribution: temurin
          java-version: "21"
      - uses: gradle/actions/setup-gradle@3f5f9adaf7d9fecd50b5935e54106014257a94e6 # v6.4.0
      - name: Gate G1, clinical sign-off
        run: python3 tools/verify_clinical_signoff.py config/triage-config.json CLINICAL_SIGNOFF.md
      - name: Tag matches versionName
        working-directory: android
        run: |
          version="$(./gradlew -q printVersionName)"
          test "v${version}" = "${GITHUB_REF_NAME}" || { echo "tag ${GITHUB_REF_NAME} does not match versionName ${version}"; exit 1; }
      - name: Build signed bundle and sign the asset manifest
        working-directory: android
        env:
          UPLOAD_KEYSTORE_B64: ${{ secrets.UPLOAD_KEYSTORE_B64 }}
          UPLOAD_KEYSTORE_PASSWORD: ${{ secrets.UPLOAD_KEYSTORE_PASSWORD }}
          UPLOAD_KEY_ALIAS: ${{ secrets.UPLOAD_KEY_ALIAS }}
          UPLOAD_KEY_PASSWORD: ${{ secrets.UPLOAD_KEY_PASSWORD }}
          RELEASE_MANIFEST_KEY_B64: ${{ secrets.RELEASE_MANIFEST_KEY_B64 }}
        run: |
          umask 077
          printf '%s' "$UPLOAD_KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/upload.jks"
          printf '%s' "$RELEASE_MANIFEST_KEY_B64" | base64 -d > "$RUNNER_TEMP/manifest.key"
          ./gradlew --no-daemon signAssetManifest bundleRelease cyclonedxBom \
            -PuploadKeystore="$RUNNER_TEMP/upload.jks" -PmanifestKey="$RUNNER_TEMP/manifest.key"
          mkdir -p "$RUNNER_TEMP/upload"
          cp app/build/outputs/bundle/release/app-release.aab "$RUNNER_TEMP/upload/"
      - name: Install bundletool and put SDK and NDK tools on PATH
        run: |
          curl -fsSL -o "$RUNNER_TEMP/bundletool.jar" \
            https://github.com/google/bundletool/releases/download/1.18.3/bundletool-all-1.18.3.jar
          echo "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29  $RUNNER_TEMP/bundletool.jar" | sha256sum -c -
          printf '#!/bin/sh\nexec java -jar "%s" "$@"\n' "$RUNNER_TEMP/bundletool.jar" > "$RUNNER_TEMP/bundletool"
          chmod +x "$RUNNER_TEMP/bundletool"
          {
            echo "$RUNNER_TEMP"
            dirname "$(find "$ANDROID_HOME/build-tools" -name zipalign | sort -V | tail -n 1)"
            dirname "$(find "$ANDROID_NDK_LATEST_HOME" -name llvm-objdump | head -n 1)"
          } >> "$GITHUB_PATH"
      - name: Gates S4 and S5 on the release bundle
        run: |
          bundletool build-apks --bundle="$RUNNER_TEMP/upload/app-release.aab" \
            --output="$RUNNER_TEMP/universal.apks" --mode=universal
          unzip -o -q "$RUNNER_TEMP/universal.apks" universal.apk -d "$RUNNER_TEMP"
          tools/check_16kb.sh "$RUNNER_TEMP/universal.apk"
          python3 tools/check_manifest.py "$(find android/app/build/intermediates -path '*release*' -name AndroidManifest.xml | head -n 1)"
      - name: Publish the checked bundle to the internal testing track (no rebuild)
        working-directory: android
        env:
          ANDROID_PUBLISHER_CREDENTIALS: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
        run: ./gradlew --no-daemon publishReleaseBundle --track internal --artifact-dir "$RUNNER_TEMP/upload"
      - name: GitHub release with SBOM and checksums
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          cp android/app/build/reports/bom.json "$RUNNER_TEMP/upload/"
          (cd "$RUNNER_TEMP/upload" && sha256sum app-release.aab bom.json > SHA256SUMS)   # the exact files uploaded
          gh release create "${GITHUB_REF_NAME}" --draft --title "NeoGold ${GITHUB_REF_NAME}" \
            --notes-file docs/release-notes/"${GITHUB_REF_NAME}".md \
            "$RUNNER_TEMP/upload/bom.json" "$RUNNER_TEMP/upload/SHA256SUMS"
      - name: Remove signing material
        if: always()
        run: rm -f "$RUNNER_TEMP/upload.jks" "$RUNNER_TEMP/manifest.key"
```

### A.3 `.github/workflows/backend-ci.yml`

```yaml
name: backend-ci
on:
  pull_request:
    paths: ["backend/**", "docs/api/openapi.yaml", ".github/workflows/backend-ci.yml"]
  push:
    branches: [main]
    tags: ["intake-v*.*.*"]
permissions:
  contents: read
jobs:
  test:
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_PASSWORD: test
        ports: ["5432:5432"]
        options: >-
          --health-cmd "pg_isready -U postgres" --health-interval 5s --health-timeout 5s --health-retries 10
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
        with:
          python-version: "3.12"
      - name: Install locked dependencies
        working-directory: backend
        run: pip install --require-hashes -r requirements.lock
      - name: Lint and static analysis
        working-directory: backend
        run: |
          ruff check .
          bandit -q -r app
      - name: Tests (including doc 7 Section 12.3 authentication cases)
        working-directory: backend
        env:
          DATABASE_URL: postgresql://postgres:test@localhost:5432/postgres
        run: pytest -q
      - name: Spec lint and server-spec diff
        run: |
          python3 -m openapi_spec_validator docs/api/openapi.yaml
          python3 backend/tools/diff_spec.py docs/api/openapi.yaml
  schema-compat:
    # Expand-only check (doc 12 Section 2): the PREVIOUS release's tests must pass against the NEW schema.
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    services:
      postgres:
        image: postgres:16
        env:
          POSTGRES_PASSWORD: test
        ports: ["5432:5432"]
        options: >-
          --health-cmd "pg_isready -U postgres" --health-interval 5s --health-timeout 5s --health-retries 10
    env:
      DATABASE_URL: postgresql://postgres:test@localhost:5432/postgres
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
        with:
          fetch-depth: 0
      - uses: actions/setup-python@5fda3b95a4ea91299a34e894583c3862153e4b97 # v7.0.0
        with:
          python-version: "3.12"
      - name: Migrate to the new head
        working-directory: backend
        run: |
          pip install --require-hashes -r requirements.lock
          alembic upgrade head
      - name: Run the previous release's tests against it
        run: |
          prev="$(git describe --tags --abbrev=0 --match 'intake-v*' HEAD^ 2>/dev/null || true)"
          if [ -z "$prev" ]; then echo "no previous release yet"; exit 0; fi
          git worktree add "$RUNNER_TEMP/prev" "$prev"
          cd "$RUNNER_TEMP/prev/backend"
          pip install --require-hashes -r requirements.lock
          pytest -q --no-migrate    # previous code, current schema
  image:
    if: github.event_name == 'push'
    needs: [test, schema-compat]
    runs-on: ubuntu-24.04
    timeout-minutes: 30
    permissions:
      contents: read
      packages: write
    outputs:
      digest: ${{ steps.build.outputs.digest }}
    steps:
      - uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
      - uses: docker/login-action@dbcb813823bdd20940b903addbd779551569679f # v4.6.0
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ github.token }}
      - id: build
        uses: docker/build-push-action@c3c9e263c25d99ce0380d002d59b67737d91b0dc # v7.4.0
        with:
          context: backend
          push: true
          provenance: true
          sbom: true
          tags: ghcr.io/${{ github.repository_owner }}/neogold-intake:${{ github.sha }}
      - name: Record the digest for deployment
        env:
          DIGEST: ${{ steps.build.outputs.digest }}
        run: |
          echo "Deploy with: neogold-deploy ${DIGEST}" >> "$GITHUB_STEP_SUMMARY"
  scan:
    # Separate job, no secrets, read-only token (doc 8 T12).
    needs: image
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    steps:
      - uses: aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25 # v0.36.0, verify before adopting
        with:
          image-ref: ghcr.io/${{ github.repository_owner }}/neogold-intake@${{ needs.image.outputs.digest }}
          severity: CRITICAL,HIGH
          exit-code: "1"
```

## Appendix B: Release Gate Scripts

### B.1 `tools/verify_clinical_signoff.py` (gate G1)

The sign-off record it reads is a Markdown file with these lines:

```markdown
- **config_version:** 2027.01.0
- **config_sha256:** <canonical hash>
- **signed_by:** <name of the clinical lead>
- **registration:** <BMDC registration number>
- **signed_on:** 2027-01-20
- **statement:** I have reviewed the thresholds, rules, and wording in this configuration.
```

```python
#!/usr/bin/env python3
"""Release gate G1 (doc 8 Section 13.3): the triage config is clinically signed off.

Usage: verify_clinical_signoff.py config/triage-config.json CLINICAL_SIGNOFF.md [--today YYYY-MM-DD]

A config with "triageEnabled": false (research builds, Path B, or a clinical emergency that
disables triage) passes without a sign-off, because such a build never shows a triage result.

The config hash is SHA-256 over canonical JSON (sorted keys, no whitespace, UTF-8) with the
`clinicalSignoff` block removed, which avoids the circular dependency between the config and
the sign-off record that hashes it.
"""
import datetime as dt, hashlib, json, re, sys

def canonical_hash(cfg: dict) -> str:
    body = {k: v for k, v in cfg.items() if k != "clinicalSignoff"}
    data = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return hashlib.sha256(data).hexdigest()

def main(argv):
    cfg_path, record_path = argv[1], argv[2]
    today = dt.date.fromisoformat(argv[argv.index("--today") + 1]) if "--today" in argv else dt.date.today()
    cfg = json.load(open(cfg_path, encoding="utf-8"))
    if cfg.get("triageEnabled") is False:
        print(f"G1 PASS: config {cfg.get('configVersion')} has triage disabled; no triage result can be shown")
        return 0
    if cfg.get("triageEnabled") is not True:
        print("G1 FAIL: triageEnabled must be true or false")
        return 1
    record_bytes = open(record_path, "rb").read()
    fields = dict(re.findall(r"^- \*\*(\w+):\*\* (.+)$", record_bytes.decode("utf-8"), re.M))
    errors = []
    signoff = cfg.get("clinicalSignoff", {})
    if signoff.get("status") != "SIGNED":
        errors.append(f"config clinicalSignoff.status is {signoff.get('status')!r}, not 'SIGNED'")
    if fields.get("config_sha256") != canonical_hash(cfg):
        errors.append("config hash does not match config_sha256 in the sign-off record")
    if fields.get("config_version") != cfg.get("configVersion"):
        errors.append("configVersion differs from config_version in the sign-off record")
    if signoff.get("signoffRecordSha256") != hashlib.sha256(record_bytes).hexdigest():
        errors.append("clinicalSignoff.signoffRecordSha256 does not match the sign-off record file")
    try:
        signed_on = dt.date.fromisoformat(fields["signed_on"])
        valid_until = dt.date.fromisoformat(cfg["validUntil"])
        if valid_until <= today:
            errors.append(f"validUntil {valid_until} is not in the future")
        if valid_until > signed_on + dt.timedelta(days=366):
            errors.append("validUntil is more than 12 months after signed_on")
    except (KeyError, ValueError) as e:
        errors.append(f"missing or malformed date: {e}")
    for name in ("signed_by", "registration"):
        if not fields.get(name):
            errors.append(f"sign-off record lacks {name}")
    for e in errors:
        print("G1 FAIL:", e)
    if not errors:
        print(f"G1 PASS: config {cfg['configVersion']} signed by {fields['signed_by']} on {fields['signed_on']}")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv))
```

### B.2 `tools/check_manifest.py` (gate S5)

```python
#!/usr/bin/env python3
"""Release gate S5 (doc 8 Section 6.1): merged-manifest allow-lists.

Usage: check_manifest.py path/to/merged/AndroidManifest.xml
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
ALLOWED_PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.WAKE_LOCK",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.USE_BIOMETRIC",
    "android.permission.USE_FINGERPRINT",
}
# exported components allowed, with the permission that must guard them (None = launcher)
ALLOWED_EXPORTED = {
    "com.kitalonlabs.neogold.MainActivity": None,
    "androidx.work.impl.background.systemjob.SystemJobService": "android.permission.BIND_JOB_SERVICE",
    "androidx.work.impl.diagnostics.DiagnosticsReceiver": "android.permission.DUMP",
    "androidx.profileinstaller.ProfileInstallerReceiver": "android.permission.DUMP",
}

def main(path):
    root = ET.parse(path).getroot()
    pkg = root.get("package", "")
    app = root.find("application")
    errors = []
    if app.get(A + "allowBackup") != "false":
        errors.append("allowBackup must be false")
    for attr in ("dataExtractionRules", "fullBackupContent", "networkSecurityConfig"):
        if not app.get(A + attr):
            errors.append(f"application lacks android:{attr}")
    if app.get(A + "debuggable") == "true":
        errors.append("release manifest is debuggable")
    if app.get(A + "usesCleartextTraffic") == "true":
        errors.append("usesCleartextTraffic is true")
    for p in root.findall("uses-permission") + root.findall("uses-permission-sdk-23"):
        name = p.get(A + "name")
        own = name == f"{pkg}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" or name.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        if name not in ALLOWED_PERMISSIONS and not own:
            errors.append(f"permission not allow-listed: {name}")
    for tag in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in app.findall(tag):
            name = c.get(A + "name")
            exported = c.get(A + "exported")
            if exported is None and c.find("intent-filter") is not None:
                errors.append(f"{tag} {name} has an intent-filter but no explicit android:exported")
            if exported == "true":
                if name not in ALLOWED_EXPORTED:
                    errors.append(f"exported {tag} not allow-listed: {name}")
                elif ALLOWED_EXPORTED[name] and c.get(A + "permission") != ALLOWED_EXPORTED[name]:
                    errors.append(f"exported {tag} {name} is not guarded by {ALLOWED_EXPORTED[name]}")
    for e in errors:
        print("S5 FAIL:", e)
    if not errors:
        print("S5 PASS: manifest allow-lists satisfied")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
```

### B.3 `tools/check_16kb.sh` (gate S4)

```bash
#!/usr/bin/env bash
# Release gate S4 (doc 8 Section 6.1): every native library is 16 KB aligned.
# Usage: check_16kb.sh app.apk   (needs zipalign and llvm-objdump from the Android SDK and NDK)
set -euo pipefail
apk="${1:?usage: check_16kb.sh app.apk}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
zipalign -c -P 16 -v 4 "$apk" > "$work/zipalign.txt" || { echo "S4 FAIL: zip alignment"; tail -5 "$work/zipalign.txt"; exit 1; }
unzip -q "$apk" 'lib/*' -d "$work" 2>/dev/null || true
fail=0
while IFS= read -r -d '' so; do
  case "$so" in */arm64-v8a/*|*/x86_64/*) ;; *) continue ;; esac   # 16 KB pages apply to 64-bit ABIs
  while read -r align; do
    exp="${align#"2**"}"
    if [ "$exp" -lt 14 ]; then echo "S4 FAIL: ${so#"$work"/} LOAD segment aligned 2**$exp"; fail=1; fi
  done < <(llvm-objdump -p "$so" | awk '/LOAD/ {for (i=1;i<=NF;i++) if ($i=="align") print $(i+1)}')
done < <(find "$work/lib" -name '*.so' -print0 2>/dev/null)
[ "$fail" -eq 0 ] && echo "S4 PASS: zip and ELF alignment satisfied"
exit "$fail"
```

### B.4 `tools/check_network_config.py` (gate S6)

```python
#!/usr/bin/env python3
"""Release gate S6 (doc 8 Section 5.3): network security config.

Usage: check_network_config.py android/app/src/main/res/xml/network_security_config.xml
Passes only if cleartext is off everywhere, trust anchors are system CAs only, and no
debug overrides or domain configs weaken that.
"""
import sys
import xml.etree.ElementTree as ET

def main(path):
    root = ET.parse(path).getroot()
    errors = []
    base = root.find("base-config")
    if base is None:
        errors.append("no <base-config>")
    else:
        if base.get("cleartextTrafficPermitted") != "false":
            errors.append('base-config must set cleartextTrafficPermitted="false"')
        anchors = base.find("trust-anchors")
        srcs = [c.get("src") for c in anchors.findall("certificates")] if anchors is not None else []
        if srcs != ["system"]:
            errors.append(f"base-config trust anchors must be exactly ['system'], found {srcs}")
    for dc in root.iter("domain-config"):
        if dc.get("cleartextTrafficPermitted") == "true":
            errors.append("a domain-config permits cleartext")
        for c in dc.iter("certificates"):
            if c.get("src") != "system":
                errors.append(f"a domain-config trusts {c.get('src')}")
    if root.find("debug-overrides") is not None:
        errors.append("debug-overrides present in the release config")
    for e in errors:
        print("S6 FAIL:", e)
    if not errors:
        print("S6 PASS: cleartext off, system CAs only")
    return 1 if errors else 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
```

### B.5 `tools/trace.py` (requirement coverage, doc 9 Section 10)

```python
#!/usr/bin/env python3
"""Coverage and traceability check for NeoGold docs (doc 9 Section 10).

Usage: trace.py <docs_dir> [--write]
Fails (exit 1) if a requirement, hazard, threat, or gate has no test case, or a test
case cites an ID that does not exist. With --write, regenerates doc 10 Section 21.
"""
import re, sys, pathlib, collections

docs = pathlib.Path(sys.argv[1])
prd = (docs / "2_PRD.md").read_text()
sec = (docs / "8_Security_Compliance_Plan.md").read_text()
backlog = (docs / "3_Feature_Backlog.md").read_text()
tc_path = docs / "10_Test_Cases.md"
tc = tc_path.read_text()

known = {
    "FR": set(re.findall(r"^- (FR-\d+[a-z]?) ", prd, re.M)),
    "NFR": set(re.findall(r"^\| (NFR-\d+[a-z]?) \|", prd, re.M)),
    "H": set(re.findall(r"^\| (H\d+) \|", sec, re.M)),
    "T": set(re.findall(r"^\| (T\d+) \|", sec, re.M)),
    "Gate": {f"Gate {g}" for g in re.findall(r"^\| \*{0,2}(S\d)\*{0,2} ", sec, re.M)},
    "NG": set(re.findall(r"^\| (NG-\d{3}) \|", backlog, re.M)),
}
pattern = {
    "FR": r"\bFR-\d+[a-z]?\b", "NFR": r"\bNFR-\d+[a-z]?\b", "H": r"\bH\d+\b",
    "T": r"\bT\d+\b", "Gate": r"\bGate S\d\b", "NG": r"\bNG-\d{3}\b",
}
cases = collections.OrderedDict()
body = tc.split("## 20. Hazard Verification Map")[0]
for line in body.splitlines():
    m = re.match(r"^\| (TC-[A-Z0-9]+-\d{2}) \| [^|]+ \| ([^|]+) \|", line)
    if m:
        cases[m.group(1)] = m.group(2)
cov = {k: collections.defaultdict(list) for k in known}
problems = []
for tcid, verifies in cases.items():
    for kind, pat in pattern.items():
        for ref in re.findall(pat, verifies):
            if ref not in known[kind]:
                problems.append(f"{tcid} cites unknown {ref}")
            cov[kind][ref].append(tcid)
for kind in ("FR", "NFR", "H", "T", "Gate"):
    for ref in sorted(known[kind], key=lambda x: (len(x), x)):
        if ref not in cov[kind]:
            problems.append(f"no test case covers {ref}")
dupes = [k for k, v in collections.Counter(re.findall(r"^\| (TC-[A-Z0-9]+-\d{2}) \|", body, re.M)).items() if v > 1]
problems += [f"duplicate test case ID {d}" for d in dupes]

def natural(x): return [int(t) if t.isdigit() else t for t in re.split(r"(\d+)", x)]
if "--write" in sys.argv:
    out = ["| Requirement | Test cases |", "|---|---|"]
    for kind in ("FR", "NFR", "NG", "H", "T", "Gate"):
        for ref in sorted(cov[kind], key=natural):
            out.append(f"| {ref} | {', '.join(cov[kind][ref])} |")
    head = tc.split("Generated from the tables above by `tools/trace.py`; do not edit by hand.")[0]
    tc_path.write_text(head + "Generated from the tables above by `tools/trace.py`; do not edit by hand.\n\n" + "\n".join(out) + "\n")
print(f"{len(cases)} test cases; " + ", ".join(f"{k}: {len(cov[k])}/{len(known[k])} covered" for k in known))
for p in problems: print("PROBLEM:", p)
sys.exit(1 if problems else 0)
```

## Appendix C: Server Configuration

### C.1 `docker-compose.yml`

```yaml
# /srv/neogold/compose/docker-compose.yml  (doc 12 Section 7.1)
name: neogold
x-hardening: &hardening
  read_only: true
  cap_drop: [ALL]
  security_opt: ["no-new-privileges:true"]
  restart: unless-stopped
  logging:
    driver: journald            # journald MaxRetentionSec=90day on the host (doc 8 Section 11)
    options:
      tag: "neogold-{{.Name}}"

services:
  caddy:
    <<: *hardening
    build:
      context: ./caddy
      args:
        CADDY_BASE: ${CADDY_BASE:?pin the official caddy image by digest in .env}
    image: neogold-caddy:local
    user: "1000:1000"
    ports: ["80:8080", "443:8443"]   # unprivileged ports inside the container
    environment:
      PUBLIC_HOST: ${PUBLIC_HOST:?}
      ACME_EMAIL: ${ACME_EMAIL:?}
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - /srv/neogold/caddy/data:/data
      - /srv/neogold/caddy/config:/config
    tmpfs: ["/tmp:size=16m"]
    networks:
      edge:
        ipv4_address: 172.30.0.10      # the only proxy the API trusts (FORWARDED_ALLOW_IPS in api.env)
    depends_on:
      api:
        condition: service_healthy

  api:
    <<: *hardening
    image: ${API_IMAGE:?}@${API_DIGEST:?set API_DIGEST, see neogold-deploy}
    user: "10001:10001"
    env_file: /etc/neogold/api.env    # DATABASE_URL, PUBLIC_BASE_URL, ENROLLMENT_CODE_SECRET, FORWARDED_ALLOW_IPS=172.30.0.10 (mode 0600)
    volumes:
      - /srv/neogold/images:/data/images     # encrypted volume; upload temp files in /data/images/.tmp, same filesystem
      - /srv/neogold/exports:/data/exports   # encrypted volume; excluded from backups; emptied after hand-over
    tmpfs: ["/tmp:size=64m"]
    networks: [edge, internal]
    depends_on:
      db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/v1/health', timeout=3)"]
      interval: 30s
      timeout: 5s
      retries: 3
    deploy:
      resources:
        limits: {cpus: "1.0", memory: 768M}

  db:
    <<: *hardening
    image: ${POSTGRES_IMAGE:?pin postgres 16 by digest in .env}
    user: "70:70"                     # postgres user in the Alpine image; data dir pre-owned by 70
    env_file: /etc/neogold/db.env     # POSTGRES_DB=neogold, POSTGRES_PASSWORD for the superuser (bootstrap only)
    volumes:
      - /srv/neogold/pg:/var/lib/postgresql/data
      - /srv/neogold/backup:/backup          # dump staging for neogold-backup; on the encrypted volume
    tmpfs: ["/tmp:size=64m", "/var/run/postgresql:size=1m"]
    networks: [internal]
    healthcheck:
      test: ["CMD", "pg_isready", "-U", "postgres"]
      interval: 10s
      timeout: 5s
      retries: 5
    deploy:
      resources:
        limits: {cpus: "1.0", memory: 1G}

  migrate:
    <<: *hardening
    profiles: [ops]
    restart: "no"
    image: ${API_IMAGE:?}@${API_DIGEST:?}
    user: "10001:10001"
    env_file: /etc/neogold/migrate.env   # credentials of neogold_migrator only
    command: ["alembic", "upgrade", "head"]
    tmpfs: ["/tmp:size=16m"]
    networks: [internal]
    depends_on:
      db:
        condition: service_healthy

networks:
  edge:
    ipam:
      config:
        - subnet: 172.30.0.0/24
  internal:
    internal: true
```

### C.2 `Caddyfile`

```caddyfile
# /srv/neogold/compose/Caddyfile  (doc 12 Section 7.2)
{
	http_port 8080
	https_port 8443
	email {$ACME_EMAIL}
	servers {
		timeouts {
			read_header 10s
			read_body 15m
			write 15m
			idle 2m
		}
	}
}

{$PUBLIC_HOST} {
	header {
		Strict-Transport-Security "max-age=31536000"
		X-Content-Type-Options "nosniff"
		Referrer-Policy "no-referrer"
		-Server
	}

	@image path_regexp image ^/v1/sessions/[^/]+/captures/[^/]+/image$
	handle @image {
		request_body {
			max_size 20MB
		}
		reverse_proxy api:8000
	}

	handle /v1/* {
		request_body {
			max_size 256KB
		}
		reverse_proxy api:8000
	}

	handle {
		respond 404
	}

	log {
		output stdout
		format json
	}
}
```

### C.3 `caddy/Dockerfile`

```dockerfile
# NeoGold Caddy image (doc 12 Section 7.1). The official image gives /usr/bin/caddy a file
# capability (cap_net_bind_service). With cap_drop ALL the kernel refuses to run such a binary,
# and NeoGold listens on unprivileged ports anyway, so the capability is removed here.
ARG CADDY_BASE
FROM ${CADDY_BASE}
RUN setcap -r /usr/bin/caddy
USER 1000:1000
```

## Appendix D: Server Scripts and Units

### D.1 `neogold-deploy`

```bash
#!/usr/bin/env bash
# Deploy one backend image by digest, with automatic rollback (doc 12 Section 7.3).
# Usage: neogold-deploy sha256:<digest>            deploy that image (backup, migrate, start, smoke)
#        neogold-deploy --rollback                 return to the previous image (no migration)
#        neogold-deploy --restore sha256:<digest>  after a restore (doc 12 Section 8.2): API only,
#                                                  no backup, no migration, Caddy left stopped
set -euo pipefail
cd /srv/neogold/compose

set_digest() {   # write API_DIGEST into .env atomically; every compose command reads it from there
  local tmp
  tmp="$(mktemp .env.XXXXXX)"
  grep -v '^API_DIGEST=' .env > "$tmp" || true
  printf 'API_DIGEST=%s\n' "$1" >> "$tmp"
  chmod 0600 "$tmp"
  mv -f "$tmp" .env
}
current="$(cat .current-digest 2>/dev/null || true)"
previous="$(cat .previous-digest 2>/dev/null || true)"
mode=deploy
case "${1:-}" in
  --rollback) mode=rollback; target="$previous" ;;
  --restore)  mode=restore;  target="${2:-}" ;;
  *)          target="${1:-}" ;;
esac
[[ "$target" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "usage: neogold-deploy sha256:<digest> | --rollback | --restore sha256:<digest>" >&2; exit 2; }

# An older image runs on a newer, expand-only schema; its self-test must accept that.
if [[ "$mode" != deploy ]]; then export NEOGOLD_ALLOW_NEWER_SCHEMA=1; fi
if [[ "$mode" == restore ]]; then export NEOGOLD_SMOKE_INTERNAL_ONLY=1; services=(api); else services=(api caddy); fi

if [[ "$mode" == deploy && -n "$current" ]]; then
  echo "1/5 Pre-deploy backup"
  systemctl start neogold-backup.service          # oneshot; returns when the backup has finished
else
  echo "1/5 Backup skipped (${mode}; first deploy, rollback, or restore)"
fi

echo "2/5 Pull ${target}"
set_digest "$target"
docker compose pull api

rollback() {
  trap - ERR
  echo "Deploy failed; restoring ${current:-nothing}" >&2
  if [[ -n "$current" && "$mode" == deploy ]]; then
    set_digest "$current"
    export NEOGOLD_ALLOW_NEWER_SCHEMA=1
    if docker compose up -d --wait "${services[@]}" && ./smoke.sh; then
      echo "Rolled back to ${current}; smoke test passed" >&2
    else
      echo "ROLLBACK ALSO FAILED: pause the study and follow doc 12 Section 7.5" >&2
    fi
  fi
  exit 1
}
trap rollback ERR

if [[ "$mode" == deploy ]]; then
  echo "3/5 Migrate (expand-only; checked in CI by the schema-compat job)"
  docker compose --profile ops run --rm migrate
else
  echo "3/5 Migration skipped: an older image cannot migrate a newer schema"
fi
echo "4/5 Start ${services[*]}"
docker compose up -d --wait "${services[@]}"
echo "5/5 Smoke test"
./smoke.sh
trap - ERR

if [[ "$target" != "$current" ]]; then
  printf '%s\n' "$current" > .previous-digest
  printf '%s\n' "$target" > .current-digest
fi
logger -t neogold-deploy "${mode}: ${target} (was ${current:-none})"
echo "Deployed ${target} (${mode})"
```

### D.2 `smoke.sh`

```bash
#!/usr/bin/env bash
# Post-deploy smoke test (doc 12 Section 7.3). Exits non-zero on any failure.
# NEOGOLD_SMOKE_INTERNAL_ONLY=1 skips the public check (Caddy stopped during a restore).
# NEOGOLD_ALLOW_NEWER_SCHEMA=1 lets an older image accept a newer expand-only schema.
set -euo pipefail
cd /srv/neogold/compose
if [[ "${NEOGOLD_SMOKE_INTERNAL_ONLY:-0}" != 1 ]]; then
  host="$(grep -E '^PUBLIC_HOST=' .env | cut -d= -f2-)"
  curl -fsS --max-time 10 "https://${host}/v1/health" | grep -q '"status":"ok"'
fi
# Self-test: DB reachable, schema at this image's head (or newer if allowed), image store writable,
# last backup under 26 hours old.
docker compose exec -T -e NEOGOLD_ALLOW_NEWER_SCHEMA="${NEOGOLD_ALLOW_NEWER_SCHEMA:-0}" api neogold-admin selftest
echo "smoke ok"
```

### D.3 `neogold-backup`

```bash
#!/usr/bin/env bash
# Nightly encrypted backup with restic (doc 8 Section 5.4, doc 12 Section 8).
# Environment from /etc/neogold/backup.env: RESTIC_REPOSITORY, RESTIC_PASSWORD_FILE (mode 0600).
# Database dump, roles, and images go into ONE snapshot, so a restore always gets a matching set.
set -euo pipefail
cd /srv/neogold/compose
stage=/srv/neogold/backup                    # encrypted volume, mounted in db at /backup
rm -f "$stage"/neogold.dump "$stage"/globals.sql
docker compose exec -T db pg_dump -U postgres -Fc -f /backup/neogold.dump neogold
docker compose exec -T db pg_dumpall -U postgres --globals-only -f /backup/globals.sql
docker compose exec -T db pg_restore --list /backup/neogold.dump > /dev/null   # refuse a truncated dump
restic backup "$stage" /srv/neogold/images --tag nightly --exclude /srv/neogold/images/.tmp
restic forget --tag nightly --keep-daily 7 --keep-weekly 5 --keep-monthly 12 --prune
if [[ "$(date -u +%u)" == "7" ]]; then restic check --read-data-subset=5%; fi
rm -f "$stage"/neogold.dump "$stage"/globals.sql
logger -t neogold-backup "backup complete"
```

### D.4 `neogold-backup.service` and `neogold-backup.timer`

```ini
[Unit]
Description=NeoGold encrypted backup
After=docker.service
Requires=docker.service

[Service]
Type=oneshot
EnvironmentFile=/etc/neogold/backup.env
ExecStart=/usr/local/bin/neogold-backup
```

```ini
[Unit]
Description=Nightly NeoGold backup

[Timer]
OnCalendar=*-*-* 02:30:00 Asia/Dhaka
Persistent=true

[Install]
WantedBy=timers.target
```

### D.5 `neogold-bootstrap`

```bash
#!/usr/bin/env bash
# One-time database setup before the first deploy (doc 12 Section 7.2, step 8).
# Reads role passwords from /etc/neogold/roles.env: MIGRATOR_PASSWORD, APP_PASSWORD, EXPORT_PASSWORD, PURGE_PASSWORD.
set -euo pipefail
cd /srv/neogold/compose
# shellcheck disable=SC1091
source /etc/neogold/roles.env
docker compose up -d --wait db
docker compose exec -T db psql -v ON_ERROR_STOP=1 -U postgres -d neogold \
  -v migrator_pw="$MIGRATOR_PASSWORD" -v app_pw="$APP_PASSWORD" \
  -v export_pw="$EXPORT_PASSWORD" -v purge_pw="$PURGE_PASSWORD" <<'SQL'
CREATE ROLE neogold_migrator LOGIN PASSWORD :'migrator_pw';
CREATE ROLE neogold_app      LOGIN PASSWORD :'app_pw';
CREATE ROLE neogold_export   LOGIN PASSWORD :'export_pw';
CREATE ROLE neogold_purge    LOGIN PASSWORD :'purge_pw';
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO neogold_migrator;
GRANT USAGE ON SCHEMA public TO neogold_app, neogold_export, neogold_purge;
-- Tables created later by migrations get these grants automatically (doc 5 Section 3.5).
ALTER DEFAULT PRIVILEGES FOR ROLE neogold_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE ON TABLES TO neogold_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neogold_migrator IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO neogold_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neogold_migrator IN SCHEMA public
  GRANT SELECT ON TABLES TO neogold_export;
ALTER DEFAULT PRIVILEGES FOR ROLE neogold_migrator IN SCHEMA public
  GRANT SELECT, DELETE ON TABLES TO neogold_purge;
SQL
echo "Roles created. Migrations grant DELETE on auth_nonce and idempotency_record to neogold_app and"
echo "revoke UPDATE on audit_log (doc 5 Section 3.5). Next: neogold-deploy sha256:<digest>"
```

### D.6 `neogold-admin` (host wrapper)

Admins always run `sudo neogold-admin ...` on the host. The wrapper writes the command line to the host journal before running it inside the API container, which is what step 2 of the restore procedure reads.

```bash
#!/usr/bin/env bash
# Host wrapper for the admin CLI (doc 7 Section 10). Every command line is written to the host
# journal before it runs, so admin actions survive a database restore (doc 12 Section 8.2).
set -euo pipefail
cd /srv/neogold/compose
logger -t neogold-admin -- "user=${SUDO_USER:-$USER} args=$*"
exec docker compose exec -T api neogold-admin "$@"
```
