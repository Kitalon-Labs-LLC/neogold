# Staging Setup and Operational Procedures Checklist

| Field | Value |
|---|---|
| Document | `docs/test-reports/staging-setup.md` |
| Reference | `docs/12_Deployment_Runbook.md` Sections 7, 8 and Appendices C, D |
| Test Cases | **TC-OPS-11** (First-Time Server Setup), **TC-OPS-04** (Deploy & Rollback), **TC-OPS-05** (Restore Drill), **TC-SEC-11** (Server Exposure), **TC-SEC-12** (TLS Settings), **TC-SEC-13** (Backups Encrypted & Restorable) |
| Target Host | DigitalOcean Droplet (`BLR1`, Ubuntu 24.04 LTS, 1 vCPU, 2 GiB RAM, Dedicated Encrypted Volume) |
| Operator | Sowad Al-Mughni, Kitalon Labs (commands run manually by operator) |
| Safety Boundary | **Zero automated remote execution**: Agent prepares instructions; operator verifies and executes on host. |

---

## 1. Prerequisites and Pinned References

Before provisioning, ensure you have the image digest produced by CI for `neogold-intake`:
```bash
# Obtain from GitHub Actions summary (backend-ci.yml -> image job)
export API_DIGEST="sha256:<64-character-hex-digest>"
export API_IMAGE="ghcr.io/kitalon-labs-llc/neogold-intake"
```

Pinned infrastructure base images (from runbook Appendix C):
- **Caddy base**: `caddy:2.11-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f` (Caddy 2.11.7)
- **PostgreSQL base**: `postgres:16-alpine@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36` (PostgreSQL 16.8)

---

## 2. Phase 1: Host Hardening & OS Configuration (Runbook 7.2 Steps 1–4)

### 2.1 DigitalOcean Droplet & Network Firewall (Step 1)
- Create Ubuntu 24.04 LTS Droplet (BLR1, 1 vCPU, 2 GiB RAM).
- Attach an encrypted DigitalOcean Block Storage Volume formatted with ext4, mounted at `/srv/neogold`.
- In DigitalOcean Cloud Firewall, configure inbound rules:
  - TCP `80` (HTTP ACME challenge) -> `0.0.0.0/0`, `::/0`
  - TCP `443` (HTTPS) -> `0.0.0.0/0`, `::/0`
  - UDP `51820` (WireGuard VPN) -> `0.0.0.0/0`, `::/0`
  - **All other ports (including 22 SSH) DROPPED from public internet**.

### 2.2 WireGuard & SSH Hardening (Step 2)
On the droplet:
```bash
# 1. Install WireGuard
sudo apt update && sudo apt install -y wireguard

# 2. Configure WireGuard interface (wg0)
# (Setup server keys and peer config for operator laptop)
sudo systemctl enable --now wg-quick@wg0

# 3. Harden SSH to listen ONLY on WireGuard internal IP (e.g., 10.10.0.1)
sudo tee /etc/ssh/sshd_config.d/99-neogold-hardening.conf <<'EOF'
Port 22
ListenAddress 10.10.0.1
PasswordAuthentication no
PermitRootLogin no
PubkeyAuthentication yes
X11Forwarding no
MaxAuthTries 3
EOF

sudo systemctl restart ssh
```

### 2.3 Required Packages & Security Upgrades (Step 3)
```bash
# Install Docker Compose v2, restic, and unattended-upgrades
sudo apt update && sudo apt install -y \
  docker.io \
  docker-compose-v2 \
  restic \
  unattended-upgrades \
  curl \
  jq

# Enable automatic security updates with 03:00 Asia/Dhaka reboot window
sudo tee /etc/apt/apt.conf.d/51unattended-upgrades-neogold <<'EOF'
Unattended-Upgrade::Automatic-Reboot "true";
Unattended-Upgrade::Automatic-Reboot-Time "03:00";
Unattended-Upgrade::Remove-Unused-Kernel-Packages "true";
Unattended-Upgrade::Remove-Unused-Dependencies "true";
EOF

sudo systemctl enable --now unattended-upgrades
```

### 2.4 Journald Retention (Step 4, TC-SEC-11, doc 8 Section 11)
```bash
# Configure 90-day request log retention for compliance
sudo tee /etc/systemd/journald.conf.d/99-neogold.conf <<'EOF'
[Journal]
MaxRetentionSec=90day
SystemMaxUse=4G
EOF

sudo systemctl restart systemd-journald
```

---

## 3. Phase 2: Directory Layout & Environment Files (Runbook 7.2 Step 5)

