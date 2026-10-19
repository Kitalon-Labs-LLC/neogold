# NeoGold: Localization and Translation Guide

| Field | Value |
|---|---|
| Document | Localization Guide (NG-038) |
| Target Audience | Open-source contributors, NGO implementers, forks (e.g. Kenya, India, Nigeria) |
| License | MIT (Code), CC BY 4.0 (Documentation) |

---

## 1. Overview

NeoGold is designed from the ground up for low-resource global health environments. When adapting or forking NeoGold for another region or language (e.g. Swahili in East Africa, Hindi in India, Hausa in Nigeria), localization involves both **linguistic translation** and **clinical safety compliance**.

This document describes how to add a new language to NeoGold and ensure it meets clinical safety, accessibility, and architectural standards.

---

## 2. Directory Layout and File Structure

All user-facing strings are externalized in the `:core:designsystem` module:

```
android/core/designsystem/src/main/res/
├── values/
│   └── strings.xml           <-- English source of truth
├── values-bn/
│   └── strings.xml           <-- Bengali (Bangla)
└── values-[lang_code]/
    └── strings.xml           <-- New language (e.g. values-sw/ for Swahili)
```

To add a new language:
1. Create a new directory named `values-<bcp47_code>` under `android/core/designsystem/src/main/res/`.
2. Copy `values/strings.xml` into the new folder.
3. Translate all values while preserving keys and parameter placeholders (e.g. `%d`, `%s`, `%1$s`).
4. Register the language in the Settings language selection menu in `:feature:settings`.

---

## 3. Mandatory Clinical Safety Rules

Translating a clinical decision-support app requires strict adherence to safety rules. Failure to observe these rules can lead to false reassurance and delayed newborn care.

### 3.1 Words Never Used (Banned Words)

The following concepts must **never** be used in any language:

| Concept | Why Banned | Replacement to Use |
|---|---|---|
| "Safe", "normal", "healthy", "fine" | Falsely reassures parents or workers. Jaundice can worsen rapidly after screening. | "No further action from this screen. Continue routine care and watch for danger signs." |
| "No jaundice", "Jaundice-free" | A smartphone camera cannot rule out pathological jaundice. Never claim absence. | (Omit; describe action only) |
| "Diagnosis", "Diagnose" | NeoGold is not a diagnostic device. It is a decision-support screening aid. | "Screening", "decision support" |
| "Accurate", "Precise" | Overclaims camera capability in variable lighting. | "Estimate", "estimated range" |
| "AI", "Artificial Intelligence" | Unhelpful to health workers and invites undue trust in automated outputs. | Omit |

### 3.2 Required Decision-Support Framing (PRD D11)

Every result screen must present the result as a suggestion:
- **Header label**: "Suggested next step" (above the triage action)
- **Footer line**: "You decide, using your clinical judgment." (below the triage action)
- **Safety reminder**: "Always confirm with a blood test." must appear on every screening outcome.
- **Experimental label**: "Experimental. Not yet validated in [Country/Region]." must be displayed.

### 3.3 Clinical Number Formatting (TC-A11Y-03)

In many countries (including Bangladesh and Kenya), hospital records, pathology lab reports, and facility registers record numbers in **Western ASCII digits** (`0`–`9`). 

- All clinical measurements (bilirubin mg/dL or µmol/L, age in hours, gestational age in weeks, thresholds) **must remain in Western digits** by default via `ClinicalNumberFormatter`.
- Do not localize numbers inside clinical strings unless the clinical advisory board specifically confirms that local numerals are standard across clinical laboratories in that jurisdiction.

---

## 4. Typography and Font Requirements

1. **Font Choice**: Use open, unencumbered typefaces under the SIL Open Font License (OFL), such as Google Noto Sans for the target script (e.g. Noto Sans Devanagari, Noto Sans Arabic).
2. **Line Height**: For scripts with complex conjuncts, vowel signs, or tall diacritics, set line height to **at least 1.5** (doc 6 Section 2.2). Never clamp line heights, as clipping diacritics alters medical meaning.
3. **Reflow & Font Scaling**: Layouts must reflow cleanly without text truncation at up to **200% system font scaling** on small screen widths (320 dp and 360 dp).

---

## 5. Clinical Sign-Off Process

Translations for NeoGold cannot be approved purely by software developers or machine translation tools.

1. **Drafting**: Create initial translations.
2. **Clinical Panel Review**: Present a review table (modeled on `docs/BANGLA_STRINGS_REVIEW.md`) to a panel of practicing nurses and pediatric/neonatal clinicians who speak the target language.
3. **Comprehension Testing**: Test the four core actions and the four danger signs with at least 5 health workers in community or district health facilities.
4. **Sign-Off**: Obtain written approval from the clinical advisor before merging.

---

## 6. Automated Verification

Before submitting a pull request:
1. Run `./gradlew verifyStrings` or `python tools/check_strings.py`.
2. Run `./gradlew :core:designsystem:testDebugUnitTest`.
3. Verify that zero missing keys exist between `values/` and the new locale.
4. Verify that zero banned words exist in the target language.
