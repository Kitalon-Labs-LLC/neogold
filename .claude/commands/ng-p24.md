---
description: "P24: Direction Check (NeoGold work package; plan first, then wait for approval)"
---
# P24: Direction check (is the build still heading where the docs say?)

Phase 11, Agent quality loop. Depends on: P23. Backlog: none (process). Tests: tooling tests in tools/tests/ (no TC IDs).

## Read first
- CLAUDE.md
- docs/14_Build_Plan.md Sections 1 to 4
- docs/2_PRD.md Sections 3, 7, 8, 13, and 16
- docs/3_Feature_Backlog.md (all of it, including WON'T HAVE)
- docs/9_Test_Plan_Strategy.md Sections 8 and 10, and tools/trace.py
- The Claude Code docs on subagents, skills, and hooks for the installed version

## Goal
`/ng-direction` answers one question with evidence: does what we built match the plan in docs/, and what got built that no doc asked for? It reports and never decides. For each drift item I choose one of two things: keep it and update the doc, or remove it from the code.

## Build
1. `tools/drift_inventory.py` (deterministic, no model calls). It writes JSON comparing each of these against the docs:
   - Gradle modules and inter-module dependencies, against doc 4 Section 4.2.
   - External libraries per module, against the ADR table (doc 4 Section 15) and the approved list from P01.
   - Manifest permissions, components, and exported flags. Reuse tools/check_manifest.py.
   - The Room exported schema JSON, against doc 5 Section 2.2 tables and columns.
   - The Alembic head schema, against doc 5 Section 3.2. Get it from SQLAlchemy metadata, or from `pg_dump --schema-only` against a throwaway PostgreSQL 16.
   - FastAPI's generated OpenAPI, against docs/api/openapi.yaml: paths, methods, request and response schemas, and error codes.
   - Navigation destinations, against doc 6 Section 5 (S01 to S25, R01 to R09).
   - String keys in values/ and values-bn/, against doc 6 Section 10.4.
   - Config keys (`triageEnabled`, `validUntil`, and the rest), against doc 4 Section 6.1.
   - TC IDs cited in test code, against doc 10. Extend trace.py to count tests per TC and list TCs with zero tests.
   - NG IDs referenced by merged PRs and commits, against doc 3. List the v1.0 Must items that have no reference.

   A `--check` mode compares the result with `reports/direction/baseline.json` and exits non-zero on any new unplanned item: a dependency, permission, endpoint, table, column, screen, or config key.
2. `.claude/agents/doc-auditor.md`: read-only.
   - Tools: Read, Grep, Glob, and Bash. A frontmatter hook limits Bash to `git log`, `git show`, `git diff`, and `python3 tools/` commands.
   - Given one doc and the inventory, it extracts every checkable claim: FR, NFR, NG item, screen, endpoint, table, rule, and gate.
   - It gives each claim one status:
     - Implemented
     - Partial
     - Missing
     - Contradicted
     - Deferred: the doc itself defers it (v1.x public release, Could, WON'T HAVE)
     - Unknown
   - Implemented needs a file and line, or an inventory key, as evidence. With no evidence, the status is Unknown, never Implemented.
   - Output is JSON matching `reports/direction/auditor.schema.json`.
3. `.claude/skills/ng-direction/SKILL.md` (`disable-model-invocation: true`; optional `--since <git ref>`, which defaults to the commit of the last report):
   a. Run drift_inventory.py and trace.py.
   b. Start one doc-auditor per plan doc, in parallel: 2, 3, 4, 5, 6, 7 (with openapi.yaml), 8, 9 (with 10), 12, and 14. Docs 1, 11, and 13 are context only.
   c. In the main session, look for unplanned work:
      - inventory items that no doc anchors
      - commits since the ref with no P, NG, TC, or BUG reference
      - anything matching the WON'T HAVE list
      - any network path reachable from screening
      - any way a release build reaches `triageEnabled: true` (PRD D10)
   d. Write `reports/direction/YYYY-MM-DD/report.md` and `scorecard.json`, containing:
      - counts by status, per doc
      - per milestone (M1, M2, M3), what is done against the doc 9 Section 8 exit criteria
      - the trend against the last report
      - a verdict, using these rules, which must be written in the skill:
        - **Off course:** any Contradicted item in triage, clinical wording, consent, quality gate, privacy, or crypto.
        - **Drifting:** any other Contradicted item, or more than 5 unplanned items.
        - **On course:** otherwise.
   e. Write an action list. For each item, spell out both options: "Keep: change doc X Section Y to say ..." and "Remove: revert these files ...". Add the owner (me; the clinical lead for anything clinical) and the effort. Mark nothing as decided.
4. CI: add a `drift_inventory.py --check` job to the PR workflow. Pin actions by SHA and give the job no secrets.
5. Register the package: a P24 row in docs/14, and the skill in the CLAUDE.md Commands table.

## Constraints
- The command is read-only on `android/`, `backend/`, `docs/`, and `config/`. It writes only under `reports/direction/`.
- This command never "fixes" clinical values. It reports drift in them to me and the clinical lead.
- The baseline changes only in a PR that also updates the doc explaining the change.

## Done when
- The first full run on `main` is committed, with the verdict and the M1 to M3 table.
- `--check` fails on a test PR that adds an undeclared dependency, and passes once the ADR row is added.
- drift_inventory.py has tests in tools/tests/.

## Stop and ask me
- After the first run and before writing the baseline. I review the report and decide each drift item.

