# Contributing to NeoGold

Thank you for contributing to NeoGold. NeoGold is a clinical decision-support tool for newborn jaundice risk estimation. Because errors in clinical logic or privacy violations can lead to real harm, contributions follow a structured and verified workflow.

---

## 1. Fork and Trademark Rules

NeoGold software is licensed under the [MIT License](LICENSE). However, per **Security & Compliance Plan (doc 8 Section 13.4)**:

> **Trademark Rule for Forks:**
> Any fork or derivative work that modifies clinical thresholds, estimation models, decision rules, or color calibration card specifications **MUST use a distinctly different application name and launcher icon**. A modified clinical tool must never pass as NeoGold.

---

## 2. Branching Model

| Branch | Purpose | Lifetime |
|---|---|---|
| `main` | Stable, reviewed code. Every commit on `main` has passed CI and two-person review. Release tags `vX.Y.Z` are cut from here (or from `release/X.Y`). | Permanent |
| `dev` | Integration branch. All day-to-day work lands here first. | Permanent |
| `pNN-short-name` | One work package (e.g., `p16-sync-client`). Branched from `dev`, merged back into `dev` by pull request. | Deleted on merge |
| `fix/short-name`, `docs/short-name`, `chore/short-name` | Small changes outside a work package. Branched from `dev`, merged into `dev`. | Deleted on merge |
| `release/X.Y` | Cut from `main` at code freeze (doc 12 Section 2). Fixes land on `dev` first and are cherry-picked. | Kept for the life of the release |
| `hotfix/X.Y.Z-description` | Branched from `release/X.Y`; merged into the release branch and back into `dev`. | Deleted on merge |

Rules:

- **Never commit directly to `main` or `dev`.** Everything arrives by pull request.
- **Feature branches target `dev`**, not `main`. Do not stack a new work package on an unmerged one; merge the previous package into `dev` first.
- **`dev` is promoted to `main`** by a pull request from `dev` to `main` once `dev` is green and the milestone is reviewed.
- **Branches are short-lived.** The repository deletes head branches automatically on merge. A branch with no open pull request should not exist on the remote.

---

## 3. Work Package Workflow

Every task or work package must follow these steps:

1. **Read First:** Read all referenced sections in `docs/` and `CLAUDE.md`. Do not skim; details are regulatory and clinical requirements.
2. **Plan & Align:** List files to create/change, tests by `TC-` ID, open questions, and hazards/risks. Stop and get approval before implementation.
3. **Branch & Small Commits:** Create the branch `pNN-short-name` from an up-to-date `dev` and make incremental commits.
4. **Test-First for Pure Engines:** For engine modules (`colorscience`, `estimation`, `triage`, `cardtrust`), write unit tests cited by `TC-` ID from `docs/10_Test_Cases.md` before writing the engine code.
5. **Run Local Checks:** Run the relevant test suites and verify traceability:
   ```bash
   python3 tools/trace.py docs
   ```
6. **Mandatory Reviewers:** Run the `safety-reviewer` subagent on any change touching clinical logic, triage, configs, or quality gates, and `privacy-security-reviewer` on data layer, permissions, network, or crypto.
7. **Documentation Updates:** Update docs, schemas, and `CLAUDE.md` command tables if anything changed.
8. **Pull Request:** Open a PR against `dev` for review. Two-person review is required before merging. The branch is deleted once merged.

---

## 4. Definition of Done & PR Checklist

Every pull request must fulfill:
- [ ] All relevant test cases from `docs/10_Test_Cases.md` pass and cite their `TC-` ID in the test name or comments.
- [ ] Zero new lint or detekt warnings; zero TODOs without an issue reference.
- [ ] No patient data (no real newborn data, photos, names, or identifiers) anywhere in tests, fixtures, or code.
- [ ] Documentation updated in the same PR to match any behavioral changes.
- [ ] CI checks pass green.
- [ ] PR description details manual verification steps for the second approver.
