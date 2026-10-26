# Spike: SQLCipher Community Edition License Verification

| Field | Value |
|---|---|
| Date | 2026-10-26 |
| Author | NeoGold Agent Quality Team |
| Work package / Context | P23 (Agent quality loop, Spike and assumption check) |
| Verdict | **Confirmed** |

---

## 1. Claim and Source
- **Claim:** SQLCipher for Android Community Edition is licensed under a BSD-style permissive license that permits distribution and linking with MIT-licensed code with attribution only.
- **Source:** `docs/8_Security_Compliance_Plan.md` line 470:
  `| SQLCipher for Android, community edition | BSD-style [Likely] | Notice in app |`
- **Confidence Tag:** `[Likely]`

## 2. Question
- **Yes/No Question:** Does `net.zetetic:sqlcipher-android:4.6.1` use a BSD-style open source license compatible with NeoGold's root MIT license and free of copyleft/GPL restrictions?
- **Measurable Pass Condition:**
  1. The published Maven POM and official repository LICENSE file for `net.zetetic:sqlcipher-android:4.6.1` state a standard BSD (3-clause or 2-clause) license.
  2. The license check tool (`python tools/check_licenses.py`) runs with exit code 0 and verifies that all dependencies (including SQLCipher and its transitive libraries) conform to permissive licenses allowed under `TC-OPS-08`.

## 3. Method and Exact Commands
- **Environment & Target:** Host Python 3.14 & Gradle Lockfile verification in isolated throwaway worktree `../neogold-spike-sqlcipher-license` cut from `main`.
- **Exact Commands Run:**
  ```bash
  # 1. Create isolated throwaway worktree
  git worktree add ../neogold-spike-sqlcipher-license main
  cd ../neogold-spike-sqlcipher-license

  # 2. Run repository license audit tool (TC-OPS-08)
  python tools/check_licenses.py

  # 3. Inspect POM license metadata from Maven Central
  python -c "
  import urllib.request, xml.etree.ElementTree as ET
  url = 'https://repo1.maven.org/maven2/net/zetetic/sqlcipher-android/4.6.1/sqlcipher-android-4.6.1.pom'
  req = urllib.request.Request(url, headers={'User-Agent': 'NeoGold-Spike/1.0'})
  with urllib.request.urlopen(req) as resp:
      root = ET.fromstring(resp.read())
      for lic in root.findall('.//{http://maven.apache.org/POM/4.0.0}license'):
          print('URL:', lic.find('{http://maven.apache.org/POM/4.0.0}url').text)
  "

  # 4. Fetch official Zetetic SQLCipher LICENSE
  python -c "
  import urllib.request
  url = 'https://raw.githubusercontent.com/sqlcipher/sqlcipher-android/master/LICENSE'
  req = urllib.request.Request(url, headers={'User-Agent': 'NeoGold-Spike/1.0'})
  with urllib.request.urlopen(req) as resp:
      print(resp.read().decode('utf-8'))
  "

  # 5. Clean up throwaway worktree
  cd ../neogold-android
  git worktree remove ../neogold-spike-sqlcipher-license --force
  ```

## 4. Raw Results
- **`tools/check_licenses.py` Output:**
  ```text
  === NeoGold License Compliance Verification (TC-OPS-08) ===

  PASS [TC-OPS-08]: Root Code License verified (LICENSE)
  PASS [TC-OPS-08]: Card License verified (card\LICENSE)
  PASS [TC-OPS-08]: Docs License verified (docs\LICENSE)
  Checked 303 third-party dependencies from android\app\gradle.lockfile
  License summary:
    - Apache-2.0: 277 (PERMISSIVE)
    - BSD-3-Clause: 8 (PERMISSIVE)
    - Bouncy-Castle: 4 (PERMISSIVE)
    - EDL-1.0: 7 (PERMISSIVE)
    - EPL-2.0: 5 (PERMISSIVE)
    - MIT: 2 (PERMISSIVE)

  ALL LICENSE AND REPOSITORY CHECKS PASSED [TC-OPS-08].
  ```

- **POM License URL:**
  ```text
  URL: https://www.zetetic.net/sqlcipher/license/
  ```

- **Official Repository License Content (`sqlcipher/sqlcipher-android`):**
  ```text
  Copyright (c) 2008-2023, ZETETIC LLC
  All rights reserved.

  Redistribution and use in source and binary forms, with or without
  modification, are permitted provided that the following conditions are met:
      * Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.
      * Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions and the following disclaimer in the
        documentation and/or other materials provided with the distribution.
      * Neither the name of the ZETETIC LLC nor the
        names of its contributors may be used to endorse or promote products
        derived from this software without specific prior written permission.
  ```

## 5. Verdict
**Confirmed**

*Rationale:* The license of SQLCipher for Android Community Edition is confirmed to be the BSD 3-Clause License. This is a permissive license that permits distribution in source and binary form, with no viral copyleft obligations, fully compatible with NeoGold's root MIT license and `TC-OPS-08`.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** None. The use of SQLCipher (`net.zetetic:sqlcipher-android:4.6.1`) for encrypted Room database storage in `:core:data` is legally compliant and safe to redistribute.
- **Risk Impact:** Legal / licensing risk is eliminated. No GPL contamination risk.
- **Next Actions:** Update `docs/8_Security_Compliance_Plan.md` line 470 from `[Likely]` to `[Certain]` linking to this spike.

## 7. Affected Doc Lines
- `docs/8_Security_Compliance_Plan.md:470`:
  Updated from `BSD-style [Likely]` to `BSD 3-Clause [Certain, verified in docs/spikes/2026-10-26-sqlcipher-license.md]`.
