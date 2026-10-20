# NeoGold Research Export Format Specification (v1.0)

## 1. Overview and Objectives

This document specifies the format and security design of the **NeoGold Encrypted Research Export** (FR-30, NG-036), satisfying the requirements in `docs/8_Security_Compliance_Plan.md` Section 5.2 and `docs/7_API_Documentation.md` Section 10 (`decrypt-export`).

The export bundle is designed so that a clinical research study can operate **entirely offline** without an ingestion server. The phone exports de-identified study records and images directly through the Android Storage Access Framework (SAF) into an encrypted ZIP file that can only be decrypted by the study team holding the offline private key ($K_{10}$).

### Key Guarantees
1. **Confidentiality at Rest & in Transit**: A lost or stolen USB drive reveals zero plaintext records or images.
2. **Data Minimization**: Zero direct identifiers (no names, national IDs, mobile numbers, birth dates, or facility register numbers). The only participant link is the pseudonymized `participant_code` (e.g. `SKP-0142`).
3. **Cryptographic Authenticity & Tamper Evident**: Any modification or bit-flip to the header, manifest, or ciphertext files immediately fails with an explicit error.
4. **Re-identification Mitigation (Date Shifting)**: The offline coordinator tool applies a per-participant random date offset $\Delta \in [-180, +180]$ days by default to prevent correlation with external birth/admission registers, while preserving internal time intervals between clinical events.

---

## 2. Cryptographic Architecture

The export uses a hybrid encryption envelope combining **Google Tink HPKE** and **Tink Streaming AEAD**:

```
+-----------------------------------------------------------------------------------+
| NeoGold Encrypted Export ZIP Archive                                              |
|                                                                                   |
|  header.json                                                                      |
|    |-- metadata (study_code, export_format_version, csv_schema_version)           |
|    `-- hpke_wrapped_data_key_b64  <-- Wrapped with Tink HPKE to K10_pub           |
|                                                                                   |
|  manifest.sha256                                                                  |
|    `-- SHA-256 digests of all ciphertext files (*.enc)                            |
|                                                                                   |
|  data/                                                                            |
|    |-- sessions.csv.enc          <-- Encrypted with fresh Streaming AEAD Data Key |
|    |-- captures.csv.enc          <-- Encrypted with fresh Streaming AEAD Data Key |
|    |-- tsb_measurements.csv.enc  <-- Encrypted with fresh Streaming AEAD Data Key |
|    `-- participants.csv.enc      <-- Encrypted with fresh Streaming AEAD Data Key |
|                                                                                   |
|  images/                                                                          |
|    |-- <capture_id_1>.jpg.enc    <-- Encrypted with fresh Streaming AEAD Data Key |
|    `-- <capture_id_2>.jpg.enc    <-- Encrypted with fresh Streaming AEAD Data Key |
+-----------------------------------------------------------------------------------+
```

### 2.1 Key Hierarchy
| Key | Identifier | Algorithm | Purpose | Location |
|---|---|---|---|---|
| **Study Export Key Pair** | $K_{10}$ | X25519 (RFC 9180 HPKE) | Encrypts export envelope data keys | Public key in signed study profile (`export_public_key`); private key held offline by study coordinator |
| **Export Data Key** | $K_{\text{exp}}$ | `AES256_GCM_HKDF_4KB` (Tink Streaming AEAD) | Encrypts export files (CSVs and images) | Ephemeral, generated freshly per export, wiped after packaging |

### 2.2 Envelope Wrapping: HPKE
- **KEM**: `DHKEM_X25519_HKDF_SHA256` (0x0020)
- **KDF**: `HKDF_SHA256` (0x0001)
- **AEAD**: `AES_256_GCM` (0x0002)
- **Context Info**: ASCII `"neogold-export-v1"`
- **Payload**: Serialized binary Tink Keyset containing $K_{\text{exp}}$.

### 2.3 File Encryption: Streaming AEAD
- **Algorithm**: `AES256_GCM_HKDF_4KB`
- Files are encrypted on-the-fly into `.enc` ciphertext streams with authentication tags embedded in 4 KB ciphertext segments.
- Any bit-flip or truncation triggers `GeneralSecurityException` / `TamperedCiphertextException`.

---

## 3. Archive Layout and Manifest

The export ZIP file contains the following relative paths:

```
neogold-export-<STUDY_CODE>-<TIMESTAMP>.zip
├── header.json
├── manifest.sha256
├── data/
│   ├── sessions.csv.enc
│   ├── captures.csv.enc
│   ├── tsb_measurements.csv.enc
│   └── participants.csv.enc
└── images/
    ├── <capture_id_1>.jpg.enc
    └── <capture_id_2>.jpg.enc
```

### 3.1 `header.json`
```json
{
  "export_format_version": 1,
  "csv_schema_version": 1,
  "study_code": "SKP",
  "exported_at": "2026-10-20T12:00:00Z",
  "total_participants": 12,
  "total_sessions": 15,
  "total_images": 30,
  "hpke_kem": "DHKEM_X25519_HKDF_SHA256",
  "hpke_kdf": "HKDF_SHA256",
  "hpke_aead": "AES_256_GCM",
  "file_cipher": "AES256_GCM_HKDF_4KB",
  "context_info": "neogold-export-v1",
  "wrapped_data_key_b64": "<base64 encoded HPKE ciphertext>",
  "manifest_sha256": "<sha256 hex of manifest.sha256>"
}
```

