# NeoGold Offline Signing Tool (`neogold-sign`)

The `neogold-sign` tool is the offline cryptographic utility used to generate root keys (K4, K5, K10), pack and sign calibration card batch QR payloads, pack and sign study profile enrollment payloads, and perform offline payload verification.

---

## Architecture and Design Decisions

### Implementation Language Justification
`neogold-sign` is implemented in **Python 3** (standard library + pinned `cryptography` from `card/requirements-dev.txt`) for the following reasons:
1. **Air-Gap Operational Simplicity:** An offline signing machine is typically a locked-down, air-gapped Linux laptop. A Python script runs directly on any minimal OS install without needing a complete Java Development Kit (JDK), Gradle wrapper, or daemon build environment.
2. **Tooling & Cryptographic Consistency:** It reuses the exact same pinned `cryptography` library and test vector generators already established in `card/spec/build_test_vectors.py` and `card/tests/test_qr_formats.py`.
3. **Auditability:** Python source code is transparent, directly readable, and verifiable by independent auditors and security reviewers prior to executing signing operations.

### Air-Gap Enforcement (Doc 8 Section 4)
Per the NeoGold Security & Compliance Plan (doc 8 Section 4, K4/K5), private signing keys must **never touch a networked machine**.
- `neogold-sign` automatically inspects local system network interfaces (Linux `/sys/class/net`, Windows `ipconfig`, macOS `ifconfig`, and socket address enumerations).
- If any active, non-loopback network interface is detected, `neogold-sign` **immediately refuses to sign** with exit code 2.
- For automated testing and continuous integration only, the explicit override flag `--i-understand-this-is-online` must be provided.

### QR Code Size Budgets
- **Card Batch Payloads:** Serialized to exactly 239 bytes (or up to 275 bytes for max-length identifiers), well within QR Code Version 12-M capacity (287 bytes).
- **Study Profile Payloads:** Strictly capped at **251 bytes** (QR Code Version 11-M capacity ceiling) to ensure printability and instant readability on printed paper enrollment sheets.

### Immutable Audit Log
Every signing operation (`card-batch` or `study-profile`) appends an entry to a signing log file (default: `signing-log.jsonl`).
- Recorded fields: UTC timestamp, action, key ID, batch ID / study code, payload byte length, SHA-256 digest of payload, operator ID, and online override flag.
- **Zero Key Leakage:** Private keys and key seeds are never logged under any circumstance.

---

## Command Reference

### 1. Key Generation (`keygen`)
Generate an Ed25519 signing keypair or X25519 export keypair:
```bash
python card/tools/neogold-sign/neogold_sign.py keygen \
  --key-id "CARD-KEY-2026-A" \
  --purpose "CARD_ISSUER" \
  --out-private "keys/card_key_2026_a.priv" \
  --out-public "keys/card_key_2026_a.pub"
```

### 2. Sign Card Batch (`card-batch`)
Pack and sign measured spectrophotometer CIELAB values for 23 patches:
```bash
python card/tools/neogold-sign/neogold_sign.py card-batch \
  --key-id "TEST-CARD-KEY-01" \
  --private-key "4e454f474f4c445f544553545f534545445f434152445f4b45595f3030303031" \
  --batch-id "NG-2026-001" \
  --issued-at "2026-10-15" \
  --expires-at "2028-10-15" \
  --patches "card/spec/testvectors/card_batch_valid.json" \
  --out "out/batch_signed.json" \
  --operator "Lead-QA" \
  --i-understand-this-is-online
```

### 3. Sign Study Profile (`study-profile`)
Pack and sign study configuration for paper enrollment sheets:
```bash
python card/tools/neogold-sign/neogold_sign.py study-profile \
  --key-id "TEST-STUDY-KEY-01" \
  --private-key "4e454f474f4c445f544553545f534545445f53545544595f4b45595f30303031" \
  --study-code "SKP01" \
  --display-name "Sakhipur Community Screening Study" \
  --partner "icddr,b / Stanford" \
  --host "intake.neogold.example.bd" \
  --timestamp-precision "DAY_PLUS_OFFSETS" \
  --expires-at "2028-12-31" \
  --export-public-key "8213620f8e2276fd6cd1c9e445dccf5514ac79bfc721ce582b7f196fd654ee02" \
  --out "out/study_profile_signed.json" \
  --operator "Study-Coordinator" \
  --i-understand-this-is-online
```

### 4. Verify Payload (`verify`)
Verify a raw binary, hex, or base64 QR payload against `config/trust-list.json`:
```bash
python card/tools/neogold-sign/neogold_sign.py verify \
  --payload "card/spec/testvectors/card_batch_valid.json" \
  --trust-list "config/trust-list.json" \
  --date "2026-10-17"
```
Output:
```
Verification Result: VERIFIED
Decoded Details:
{
  "type": "card_batch",
  "batch_id": "NG-2026-001",
  "issuer_key_id": "TEST-CARD-KEY-01",
  "issued_at": "2026-10-15",
  "expires_at": "2028-10-15",
  "patch_count": 23,
  "patches": [...]
}
```
