---
description: "P00: Repository Bootstrap (NeoGold work package; plan first, then wait for approval)"
---
# P00: Repository bootstrap

Phase 0, Foundation. Depends on: nothing. Backlog: NG-001, NG-037. Tests: TC-OPS-08.

## Read first
- CLAUDE.md (all of it)
- docs/14_Build_Plan.md Sections 1, 2, 6
- docs/12_Deployment_Runbook.md Sections 2 and 3, Appendices A and B
- docs/8_Security_Compliance_Plan.md Sections 10 and 13.4

## Goal
A repository that every later work package can build on: the agreed layout, licenses, contribution rules, security policy, and a minimal CI that runs today and grows with the project.

## Build
1. Create the layout in CLAUDE.md "Repository layout" with a README in each top-level folder saying what goes there and which work package fills it.
2. Licenses: `LICENSE` (MIT) for code; `card/LICENSE` and `docs/LICENSE` (CC BY 4.0). Root README: what NeoGold is (intended use from CLAUDE.md, word for word), current status (v1.0 closed research build), how to build, and the line "Install only from Google Play; the signing certificate fingerprint is published here at release."
3. `CONTRIBUTING.md`: the session workflow, branch naming, PR checklist, and the fork rule from doc 8 Section 13.4 (forks that change thresholds, models, or cards use a different name and icon).
4. `SECURITY.md` per doc 8 Section 10: private vulnerability reporting, 3 working-day acknowledgment, 90-day disclosure, a placeholder contact address for me to fill in.
5. `.github/dependabot.yml` for Gradle, pip, Docker, and GitHub Actions (weekly).
6. Leave the full workflows in `docs/ci-templates/` as reference; later packages enable them. Create `.github/workflows/repo-ci.yml` that runs only what works today: `python3 tools/trace.py docs` and ShellCheck on `tools/*.sh`, with actions pinned to the SHAs used in doc 12 Appendix A and `permissions: contents: read`.
7. `.gitignore` for Android, Python, keystores (`*.jks`, `*.keystore`), `.env`, and `local.properties`.
8. `.github/ISSUE_TEMPLATE/bug.yml` from doc 11 Section 8.
9. A CI license check script `tools/check_licenses.py` (placeholder that passes until Gradle and pip dependencies exist; P16 completes it).

## Constraints
- Do not create any Android or backend code in this package.
- Do not edit anything in `docs/`.

## Done when
- `repo-ci.yml` passes on the PR.
- `python3 tools/trace.py docs` prints full FR, NFR, H, T, and Gate coverage.
- TC-OPS-08 partially satisfied (licenses present); note what P16 adds.

## Stop and ask me if
- Any file in the starter kit conflicts with this layout.