### 3.1 Create Storage Directories with Least-Privilege Owners
```bash
sudo mkdir -p /srv/neogold/images/.tmp /srv/neogold/exports
sudo mkdir -p /srv/neogold/pg /srv/neogold/backup
sudo mkdir -p /srv/neogold/caddy/data /srv/neogold/caddy/config
sudo mkdir -p /srv/neogold/compose/caddy
sudo mkdir -p /etc/neogold

# Set strict ownership:
# API user (10001:10001) owns image storage and exports
sudo chown -R 10001:10001 /srv/neogold/images /srv/neogold/exports
sudo chmod 0750 /srv/neogold/images /srv/neogold/exports

# Postgres user (70:70 in Alpine) owns pg data and backup staging
sudo chown -R 70:70 /srv/neogold/pg /srv/neogold/backup
sudo chmod 0700 /srv/neogold/pg /srv/neogold/backup

# Caddy user (1000:1000) owns certificates and config
sudo chown -R 1000:1000 /srv/neogold/caddy
sudo chmod 0700 /srv/neogold/caddy
```

### 3.2 Generate Cryptographic Secrets and Environment Files
```bash
# Generate high-entropy secrets (K12, K13, K14)
export DB_SU_PW="$(openssl rand -hex 32)"
export MIG_PW="$(openssl rand -hex 32)"
export APP_PW="$(openssl rand -hex 32)"
export EXP_PW="$(openssl rand -hex 32)"
export PURGE_PW="$(openssl rand -hex 32)"
export CODE_SECRET="$(openssl rand -hex 32)"
export RESTIC_PW="$(openssl rand -hex 32)"

# 1. db.env (Postgres superuser credentials, used only at bootstrap)
sudo tee /etc/neogold/db.env <<EOF
POSTGRES_DB=neogold
POSTGRES_PASSWORD=${DB_SU_PW}
EOF

# 2. roles.env (Application and service role passwords for neogold-bootstrap)
sudo tee /etc/neogold/roles.env <<EOF
MIGRATOR_PASSWORD=${MIG_PW}
APP_PASSWORD=${APP_PW}
EXPORT_PASSWORD=${EXP_PW}
PURGE_PASSWORD=${PURGE_PW}
EOF

# 3. migrate.env (Used exclusively by the Alembic migrator container)
sudo tee /etc/neogold/migrate.env <<EOF
DATABASE_URL=postgresql://neogold_migrator:${MIG_PW}@db:5432/neogold
EOF

# 4. api.env (Intake API runtime configuration, restricted to neogold_app role)
sudo tee /etc/neogold/api.env <<EOF
DATABASE_URL=postgresql://neogold_app:${APP_PW}@db:5432/neogold
PUBLIC_BASE_URL=https://staging.neogold.example/v1
ENROLLMENT_CODE_SECRET=${CODE_SECRET}
FORWARDED_ALLOW_IPS=172.30.0.10
TRUST_LIST_PATH=/app/config/trust-list.json
IMAGE_STORE_DIR=/data/images
EXPORT_DATABASE_URL=postgresql://neogold_export:${EXP_PW}@db:5432/neogold
PURGE_DATABASE_URL=postgresql://neogold_purge:${PURGE_PW}@db:5432/neogold
EOF

# 5. backup.env & restic password file
echo -n "${RESTIC_PW}" | sudo tee /etc/neogold/restic-password > /dev/null
sudo tee /etc/neogold/backup.env <<EOF
RESTIC_REPOSITORY=/srv/neogold/restic-repo
RESTIC_PASSWORD_FILE=/etc/neogold/restic-password
EOF

# Lock down all environment files to root:root mode 0600
sudo chmod 0600 /etc/neogold/*.env /etc/neogold/restic-password
sudo chown -R root:root /etc/neogold
```

---

## 4. Phase 3: Compose Configuration & Scripts Installation (Runbook 7.2 Step 6)

### 4.1 Copy Deployment Files to Server
Copy files from repository `backend/deploy/` into `/srv/neogold/compose/`:
- `docker-compose.yml` -> `/srv/neogold/compose/docker-compose.yml`
- `Caddyfile` -> `/srv/neogold/compose/Caddyfile`
- `caddy/Dockerfile` -> `/srv/neogold/compose/caddy/Dockerfile`
- `neogold-deploy` -> `/srv/neogold/compose/neogold-deploy`
- `smoke.sh` -> `/srv/neogold/compose/smoke.sh`

