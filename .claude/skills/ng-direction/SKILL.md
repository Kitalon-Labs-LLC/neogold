---
name: ng-direction
description: Direction check. Compare what is built with what docs/ plan, find work no doc asked for, and write a dated report, scorecard, and action list. Reports only; the owner decides every drift item.
disable-model-invocation: true
argument-hint: "[--since <git ref>]"
---

# Direction Check (P24)

One question, answered with evidence: does what we built match the plan in `docs/`, and what got built that no doc asked for? This command **reports and never decides**. For each drift item the owner chooses: keep it and update the doc, or remove it from the code.

## Hard constraints
- Read-only on `android/`, `backend/`, `docs/`, and `config/`. Write only under `reports/direction/`.
- Never "fix" clinical values (thresholds, rule order, gestational bands, clinical wording). Report drift in them to the owner and the clinical lead.
- Never write `reports/direction/baseline.json` from this command. The baseline changes only in a PR that also updates the doc explaining the change, after the owner has decided each item.
- No real newborn data in any report. Quote code and docs, never data.

## Arguments
`--since <git ref>`: commits after this ref are checked for missing references. Default: the `commit` field of the newest `reports/direction/*/scorecard.json`. With no earlier report, use the root commit (all history) and say so in the report.

## Procedure

### Step 0: Guard the tree
```bash
git status --porcelain > /tmp/ng-direction-before.txt
```
Record the commit (`git rev-parse HEAD`) and whether it equals `origin/main`. Let `DATE=$(date +%F)` and `OUT=reports/direction/$DATE`.

### Step a: Deterministic inventory
```bash
python3 tools/drift_inventory.py --since "$SINCE" --out "$OUT/inventory.json"
python3 tools/trace.py docs --tests > "$OUT/trace.txt"
```
Use a Python that has `backend/requirements.lock` installed (`--python <venv>/bin/python`), otherwise `endpoints` is skipped and the report must say so. If a release build is available, also pass `--merged-manifest` (otherwise permissions and exported components cover source manifests only; say so).

### Step b: One doc-auditor per plan document, in parallel
Start these `doc-auditor` subagents in a single message, each with the document, its companion, the inventory path, and the commit:

| Doc | Companion |
|---|---|
| `docs/2_PRD.md` | |
| `docs/3_Feature_Backlog.md` | |
| `docs/4_System_Architecture.md` | |
| `docs/5_Database_Schema.md` | |
| `docs/6_UI_UX_Guidelines.md` | |
| `docs/7_API_Documentation.md` | `docs/api/openapi.yaml` |
| `docs/8_Security_Compliance_Plan.md` | |
| `docs/9_Test_Plan_Strategy.md` | `docs/10_Test_Cases.md` |
| `docs/12_Deployment_Runbook.md` | |
| `docs/14_Build_Plan.md` | |

Docs 1, 11, and 13 are context only; no auditor.

Save each reply as `$OUT/auditor/<doc-number>.json` and validate it against `reports/direction/auditor.schema.json`. A reply that fails validation is re-requested once; if it fails again, record that doc as "audit failed" in the report and continue. **Re-check every Implemented claim's evidence yourself by sampling** (at least 3 per doc, all of them in the six verdict areas): open the file and line. Evidence that does not show the claim turns the status into Unknown, and the report notes the downgrade.

The Bash guard that enforces read-only is the project hook in `.claude/settings.json` (it keys on `agent_type == "doc-auditor"`); the frontmatter hook did not fire in testing (docs/spikes/2026-10-26-subagent-frontmatter-hooks.md). If `doc-auditor` is not available as a subagent type, run the same instructions (`.claude/agents/doc-auditor.md` body) in a general-purpose subagent, and say in the report that the Bash guard was not active for those audits.