### 3.2 `manifest.sha256`
Standard checksum format (two spaces between hash and relative path):
```
e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  data/sessions.csv.enc
f4284d326f...  data/captures.csv.enc
89c21ef83a...  data/tsb_measurements.csv.enc
55b6a71e2c...  data/participants.csv.enc
a1c9381f09...  images/019318b2-c104-7b3b-871d-1029384756af.jpg.enc
...
```

---

## 4. De-identified CSV Schemas (Version 1)

All dates and times stored in the encrypted CSVs before date shifting are in the study's designated IANA time zone (e.g. `Asia/Dhaka`).

### 4.1 `data/sessions.csv`
| Column | Type | Description |
|---|---|---|
| `session_id` | String (UUID) | Unique research session identifier |
| `participant_code` | String | Pseudonymized participant code (`^[A-Z]{2,5}-[0-9]{3,6}$`) |
| `status` | String | `COMPLETE`, `IN_PROGRESS`, `ABANDONED` |
| `created_at` | Int64 (epoch ms) | Session start timestamp |
| `age_hours` | Float | Infant age in hours at screening (0.0 to 720.0) |
| `gestational_age_weeks` | Int | Gestational age in completed weeks (22 to 44) |
| `rule_outcome` | String? | Hard rule ID (e.g. `R2`) or null |
| `triage_action` | String? | `REFER_URGENT`, `BLOOD_TEST_TODAY`, `RECHECK`, etc. |
| `estimate_mg_dl` | Float? | Model point estimate in mg/dL |
| `estimate_low_mg_dl` | Float? | 90% confidence interval lower bound |
| `estimate_high_mg_dl` | Float? | 90% confidence interval upper bound |
| `device_model` | String? | Hardware model string (e.g. "Infinix X6528B") |
| `card_batch_id` | String? | Calibration card batch identifier |

### 4.2 `data/captures.csv`
| Column | Type | Description |
|---|---|---|
| `capture_id` | String (UUID) | Unique capture identifier |
| `session_id` | String (UUID) | Foreign key to session |
| `attempt_number` | Int | Attempt index (1 to 3) |
| `site` | String | `STERNUM`, `FOREHEAD`, `SCLERA_FLASH`, `SCLERA_NOFLASH` |
| `captured_at` | Int64 (epoch ms) | Capture timestamp |
| `quality_status` | String | `PASSED` or `FAILED` |
| `window_l` | Float? | Extracted window CIELAB L* |
| `window_a` | Float? | Extracted window CIELAB a* |
| `window_b` | Float? | Extracted window CIELAB b* |
| `correction_residual_de` | Float? | Mean $\Delta E_{00}$ on card patches |
| `image_file` | String? | Relative image filename in `images/` directory |
| `image_sha256` | String? | SHA-256 hash of plaintext image bytes |

### 4.3 `data/tsb_measurements.csv`
| Column | Type | Description |
|---|---|---|
| `tsb_id` | String (UUID) | Unique blood measurement identifier |
| `participant_code` | String | Participant code |
| `session_id` | String (UUID)? | Linked research session identifier |
| `tsb_mg_dl` | Float | Total Serum Bilirubin in mg/dL (0.1 to 40.0) |
| `draw_time` | Int64 (epoch ms) | Blood collection timestamp |
| `method` | String | `LAB`, `POINT_OF_CARE`, `OTHER` |

### 4.4 `data/participants.csv`
| Column | Type | Description |
|---|---|---|
| `participant_id` | String (UUID) | Internal participant UUID |
| `participant_code` | String | Assigned code (`^[A-Z]{2,5}-[0-9]{3,6}$`) |
| `consent_form_id` | String | Paper consent reference (`^[A-Z]{2,5}-[A-Z0-9-]{2,26}$`) |
| `consent_recorded_at` | Int64 (epoch ms) | Consent timestamp |
| `created_at` | Int64 (epoch ms) | Participant creation timestamp |

---

## 5. Date Shifting Algorithm

To prevent re-identification by correlating capture or draw dates with hospital admission or birth registers (T15 in `docs/8_Security_Compliance_Plan.md`), the decrypt tool applies date shifting:

1. For each distinct `participant_code`, the tool generates a cryptographically random integer offset:
   $$\Delta_p \in [-180, +180] \setminus \{0\} \text{ days}$$
2. The offset in milliseconds is:
   $$\text{offset\_ms} = \Delta_p \times 86{,}400 \times 1000$$
3. For all events belonging to participant $p$:
   - `created_at' = created_at + \text{offset\_ms}`
   - `captured_at' = captured_at + \text{offset\_ms}`
   - `draw_time' = draw_time + \text{offset\_ms}`
   - `consent_recorded_at' = consent_recorded_at + \text{offset\_ms}`
4. **Invariant**: The difference between any two timestamps for the same participant (e.g. time between session capture and blood draw: `draw_time - created_at`) is **strictly invariant** ($\Delta_p - \Delta_p = 0$).
5. **Override**: Passing `--no-date-shift` disables shifting ($\Delta_p = 0$) and issues a prominent warning to the operator requiring ethics board justification.

---

## 6. Verification and Error Handling

| Condition | Behavior |
|---|---|
| **Wrong Private Key** | HPKE unwrapping fails with `DecryptionException: Invalid private key or corrupted export header`. Process terminates with code 2. |
| **Tampered Ciphertext** | SHA-256 check fails with `ManifestVerificationException: File hash mismatch for <path>`. Streaming AEAD fails with `GeneralSecurityException`. Process terminates with code 3. |
| **Missing File** | Manifest check detects absent file and terminates with code 4. |
| **Success** | Outputs decrypted CSVs, decrypted JPEG images, and a decrypted `manifest.sha256` verified and compatible with `PartnerExportLoader`. |
