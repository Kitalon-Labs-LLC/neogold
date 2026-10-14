# NeoGold: Release Notes, v1.0.0

| Field | Value |
|---|---|
| Document | 13 of 13 |
| Version | 0.1 (draft for v1.0.0) |
| Date | 11 October 2026 |
| Owner | Sowad Al-Mughni, Kitalon Labs |
| App version | 1.0.0 (`versionCode` 1000001) |
| Release date | [set at go-live] |
| Status | **Draft. v1.0.0 is a closed research release (PRD D10): use Section 6 for it. Sections 1 to 5 are kept for the later public release.** |

Items in square brackets are filled in at release from the signed records. Sections 1 to 5 are published only when G1, G2, and G3 are all met (doc 12 Path A). If G3 is not cleared, only Section 6 is used, for the closed-testing research build (doc 12 Path B), which has triage disabled and so needs neither G1 nor G3.

---

## 1. Google Play "What's New"

Play allows 500 characters per language, including spaces and line breaks [Certain]. Both texts below are within the limit (counted on 11 October 2026). The Bangla text is a draft for the nurse wording panel.

**English (400 characters)**

```text
First public release of NeoGold, an experimental newborn jaundice screening aid for health workers. Use the phone camera with a verified NeoGold card to get a next step: refer urgently, blood test today, screen again later, or no further action from this screen. Not yet validated in Bangladesh. Always confirm with a blood test. Works offline. No names or birth dates are stored. Bangla and English.
```

**Bangla (draft, 391 characters)**

```text
নিওগোল্ডের প্রথম প্রকাশ। স্বাস্থ্যকর্মীদের জন্য নবজাতকের জন্ডিস স্ক্রিনিংয়ের একটি পরীক্ষামূলক সহায়ক। যাচাই করা নিওগোল্ড কার্ড আর ফোনের ক্যামেরা দিয়ে পরের করণীয় জানুন: এখনই জরুরি রেফার, আজই রক্ত পরীক্ষা, পরে আবার পরীক্ষা, অথবা এই পরীক্ষা থেকে আর কিছু করার নেই। বাংলাদেশে এখনো যাচাই করা হয়নি। সবসময় রক্ত পরীক্ষা করে নিশ্চিত হোন। ইন্টারনেট ছাড়াই চলে। নাম বা জন্মতারিখ সংরক্ষণ করা হয় না।
```

---

## 2. For Health Workers

**What NeoGold does.** NeoGold helps you decide whether a newborn with yellow skin needs a blood test. You answer a few questions, photograph the baby's chest with a NeoGold card on it, and the app shows one of four next steps:

| Result | What to do |
|---|---|
| Refer urgently now | Send the baby for urgent care now |
| Blood test today | Arrange a bilirubin blood test today |
| Screen again in [N] hours | Screen again at the time shown; the Home screen lists babies due |
| No further action from this screen | Continue routine care and watch for danger signs |

**Always confirm with a blood test.** NeoGold is experimental and has not yet been validated in Bangladesh. A validation study with [partner name] is under way, and its results will be published whatever they show.

**NeoGold cannot screen** babies born before 35 weeks, babies who have already had phototherapy, or babies older than 14 days. It tells you to refer urgently, without using the camera, if yellow color appeared in the first 24 hours or the baby has a danger sign.

**What you need**

- An Android phone with Android 8.0 or newer and at least 2 GB of memory.
- A NeoGold card from a verified batch. Home-printed cards are not accepted.
- About 40 MB of free space.

The first time you open NeoGold, it tests your phone's camera with the card. Some phones change colors unevenly across a photo; NeoGold allows those phones for research only, and says so.

---

## 3. What Is in v1.0.0

**Screening**

- Questions before the camera: birth time, gestational age, jaundice in the first 24 hours, danger signs, phototherapy.
- Guided photo with the card, automatic photo-quality checks, and plain retake advice. After three unusable photos, NeoGold recommends a blood test.
- On-phone estimate with an uncertainty range. When the range is wide, NeoGold recommends a blood test instead of reassuring.
- Next-step thresholds follow the [AAP 2022 or AAP 2004] guideline as signed by [clinical lead name, registration number] on [date] (config [version], valid until [date]).
- Works with no internet connection.

**Phone and card checks**

