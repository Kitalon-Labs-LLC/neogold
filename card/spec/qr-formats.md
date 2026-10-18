# NeoGold QR Payload Formats and Trust Specifications

| Field | Value |
|---|---|
| Document | QR Code Binary Payloads, Trust List, and Signature Specifications |
| Version | 1.0.0-draft |
| Status | DRAFT — Awaiting P02 Review Gate |
| Date | 2026-10-17 |
| Authors | Kitalon Labs / NeoGold Project |
| Backlog | NG-006, NG-039, NG-045 |
| Tests | TC-CARD-01 to TC-CARD-06, TC-RSH-02, TC-RSH-03 |
| Dependencies | `config/schema/trust-list.schema.json`, `card/spec/testvectors/` |
| License | Creative Commons Attribution 4.0 International (CC BY 4.0) |

---

## 1. Overview and Principles

NeoGold uses signed, high-density 2D barcodes (QR codes) for two distinct operational functions:
1. **Calibration Card QR Code:** Printed physically on every calibration card. Carries the measured CIELAB color targets of the card's 23 patches, batch ID, issuer identity, and validity dates, signed offline by the card producer's private key K4 (`CARD_ISSUER`). Verified offline on the Android device before every screening session.
2. **Study Profile QR Code:** Printed on the paper enrollment sheet provided to clinical study coordinators. Carries the study configuration, API server hostname, timestamp minimization rules, and the study export public key K10, signed offline by Kitalon Labs' private key K5 (`STUDY_ISSUER`). Verified offline on the Android device during onboarding.

### 1.1 Non-Negotiable Rules
- **Pure binary encoding (QR Byte Mode):** All payloads are packed binary structures, decoded directly as raw bytes (`byte[]` in Kotlin / `bytes` in Python). No JSON or text wrapper in the QR symbol.
- **Big-endian byte order (Network Byte Order):** All multi-byte integers (`uint16`, `int16`) are strictly big-endian.
- **Ed25519 signatures only:** All signatures are 64-byte Ed25519 signatures (RFC 8032) calculated over all preceding bytes in the payload. Android verification uses Google Tink (`org.conscrypt` / Java Security Provider), fully compatible with standard Ed25519 implementations.
- **Purpose-bound keys:** A key certified in `config/trust-list.json` for `CARD_ISSUER` cannot sign a study profile, and a `STUDY_ISSUER` key cannot sign a card batch (`TC-CARD-04`).
- **No online verification during screening:** The screening path never touches the network. Trust lists are bundled in the app and updated only through app releases.

---

## 2. Card Batch Payload (`type = 0x01`)

### 2.1 Binary Layout Specification

The card batch payload is a compact binary record containing the spectrophotometer-measured CIELAB values for all 23 patches.

| Offset (bytes) | Field Name | Data Type | Units / Encoding | Description |
|---|---|---|---|---|
| `0` | `payload_type` | `uint8` | Constant `0x01` | Identifies this as a Card Batch payload. |
| `1` | `format_version` | `uint8` | Constant `0x01` | Payload schema version. |
| `2 .. 3` | `issued_at_days` | `uint16` BE | Days since `2020-01-01` UTC | Issue date of the batch. |
| `4 .. 5` | `expires_at_days` | `uint16` BE | Days since `2020-01-01` UTC | Expiry date of the batch. Screening blocked after this date. |
| `6` | `issuer_key_id_len` | `uint8` | Length $L_k \in [3, 32]$ | Byte length of `issuer_key_id`. |
| `7 .. 7+Lk-1` | `issuer_key_id` | `ASCII string` | ASCII characters | Identifier of the key in `trust-list.json` (e.g., `TEST-CARD-KEY-01`). |
| `7+Lk` | `batch_id_len` | `uint8` | Length $L_b \in [3, 32]$ | Byte length of `batch_id`. |
| `8+Lk .. 8+Lk+Lb-1` | `batch_id` | `ASCII string` | Pattern `^[A-Z0-9][A-Z0-9-]{2,31}$` | Card batch identifier (matches OpenAPI `BatchId` schema). |
| `8+Lk+Lb` | `patch_count` | `uint8` | Count $N = 23$ | Number of patches encoded in the payload. |
| `9+Lk+Lb .. 9+Lk+Lb+(N*6)-1` | `patch_data` | Array of $N$ patch records | Fixed-point CIELAB (6 bytes each) | Measured values for patches in canonical index order `0 .. N-1`. |
| `End - 64 .. End - 1` | `signature` | `bytes[64]` | Raw Ed25519 signature | Digital signature over all preceding bytes (`0` to `End - 65`). |