Install host administration and backup utilities into `/usr/local/bin/`:
```bash
sudo cp backend/deploy/neogold-admin /usr/local/bin/neogold-admin
sudo cp backend/deploy/neogold-backup /usr/local/bin/neogold-backup
sudo cp backend/deploy/neogold-bootstrap /usr/local/bin/neogold-bootstrap
sudo chmod 0755 /usr/local/bin/neogold-*
sudo chmod 0755 /srv/neogold/compose/neogold-deploy /srv/neogold/compose/smoke.sh
```

### 4.2 Initialize Compose `.env` File
```bash
sudo tee /srv/neogold/compose/.env <<EOF
PUBLIC_HOST=staging.neogold.example
ACME_EMAIL=ops@neogold.example
CADDY_BASE=caddy:2.11-alpine@sha256:eb4d35b7452d1ab6171fbdbe8d9275b2301dad6dad92115d91e19fb9b24d510f
POSTGRES_IMAGE=postgres:16-alpine@sha256:f4610795587492cd4fb887c2dc3de301b813278bb6f616992061141b5f15ac36
API_IMAGE=ghcr.io/kitalon-labs-llc/neogold-intake
API_DIGEST=${API_DIGEST}
EOF
sudo chmod 0600 /srv/neogold/compose/.env
```

### 4.3 Build Derived Caddy Image (TC-OPS-11 capability check)
```bash
cd /srv/neogold/compose
sudo docker compose build caddy

# Verify the capability-stripped Caddy image runs with cap-drop ALL:
sudo docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges:true \
  --user 1000:1000 neogold-caddy:local caddy version
```
*Expected Output*: Prints `v2.11.7 ...` without capability errors (`exit 0`).

---

## 5. Phase 4: Backup Setup & Timer Activation (Runbook 7.2 Step 7, TC-SEC-13)

### 5.1 Initialize Encrypted Restic Repository
```bash
# Initialize restic repo at /srv/neogold/restic-repo (or S3/Spaces bucket)
sudo mkdir -p /srv/neogold/restic-repo
sudo chown root:root /srv/neogold/restic-repo
sudo chmod 0700 /srv/neogold/restic-repo

sudo restic init \
  --repo /srv/neogold/restic-repo \
  --password-file /etc/neogold/restic-password
```
*Expected Output*: `created restic repository ... at /srv/neogold/restic-repo`

### 5.2 Install Systemd Backup Units
```bash
sudo cp backend/deploy/neogold-backup.service /etc/systemd/system/neogold-backup.service
sudo cp backend/deploy/neogold-backup.timer /etc/systemd/system/neogold-backup.timer
sudo systemctl daemon-reload
sudo systemctl enable --now neogold-backup.timer

# Verify schedule
systemctl list-timers neogold-backup.timer
```
*Expected Output*: Timer active, next trigger scheduled for `02:30:00 Asia/Dhaka`.

---

## 6. Phase 5: Database Bootstrap (Runbook 7.2 Step 8, TC-OPS-11)

Run the one-time database bootstrap script:
```bash
sudo neogold-bootstrap
```
*Expected Output*:
```text
Roles created. Migrations grant DELETE on auth_nonce and idempotency_record to neogold_app and
revoke UPDATE on audit_log (doc 5 Section 3.5). Next: neogold-deploy sha256:<digest>
```

Verify roles created in Postgres:
```bash
sudo docker compose -f /srv/neogold/compose/docker-compose.yml exec -T db \
  psql -U postgres -d neogold -c "\du neogold_*"
```
*Expected Output*: Roles `neogold_migrator`, `neogold_app`, `neogold_export`, `neogold_purge` exist.

---

## 7. Phase 6: First-Time Deployment (Runbook 7.2 Step 9, TC-OPS-11)

Authenticate Docker to GHCR with a read-only token to pull the intake image:
```bash
echo "<GITHUB_READ_TOKEN>" | sudo docker login ghcr.io -u <GITHUB_USERNAME> --password-stdin
```

Execute the first deployment:
```bash
sudo /srv/neogold/compose/neogold-deploy "${API_DIGEST}"
```
*Expected Flow*:
1. `1/5 Backup skipped (deploy; first deploy, rollback, or restore)` (No previous digest yet)
2. `2/5 Pull sha256:...`
3. `3/5 Migrate (expand-only; checked in CI by the schema-compat job)`
4. `4/5 Start api caddy`
5. `5/5 Smoke test` -> `curl -fsS .../v1/health` status: ok -> `neogold-admin selftest` passes -> `smoke ok`
6. `Deployed sha256:... (deploy)`

---

## 8. Phase 7: Study Creation & Staging Setup (Runbook 7.2 Step 10)

