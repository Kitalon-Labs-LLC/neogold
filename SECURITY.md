# Security Policy

NeoGold handles newborn healthcare workflows and research data collection. We take security vulnerabilities seriously and adhere to responsible disclosure principles.

---

## 1. Reporting a Vulnerability

**DO NOT create public GitHub issues for security vulnerabilities.**

To report a vulnerability:
- **Preferred:** Use [GitHub Private Vulnerability Reporting](https://github.com/Kitalon-Labs-LLC/neogold/security/advisories/new) on this repository.
- **Alternative:** Email the security team at:
  ```text
  security@kitalonlabs.com  <!-- Placeholder: update with your designated security address -->
  ```

Please include:
- Description of the vulnerability and its potential impact
- Detailed reproduction steps or proof-of-concept
- Any affected components (Android app, Study Intake API, card signing tool, etc.)
- Your name / handle if you wish to be credited in release notes

---

## 2. Response Timelines & Commitments

Per **Security & Compliance Plan (doc 8 Section 10)**:
- **Acknowledgment:** Within **3 working days** of receiving your report.
- **Triage & Status Updates:** Regular updates during investigation.
- **Remediation Timelines:**
  - **Critical:** Fix within 7 days (triggers immediate app halt/update procedure if already deployed).
  - **High:** Fix within 30 days.
  - **Medium:** Fix within 90 days.
  - **Low:** Fixed in the next planned release.
- **Coordinated Disclosure:** Public disclosure within **90 days** or after fixes have been deployed across active study devices.

---

## 3. Privacy & Patient Data Warning

Never submit real newborn or patient data in vulnerability reports or proof-of-concept payloads. Use only bench or synthetic data.