### 2.2 Patch Data Encoding (Fixed-Point CIELAB)
Each patch occupies exactly **6 bytes**, encoded in strict patch index order ($0$ to $22$, corresponding to `card-v1.json` patch indices):

$$\text{Encoded patch} = [L^*_{\text{raw}}, a^*_{\text{raw}}, b^*_{\text{raw}}]$$

| Sub-offset | Channel | Data Type | Scaling Factor | Encoded Range | Physical CIELAB Range | Resolution |
|---|---|---|---|---|---|---|
| `+0 .. +1` | $L^*$ | `uint16` BE | Value $\times 100$ | $0 \dots 10000$ | $0.00 \dots 100.00$ | $0.01\ L^*$ units |
| `+2 .. +3` | $a^*$ | `int16` BE | Value $\times 100$ | $-12800 \dots +12700$ | $-128.00 \dots +127.00$ | $0.01\ a^*$ units |
| `+4 .. +5` | $b^*$ | `int16` BE | Value $\times 100$ | $-12800 \dots +12700$ | $-128.00 \dots +127.00$ | $0.01\ b^*$ units |

**Precision note:** A resolution of $0.01$ CIELAB units is an order of magnitude finer than the $0.10\ \Delta E_{00}$ repeatability floor of benchtop spectrophotometers, ensuring zero rounding loss.

### 2.3 Total Payload Size and QR Version Budget
For a card with 23 patches, a 17-character `issuer_key_id` (`TEST-CARD-KEY-01`), and an 11-character `batch_id` (`NG-2026-001`):
- Header & dates: $1 + 1 + 2 + 2 = 6\text{ bytes}$
- Issuer key ID: $1 + 17 = 18\text{ bytes}$
- Batch ID: $1 + 11 = 12\text{ bytes}$
- Patch data: $1 + (23 \times 6) = 139\text{ bytes}$
- Ed25519 signature: $64\text{ bytes}$
- **Total payload size:** $6 + 18 + 12 + 139 + 64 = \mathbf{239\text{ bytes}}$.
- **Maximum theoretical size** (with max 32-character key ID and 32-character batch ID): $6 + 33 + 33 + 139 + 64 = \mathbf{275\text{ bytes}}$.

#### QR Code Version Selection (ISO/IEC 18004):
- **QR Version 11-M:** Capacity = **251 bytes** (tight; does not leave headroom for 32-character IDs).
- **QR Version 12-M:** Capacity = **287 bytes** ($65 \times 65$ modules).
  - Headroom: $287 - 239 = 48\text{ bytes}$ spare capacity.
  - At $0.50\text{ mm/module}$, symbol size is $32.5 \times 32.5\text{ mm}$ (clear area with 4-module quiet zone = $36.5 \times 36.5\text{ mm}$).
  - Fits comfortably within the card geometry.

---

## 3. Study Profile Payload (`type = 0x02`)

### 3.1 Binary Layout Specification

The study profile payload encodes study configuration parameters scanned from the printed enrollment sheet.

| Offset (bytes) | Field Name | Data Type | Units / Encoding | Description |
|---|---|---|---|---|
| `0` | `payload_type` | `uint8` | Constant `0x02` | Identifies this as a Study Profile payload. |
| `1` | `format_version` | `uint8` | Constant `0x01` | Payload schema version. |
| `2 .. 3` | `expires_at_days` | `uint16` BE | Days since `2020-01-01` UTC | Profile expiration date. |
| `4` | `timestamp_precision`| `uint8` | `0` or `1` | `0` = `DAY_PLUS_OFFSETS`, `1` = `FULL`. |
| `5` | `issuer_key_id_len` | `uint8` | Length $L_k \in [3, 32]$ | Byte length of `issuer_key_id`. |
| `6 .. 6+Lk-1` | `issuer_key_id` | `ASCII string` | ASCII characters | Key ID in `trust-list.json` (must have purpose `STUDY_ISSUER`). |
| `6+Lk` | `study_code_len` | `uint8` | Length $L_s \in [2, 32]$ | Byte length of `study_code`. |
| `7+Lk .. 7+Lk+Ls-1` | `study_code` | `ASCII string` | Pattern `^[A-Za-z0-9_-]{2,32}$` | Unique study identifier (e.g., `SKP01`). |
| `7+Lk+Ls` | `display_name_len` | `uint8` | Length $L_d \in [1, 128]$| Byte length of `display_name`. |
| `8+Lk+Ls .. 8+Lk+Ls+Ld-1` | `display_name` | `UTF-8 string` | UTF-8 | Human-readable study title shown on phone confirmation screen. |
| `8+Lk+Ls+Ld` | `partner_len` | `uint8` | Length $L_p \in [1, 128]$| Byte length of `partner`. |
| `9+Lk+Ls+Ld .. 9+Lk+Ls+Ld+Lp-1`| `partner` | `UTF-8 string` | UTF-8 | Research institution or partner name (e.g., `icddr,b / Stanford`). |
| `9+Lk+Ls+Ld+Lp` | `host_len` | `uint8` | Length $L_h \in [0, 128]$| `0` if export-only study (no server); otherwise hostname length. |
| `10+Lk+Ls+Ld+Lp .. 10+Lk+Ls+Ld+Lp+Lh-1`| `host` | `ASCII string` | Hostname | Ingestion server hostname (e.g., `intake.neogold.example.bd`). |
| `10+Lk+Ls+Ld+Lp+Lh .. +31` | `export_public_key` | `bytes[32]` | Raw 32-byte X25519 key | Study coordinator's public key (K10) for encrypting USB exports. |
| `End - 64 .. End - 1` | `signature` | `bytes[64]` | Raw Ed25519 signature | Digital signature over all preceding bytes. |