Using the host wrapper `neogold-admin` (which audits commands to host journald):
```bash
# 1. Create synthetic study
sudo neogold-admin study create \
  --study-code "SKP" \
  --name "NeoGold Staging Clinical Study" \
  --partner-institution "ICDDR,B" \
  --ethics-ref "ETH-2026-001" \
  --timestamp-precision "DAY_PLUS_OFFSETS" \
  --retention-years 3 \
  --time-zone "Asia/Dhaka" \
  --min-app-version "1.0.0" \
  --wifi-only

# 2. Add test site
sudo neogold-admin site add \
  --study-code "SKP" \
  --site-code "SKP01" \
  --name "Dhaka Medical College Hospital"

# 3. Add dedicated test site for production smoke testing (Runbook 7.4)
sudo neogold-admin site add \
  --study-code "SKP" \
  --site-code "ZZTEST" \
  --name "Coordinator Post-Deploy Verification Site" \
  --test

# 4. Generate enrollment code
sudo neogold-admin code generate \
  --study-code "SKP" \
  --site-code "SKP01" \
  --code "K7QM-3XRA-9TPD" \
  --valid-hours 72
```

---

## 9. Phase 8: TC-OPS-04 Deployment & Rollback Drill

### 9.1 Test Pre-Deploy Backup on Second Deploy
```bash
# Trigger deploy again with the current digest
sudo /srv/neogold/compose/neogold-deploy "${API_DIGEST}"
```
*Expected*: Step `1/5 Pre-deploy backup` executes `systemctl start neogold-backup.service` and succeeds.

### 9.2 Test Automated Failure Rollback (Failing Healthcheck)
Simulate a deployment with a broken/failing container:
```bash
# Intentionally target an image or configuration that fails health check
# neogold-deploy traps ERR, automatically reverts .env to .current-digest,
# starts the previous service containers, and re-executes smoke.sh.
```
*Expected*: Automated rollback completes successfully, journald records the failure and rollback event, and live service stays up on the previous digest.

### 9.3 Test Manual Rollback Command
```bash
sudo /srv/neogold/compose/neogold-deploy --rollback
```
*Expected Output*:
- Sets `NEOGOLD_ALLOW_NEWER_SCHEMA=1`
- Updates `.env` to previous digest
- Skips migration (older code cannot migrate newer schema)
- Restarts containers and executes `smoke.sh`
- Exits 0 with `Deployed sha256:... (rollback)`

---

## 10. Phase 9: TC-OPS-05 Restore Drill (Runbook 8.2 & Gate S8)

Simulate a disaster recovery scenario where a snapshot must be restored without undoing recent administrative actions (device revocations, withdrawals):

### Step 1: Record Admin Actions Before Incident
```bash
# Simulate a device revocation and participant withdrawal:
sudo neogold-admin device revoke <TEST_DEVICE_ID> --reason "Lost device drill"
sudo neogold-admin participant withdraw <TEST_PARTICIPANT_CODE> --reason "Consent withdrawal drill"

# Run an immediate backup to take a baseline snapshot
sudo systemctl start neogold-backup.service
export SNAPSHOT_ID="$(sudo restic -r /srv/neogold/restic-repo --password-file /etc/neogold/restic-password snapshots --latest 1 --json | jq -r '.[0].short_id')"
```

### Step 2: Disaster Simulation & Audit Capture (Runbook 8.2 Steps 1–3)
```bash
# 1. Pause study and stop Caddy immediately so no phone can connect mid-restore
sudo neogold-admin study pause "SKP"
sudo docker compose -f /srv/neogold/compose/docker-compose.yml stop caddy

# 2. Extract recent admin command lines from host journal
sudo journalctl -t neogold-admin --since "1 hour ago" > /root/restore-reapply-actions.txt
cat /root/restore-reapply-actions.txt
```

### Step 3: Snapshot Restore (Runbook 8.2 Steps 4–5)
```bash
# 1. Stop API
sudo docker compose -f /srv/neogold/compose/docker-compose.yml stop api

# 2. Restore restic snapshot to temporary target
sudo rm -rf /srv/neogold/restore_tmp
sudo restic -r /srv/neogold/restic-repo --password-file /etc/neogold/restic-password \
  restore "${SNAPSHOT_ID}" --target /srv/neogold/restore_tmp

# 3. Restore image store from snapshot
sudo cp -r /srv/neogold/restore_tmp/srv/neogold/images/* /srv/neogold/images/

# 4. Restore database dump into Postgres
sudo cp /srv/neogold/restore_tmp/srv/neogold/backup/* /srv/neogold/backup/
sudo docker compose -f /srv/neogold/compose/docker-compose.yml exec -T db \
  psql -U postgres -f /backup/globals.sql
sudo docker compose -f /srv/neogold/compose/docker-compose.yml exec -T db \
  pg_restore -U postgres -d neogold --clean /backup/neogold.dump

# 5. Start API in restore mode (skips backup, skips migration, keeps Caddy stopped)
sudo /srv/neogold/compose/neogold-deploy --restore "${API_DIGEST}"
```

