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
| `dev` | Integration branch. The repository owner commits and pushes here directly; CI runs on every push. All other contributors land work here by pull request. | Permanent |
| `pNN-short-name` | One work package (e.g., `p16-sync-client`) from an outside contributor. Branched from `dev`, merged back into `dev` by pull request. The owner works on `dev` and does not need one. | Deleted on merge |
| `fix/short-name`, `docs/short-name`, `chore/short-name` | Small changes outside a work package, from an outside contributor. Branched from `dev`, merged into `dev`. | Deleted on merge |
| `release/X.Y` | Cut from `main` at code freeze (doc 12 Section 2). Fixes land on `dev` first and are cherry-picked. | Kept for the life of the release |
| `hotfix/X.Y.Z-description` | Branched from `release/X.Y`; merged into the release branch and back into `dev`. | Deleted on merge |

Rules:

- **Never commit directly to `main`.** It moves only by a pull request from `dev`. The repository owner commits to `dev` directly (decided 2026-10-27); every other contributor opens a pull request into `dev`.
- **Never force-push** `dev` or `main`.
- **No AI attribution on any commit.** No `Co-Authored-By` trailer naming an AI tool, no vendor address, no "Generated with" footer. `tools/check_commit_attribution.py` enforces it in CI; `git config core.hooksPath tools/hooks` also checks it before each local commit.
- **Contributor branches target `dev`**, not `main`. Do not stack a new work package on an unmerged one; merge the previous package into `dev` first.
- **`dev` is promoted to `main`** by a pull request from `dev` to `main` once `dev` is green and the milestone is reviewed.
- **Branches are short-lived.** The repository deletes head branches automatically on merge. A branch with no open pull request should not exist on the remote.

---

## 3. Work Package Workflow

Every task or work package must follow these steps:

1. **Read First:** Read all referenced sections in `docs/` and `CLAUDE.md`. Do not skim; details are regulatory and clinical requirements.
2. **Plan & Align:** List files to create/change, tests by `TC-` ID, open questions, and hazards/risks. Stop and get approval before implementation.
3. **Small Commits:** Make incremental commits. The owner commits on `dev`; an outside contributor creates the branch `pNN-short-name` from an up-to-date `dev`.
4. **Test-First for Pure Engines:** For engine modules (`colorscience`, `estimation`, `triage`, `cardtrust`), write unit tests cited by `TC-` ID from `docs/10_Test_Cases.md` before writing the engine code.
5. **Run Local Checks:** Run the relevant test suites and verify traceability:
   ```bash
   python3 tools/trace.py docs
   ```
6. **Mandatory Reviewers:** Run the `safety-reviewer` subagent on any change touching clinical logic, triage, configs, or quality gates, and `privacy-security-reviewer` on data layer, permissions, network, or crypto.
7. **Documentation Updates:** Update docs, schemas, and `CLAUDE.md` command tables if anything changed.
8. **Land it:** The owner pushes to `dev` once the local checks pass. An outside contributor opens a PR against `dev`; two-person review is required before merging, and the branch is deleted once merged. Promotion from `dev` to `main` is always a PR with two-person review.

---

## 4. Definition of Done & PR Checklist

Every pull request must fulfill:
- [ ] All relevant test cases from `docs/10_Test_Cases.md` pass and cite their `TC-` ID in the test name or comments.
- [ ] Zero new lint or detekt warnings; zero TODOs without an issue reference.
- [ ] No patient data (no real newborn data, photos, names, or identifiers) anywhere in tests, fixtures, or code.
- [ ] Documentation updated in the same PR to match any behavioral changes.
- [ ] CI checks pass green.
- [ ] PR description (or the commit message, for the owner) details manual verification steps for the second approver.
- [ ] No AI attribution in any commit message.

---

## 5. Static Analysis (detekt)

`./gradlew detekt` runs on every Android module in CI, and one finding fails the build. The rules and thresholds are in `android/config/detekt/detekt.yml`; the four pure engines add `detekt-engine.yml` (the StrictMath rules).

- **Fix, do not baseline.** There is no baseline file. Unused code, swallowed exceptions, locale-dependent formatting and similar findings are fixed.
- **Suppress with a reason.** Use `@Suppress("Rule") // why` on the smallest scope. A temporary suppression names the package that removes it. Numbers that are a published standard, a byte layout or the clinical rule stay literal, with a file-level `@Suppress("MagicNumber")` that names the source.
- **Size and branching limits** (`LongMethod` 120, `CyclomaticComplexMethod` 25, `NestedBlockDepth` 5, 3 returns or throws per function, 8 function and 10 constructor parameters, 15 functions per type, 140 characters per line) sit where the code is today. They stop new growth and are lowered as the packages that rewrite the suppressed functions land.
- **Formatting is automatic:** `./gradlew :<module>:detekt --auto-correct`. The flag applies to the task right before it, so repeat it for every module you list. Run it twice: the second pass fixes what the first one exposed.
- **Do not split a hardcoded UI string with `+` to satisfy the line limit.** `tools/check_strings.py` counts string literals, and its baseline can only shrink.