### 3.2 Total Payload Size and QR Version Budget
For typical study parameters (display name ~35 bytes, partner ~20 bytes, host ~25 bytes, key ID 18 bytes, code 5 bytes):
- Base header & flags: $1 + 1 + 2 + 1 = 5\text{ bytes}$
- Strings: $(1+18) + (1+5) + (1+35) + (1+20) + (1+25) = 108\text{ bytes}$
- Export public key: $32\text{ bytes}$
- Ed25519 signature: $64\text{ bytes}$
- **Total payload size:** $5 + 108 + 32 + 64 = \mathbf{209\text{ bytes}}$.
- **QR Version 10-M:** Capacity = **213 bytes** ($57 \times 57$ modules).
- **QR Version 11-M:** Capacity = **251 bytes** ($61 \times 61$ modules).
On an A4 or Letter paper enrollment sheet, printing this QR code at $35 \times 35\text{ mm}$ gives a module size of $0.57\text{ mm}$, easily scannable by budget smartphone cameras.

---

## 4. Trust List (`config/trust-list.json`)

The trust list contains the trusted root certificates/keys embedded directly into the Android application and backend service.

### 4.1 Schema Overview
The JSON structure is governed by `config/schema/trust-list.schema.json` (Draft 2020-12):
```json
{
  "schema_version": 1,
  "version": 1,
  "valid_from": "2026-10-01",
  "valid_until": "2028-10-01",
  "keys": [
    {
      "key_id": "TEST-CARD-KEY-01",
      "purpose": "CARD_ISSUER",
      "algorithm": "Ed25519",
      "public_key_hex": "b531103f671b40feea7ce88eb28cf508aa745ef2b3ff30263f3503f56b53fe77",
      "valid_from": "2026-10-01",
      "valid_until": "2028-10-01",
      "description": "Test-only card issuer key for unit and integration tests"
    },
    {
      "key_id": "TEST-STUDY-KEY-01",
      "purpose": "STUDY_ISSUER",
      "algorithm": "Ed25519",
      "public_key_hex": "c6218114beba9f71c3ff7e250785f858fa1a8f96443c52e46b97451bfbc74127",
      "valid_from": "2026-10-01",
      "valid_until": "2028-10-01",
      "description": "Test-only study issuer key for enrollment QR tests"
    }
  ],
  "revoked_card_batches": ["NG-2026-REVOKED"],
  "revoked_study_codes": ["STUDY-REVOKED"]
}
```

### 4.2 Verification Flow (`:engine:cardtrust`)
When a QR payload is scanned:
1. **Magic byte & version check:** Extract `payload_type` and `format_version`. Reject unsupported types (`Malformed`).
2. **Issuer lookup:** Read `issuer_key_id`. Search `keys` array in `trust-list.json`. If missing, return `UnknownIssuer` (`TC-CARD-03`).
3. **Purpose binding:** 
   - If `payload_type == 0x01` (Card Batch): key `purpose` MUST be `CARD_ISSUER`.
   - If `payload_type == 0x02` (Study Profile): key `purpose` MUST be `STUDY_ISSUER`.
   - If mismatched, return `WrongPurpose` (`TC-CARD-04`).