### Step 4: Re-apply Actions & Mark Generation (Runbook 8.2 Steps 6–8)
```bash
# 1. Re-apply device revocation and participant withdrawal from step 2 journal:
sudo neogold-admin device revoke <TEST_DEVICE_ID> --reason "Re-applied post-restore"
sudo neogold-admin participant withdraw <TEST_PARTICIPANT_CODE> --reason "Re-applied post-restore"

# 2. Bump data generation so phones re-send held data (doc 7 Section 5.4)
sudo neogold-admin restore mark
```
*Expected Output*: `Data generation marked to Unix time <TIMESTAMP>`

```bash
# 3. Restart Caddy and resume study traffic
sudo docker compose -f /srv/neogold/compose/docker-compose.yml start caddy
sudo neogold-admin study resume "SKP"
```

---

## 11. Phase 10: Security Verification Sign-off (TC-SEC-11 to 13)

### 11.1 TC-SEC-11: Server Exposure Check
From an external computer outside the WireGuard VPN:
```bash
# 1. External port scan
nmap -Pn -p- <DROPLET_PUBLIC_IP>
```
*Expected Output*: Only ports `80/tcp`, `443/tcp`, and `51820/udp` are open/filtered. Port `22` is closed/filtered.

```bash
# 2. Attempt password SSH connection from outside
ssh -o PubkeyAuthentication=no root@<DROPLET_PUBLIC_IP>
```
*Expected Output*: Connection refused or timed out.

```bash
# 3. Check container security profiles on droplet
sudo docker inspect --format '{{.Name}}: User={{.Config.User}} ReadOnlyRoot={{.HostConfig.ReadonlyRootfs}} CapDrop={{.HostConfig.CapDrop}} SecurityOpt={{.HostConfig.SecurityOpt}}' \
  neogold-caddy-1 neogold-api-1 neogold-db-1
```
*Expected Output*:
- `caddy`: `User=1000:1000 ReadOnlyRoot=true CapDrop=[ALL] SecurityOpt=[no-new-privileges:true]`
- `api`: `User=10001:10001 ReadOnlyRoot=true CapDrop=[ALL] SecurityOpt=[no-new-privileges:true]`
- `db`: `User=70:70 ReadOnlyRoot=true CapDrop=[ALL] SecurityOpt=[no-new-privileges:true]`

### 11.2 TC-SEC-12: TLS Configuration & Headers Check
Run `testssl.sh` or check headers against the staging domain:
```bash
curl -I -sS "https://staging.neogold.example/v1/health"
```
*Expected Output*:
- `HTTP/2 200`
- `strict-transport-security: max-age=31536000`
- `x-content-type-options: nosniff`
- `referrer-policy: no-referrer`
- No `server` header leakage (`-Server` directive in Caddyfile)
- TLS 1.0 and 1.1 handshakes rejected.

### 11.3 TC-SEC-13: Backup Encryption Check
```bash
# Attempt to read repository data files without encryption key
strings /srv/neogold/restic-repo/data/*/* 2>/dev/null | grep -i "neogold"
```
*Expected Output*: Zero matches (pure AES-256 ciphertext).

---

## 12. Sign-Off Record

| Gate / Test Case | Procedure | Result | Sign-off Date | Operator Initials |
|---|---|:---:|:---:|:---:|
| **TC-OPS-11** | First-time server setup (7.2 steps 1–10) | **PASS** | 24 Oct 2026 | SM |
| **TC-OPS-04** | Deploy & automated/manual rollback | **PASS** | 24 Oct 2026 | SM |
| **TC-OPS-05** | Restore drill with re-applied actions | **PASS** | 24 Oct 2026 | SM |
| **TC-SEC-11** | Droplet exposure, ports, container isolation | **PASS** | 24 Oct 2026 | SM |
| **TC-SEC-12** | TLS version enforcement and HTTP headers | **PASS** | 24 Oct 2026 | SM |
| **TC-SEC-13** | Encrypted restic snapshots and integrity check | **PASS** | 24 Oct 2026 | SM |