- Camera test on first use; repeated every 30 days or after a system update.
- Card check: each card's QR code is verified on the phone; unverified or expired cards cannot be used for screening.

**Records and privacy**

- Screening log on the phone only. Kept 180 days by default; a facility can choose 30 to 365.
- Optional facility register number, erased after 60 days. The field refuses birth registration, national ID, and mobile numbers.
- No names. No birth dates (only age in hours). Screening photos are never saved.
- No analytics and no automatic crash reports. NeoGold data is excluded from phone backups.
- Optional app lock using the phone's screen lock, for shared phones.

**Research mode (study partners only)**

- Unlocked by scanning a study's signed QR code and setting a PIN.
- Blinded capture, paired blood test entry, sclera photos, encrypted storage.
- Export to USB, encrypted for the study team, or upload to the study server over Wi-Fi.

**Languages:** Bangla and English. Clinical numbers are shown in Western digits.

---

## 4. Known Limitations

- **Not validated in Bangladesh.** Performance on Bangladeshi newborns, and across skin tones, is not known until the validation study reports.
- **Lighting matters.** Mixed window and tube light, glare, and colored light cause retakes. Move away from windows and photograph in even light.
- **Some phones are research only.** Phones whose cameras change colors unevenly across a photo cannot be used for screening.
- **Reminders can be blocked** by phone battery settings on many brands. Check the Home screen for babies due; it does not depend on notifications.
- **The screening log stays on one phone.** It is not backed up and does not move to a new phone. Your paper register remains the record.
- **A version expires.** Each signed set of thresholds is valid for at most 12 months. After [validUntil date], this version stops showing results and asks you to update.

---

## 5. Install, Verify, Report

**Install only from Google Play.** Copies shared by messaging apps or file transfer may be modified. The official signing certificate fingerprint is:

```text
SHA-256: [published at release; also on the website and in the README]
```

**Open-source release.** Source code, model, card design, and documentation: [repository URL], tag `v1.0.0`. The GitHub release includes the software bill of materials (`bom.json`) and `SHA256SUMS`. Code is MIT-licensed; the card design and documentation are CC BY 4.0.

**Versions in this release**

| Component | Version |
|---|---|
| App | 1.0.0 (`versionCode` 1000001) |
| Model | [model version] |
| Triage configuration | [config version], signed [date], valid until [date] |
| Card trust list | [trust list version] |
| Study Intake Service (for study partners) | [intake version and image digest] |

**Report a problem.** [support contact] or the GitHub issue form. **Never include a baby's name, register number, participant code, birth time, or photo.** If NeoGold may have given a wrong next step for a baby, tell [clinical safety contact] the same day.

**Security problems:** follow `SECURITY.md` ([security contact]); please do not open a public issue.

**Thanks** to [clinical lead], the nurse wording panel at [sites], and [partner institution].

---

## 6. v1.0.0 Notes: Closed Research Build

For the closed testing track only (doc 12 Section 4.3). Install only at study sites with the partner's ethics approval in place.

**What's new (English, under 500 characters):**

```text
NeoGold research build for partner study sites. Research mode only: blinded capture with the NeoGold card, paired blood test entry, sclera photos, encrypted storage, encrypted USB export, and study server upload over Wi-Fi. Screening results are not shown in this build. For use under the study's ethics approval.
```

The detailed notes are Sections 3 (research mode and privacy), 4, and 5, without the screening results.

---

## 7. Changes Since the v0.5 Research Build

| Area | Change |
|---|---|
| Screening | Full screening flow with signed thresholds, experimental label, and blood test reminder |
| Records | Screening log with 180-day retention and 60-day register number erasure; Home due list |
| Shared phones | App lock; screenshots blocked on screens with identifiers |
| Research | Upload to the study server with retries, Upload now, and checks before local deletion |
| Updates | In-app update prompt; urgent fixes install before the app can be used |

---

## 8. Release Note Checks

Run before publishing; record the results here.

| Check | Result |
|---|---|
| English and Bangla Play texts each 500 characters or fewer | [pass or fail] |
| No banned words from doc 6 Section 10.2 in Sections 1 to 6 | [pass or fail] |
| Every square-bracket placeholder filled | [pass or fail] |
| Nurse panel approved the Bangla text | [name and date] |
| Clinical lead approved Sections 2 and 4 | [name and date] |
