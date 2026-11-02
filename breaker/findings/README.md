# NeoGold Breaker Findings (`breaker/findings/`, P27)

Every break discovered by `/ng-break` (the `breaker` subagent or `breaker/run_round.py`) is recorded in this directory as `BRK-YYYYMMDD-nn.md` before any fix is written.

## Completed Breaker Rounds

| Round ID | Date | Target Ref | Scope | Total Checks | Passed | New Findings | S1/S2 Open |
|---|---|---|---|---|---|---|---|
| `RND-20261031-01` | 2026-10-31 | `main` | `api` | 10 | 7 | 3 (`BRK-021`, `BRK-022`, `BRK-023`) | 0 (all fixed) |
| `RND-20261102-01` | 2026-11-02 | `main` | `all` (`clinical`, `data-loss`, `api`, `privacy`, `ui`) | 30 | 30 | 0 | 0 |

### Findings Index
- [BRK-20261031-01](BRK-20261031-01.md) (`S1`, `api`): Capture image upload for withdrawn participant rejected with 422, zero files written to disk (`FIXED`, regression test: `test_brk_022`).
- [BRK-20261031-02](BRK-20261031-02.md) (`S2`, `api`): Manifest with all `has_image=False` completes immediately (`FIXED`, regression test: `test_brk_021`).
- [BRK-20261031-03](BRK-20261031-03.md) (`S2`, `api`): `neogold-admin restore mark` strictly increases `data_generation` (`FIXED`, regression test: `test_brk_023`).

## Mandatory Finding Template (`BRK-YYYYMMDD-nn.md`)

```markdown
# BRK-YYYYMMDD-nn: <Short Title>

| Field | Value |
|---|---|
| Finding ID | `BRK-YYYYMMDD-nn` |
| Date | `YYYY-MM-DD` |
| Commit | `<git-sha>` |
| Catalog ID | `BRK-nnn` |
| Area | `clinical` | `data-loss` | `api` | `privacy` | `ui` |
| Severity | `S1 Critical` | `S2 Major` | `S3 Minor` | `S4 Cosmetic` (doc 9 Section 9.1) |
| Clinical | `Yes` | `No` |
| Protects | `H1`..`H11`, `T1`..`T17`, `TC-xxx` |
| Regression Test | `<path/to/failing_test>` |
| Status | `OPEN` | `FIXED` |

## 1. Summary
What failed and why it violates the safe behavior required by `docs/`.

## 2. Steps to Reproduce
1. Exact command or input vector on the disposable `neogold-breaker` environment.
2. Observed output vs expected safe behavior.

## 3. Raw Evidence
```text
<exact command output, HTTP response, or stack trace (synthetic data only)>
```

## 4. Failing Regression Test
Path to the committed failing test that cites the protected `TC-`, `T`, or `H` ID.
```

## Severity & Stop Rules (`docs/9_Test_Plan_Strategy.md` Section 9.1)
- **Stop immediately on `Clinical: Yes` or `S1`:** Any defect flagged `Clinical: Yes` or `S1 Critical` halts the `/ng-break` loop immediately and reports to the repository owner and clinical lead.
- **No app edits by `breaker`:** The `breaker` agent writes only finding files and failing regression tests. Application fixes happen in a normal PR on `dev` reviewed by `safety-reviewer` (Tier 0) or `privacy-security-reviewer` (Tier 1).