4. **Signature verification:** Extract the 64-byte trailing signature and preceding payload bytes. Verify using Ed25519 with the key's `public_key_hex`. If signature is invalid, return `BadSignature` (`TC-CARD-02`).
5. **Revocation check:**
   - Card Batch: If `batch_id` is present in `revoked_card_batches`, return `Revoked` (`TC-CARD-06`).
   - Study Profile: If `study_code` is present in `revoked_study_codes`, return `Revoked`.
6. **Date validity check:**
   - Verify evaluation date is within trust list validity window (`valid_from` to `valid_until`). If outside, return `Expired`.
   - Verify evaluation date is within issuer key validity window (`valid_from` to `valid_until`). If outside, return `Expired`.
   - Compare `expires_at_days` against evaluation date. If expired (evaluation date > `expires_at`), return `Expired` (`TC-CARD-05`).
7. **Success:** Return `Verified` along with the decoded typed structures.

---

## 5. Test Vectors

All test vectors are committed as JSON files in `card/spec/testvectors/`. Keys are generated deterministically using fixed test seeds and clearly labeled `TEST_ONLY_*`.

### 5.1 Card Batch Test Vectors

| Test Vector File | Target Verification | Key Used | Expected Result | Description |
|---|---|---|---|---|
| `card_batch_valid.json` | `TC-CARD-01` | `TEST-CARD-KEY-01` (`CARD_ISSUER`) | `VERIFIED` | Fully valid payload (239 bytes). Verified against bundled trust list. |
| `card_batch_invalid_tampered.json` | `TC-CARD-02` | `TEST-CARD-KEY-01` | `BAD_SIGNATURE` | 1 bit flipped at byte offset 35 ($L^*$ value of Patch 0). Signature check fails. |
| `card_batch_invalid_unknown_issuer.json`| `TC-CARD-03` | `TEST-UNKNOWN-KEY-99` | `UNKNOWN_ISSUER` | Valid Ed25519 signature, but issuer key is not in the trust list. |
| `card_batch_invalid_wrong_purpose.json` | `TC-CARD-04` | `TEST-STUDY-KEY-01` (`STUDY_ISSUER`) | `WRONG_PURPOSE` | Valid signature from a trusted key, but key purpose is `STUDY_ISSUER`. |
| `card_batch_invalid_expired.json` | `TC-CARD-05` | `TEST-CARD-KEY-01` | `EXPIRED` | Valid signature, but `expires_at` is `2025-01-01` (expired relative to current date). |
| `card_batch_invalid_revoked.json` | `TC-CARD-06` | `TEST-CARD-KEY-01` | `REVOKED` | Valid signature, but batch ID `NG-2026-REVOKED` is in `revoked_card_batches`. |

### 5.2 Study Profile Test Vectors

| Test Vector File | Target Verification | Key Used | Expected Result | Description |
|---|---|---|---|---|
| `study_profile_valid.json` | doc 7 Section 4.1 | `TEST-STUDY-KEY-01` (`STUDY_ISSUER`) | `VERIFIED` | Valid study profile payload (209 bytes). |
| `study_profile_invalid_tampered.json` | Security check | `TEST-STUDY-KEY-01` | `BAD_SIGNATURE` | 1 bit flipped at byte offset 20 (display name character). |
| `study_profile_invalid_unknown_issuer.json`| Security check | `TEST-UNKNOWN-KEY-99` | `UNKNOWN_ISSUER` | Valid signature from untrusted issuer key. |
| `study_profile_invalid_wrong_purpose.json` | Security check | `TEST-CARD-KEY-01` (`CARD_ISSUER`) | `WRONG_PURPOSE` | Key purpose `CARD_ISSUER` rejected for study profile. |
| `study_profile_invalid_expired.json` | Security check | `TEST-STUDY-KEY-01` | `EXPIRED` | Valid signature, but expiration date is in the past. |
| `study_profile_invalid_revoked.json` | Security check | `TEST-STUDY-KEY-01` | `REVOKED` | Study code `STUDY-REVOKED` is in `revoked_study_codes`. |

### 5.3 Test Vector Inspection & Cross-Verification
Each test vector JSON file contains:
- `description`: Plain-language explanation of the scenario.
- `expected_result`: Expected typed enum result.
- `payload_bytes_len`: Exact length in bytes.
- `payload_hex`: Hexadecimal string of the exact payload.
- `payload_base64`: Base64-encoded string (identical to the API `qr_payload` format in `docs/api/openapi.yaml`).

Automated round-trip tests in `card/tests/test_qr_formats.py` cross-verify all 12 test vectors against the schema and cryptographic engine.