### Step c: Unplanned work (main session)
1. **Inventory items no doc anchors:** every `unplanned` entry in the gated categories (`gated` list in the inventory). Group them by category; one row per item.
2. **Commits with no reference:** `commits.unreferenced` (non-merge commits since the ref citing no P, NG, TC, or BUG ID). List them; read each one's diff (`git show --stat`) and say whether it changes behavior or only docs or tooling.
3. **WON'T HAVE matches:** compare the inventory (`wont_have_signals`, libraries, permissions, endpoints, screens) and the auditors' Contradicted claims with every row of doc 3 "WON'T HAVE (this release)". Name the row each match hits.
4. **Network from screening:** `categories.screening_network` must be empty. Also confirm the Gradle isolation rule (doc 8 Section 6.1, "Network isolation of screening") still exists in `android/build-logic`, and grep the screening path (`:feature:screening`, `:feature:result`, `:feature:log`, `:feature:calibration`, `:engine:*`) for network APIs the inventory pattern might miss. Any hit is a privacy item.
5. **PRD D10, `triageEnabled: true` in a release build:** list every `categories.triage_gate` entry and trace each one: can a release build of the closed research track reach it with `true`? Check the bundled `config/triage-config.json` value, how the app reads the config, default parameter values on the path from `MainActivity` to the result screen, and the release workflow (doc 12). A reachable path is a triage Contradicted item.

### Step d: Report and scorecard
Write `$OUT/report.md` and `$OUT/scorecard.json`.

`report.md` sections, in this order:
1. **Verdict** (first line) and the rule that produced it.
2. **Run facts:** commit, whether it is `main`, `--since` range, which inventory categories were skipped and why, whether the Bash guard was active.
3. **Counts by status, per doc** (table: doc, Implemented, Partial, Missing, Contradicted, Deferred, Unknown, audit failed).
4. **Milestones M1, M2, M3 against doc 9 Section 8 exit criteria:** one row per exit criterion (v0.1 for M1; v0.5 for M2; v1.0 internal track, closed testing, and closed research release for M3), with Met, Not met, or Unknown and the evidence (`docs/test-reports/`, CI, inventory key). Physical criteria (phones, power-pull tests, bench measurements) with no committed test report are Unknown, not Met.
5. **Contradicted items**, verdict areas first.
6. **Unplanned items** (Step c).
7. **Trend against the last report:** per status and per category, then and now, and items resolved or newly appeared. "First report" if none.
8. **Action list** (Step e).

`scorecard.json`:
```json
{
  "date": "YYYY-MM-DD", "commit": "<sha>", "is_main": true, "since": "<ref>",
  "verdict": "On course | Drifting | Off course", "verdict_reason": "...",
  "counts_by_doc": {"docs/2_PRD.md": {"Implemented": 0, "Partial": 0, "Missing": 0, "Contradicted": 0, "Deferred": 0, "Unknown": 0}},
  "milestones": {"M1": [{"criterion": "...", "status": "Met | Not met | Unknown", "evidence": "..."}], "M2": [], "M3": []},
  "unplanned": {"<category>": 0}, "unplanned_new_since_baseline": 0, "unreferenced_commits": 0,
  "skipped_categories": [], "bash_guard_active": true,
  "previous_report": "reports/direction/<date>/scorecard.json or null"
}
```

### Verdict rules (apply exactly, first match wins)
- **Off course:** any Contradicted item in triage, clinical wording, consent, quality gate, privacy, or crypto. This includes any reachable network path from screening (Step c4) and any reachable `triageEnabled: true` in a release build (Step c5).
- **Drifting:** any other Contradicted item, or more than 5 unplanned items.
- **On course:** otherwise.

"Unplanned items" for this rule means inventory items in the gated categories that are **not in `reports/direction/baseline.json`** (the baseline holds the items the owner already decided to keep), plus WON'T HAVE matches. With no baseline, every gated unplanned item counts. Unreferenced commits are listed and trended but do not count toward the threshold: they measure commit-message habits, not product drift.

### Step e: Action list
One entry per Contradicted item, unplanned item (items of the same kind may be grouped, for example one library in many modules), and Missing item in M1 to M3 scope. Each entry has:
- **What:** the item and its evidence.
- **Keep:** "change doc X Section Y to say ..." (the exact sentence or table row to add).
- **Remove:** "revert these files ..." (paths, and the commit that added them if known).
- **Owner:** the founder; the founder **and the clinical lead** for anything clinical (thresholds, rules, gestational bands, clinical wording, consent).
- **Effort:** S (under an hour), M (a session), L (more).
- **Decision:** `undecided`. Never mark anything decided.

### Step f: Verify and stop
```bash
git status --porcelain
```
Compare with `/tmp/ng-direction-before.txt`. Anything changed outside `reports/direction/` means an agent wrote where it must not: revert nothing yourself, list the paths in the report under "Integrity", and tell the owner.

Commit only `reports/direction/$DATE/`. Then stop and hand the report to the owner. Do not write or change the baseline.
