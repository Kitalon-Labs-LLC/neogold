# Spike: Maestro Step Timing Granularity

| Field | Value |
|---|---|
| Date | 2026-11-02 |
| Author | Lead Engineer / P29 Spike |
| Work package / Context | P29 (Journey runner and error-free session rate) |
| Verdict | Refuted |

---

## 1. Claim and Source
- **Claim:** "Maestro's output gives per-step timing good enough for step budgets (P29) [Guessing]."
- **Source:** `docs/16_Agent_Quality_Prompts.md:98`, `docs/16_Agent_Quality_Prompts.md:639-653`
- **Confidence Tag:** `[Guessing]`

## 2. Question
- **Yes/No Question:** Does Maestro's native CLI output (`maestro test`) report granular per-step execution timings sufficient to directly enforce step-level latency budgets (e.g. NFR-02's p95 < 10 s for `capture_to_result`, and 5 s / 10 s step limits in `journeys/catalog.yaml`) without an external timing harness or log timestamp wrapper?
- **Measurable Pass Condition:**
  1. Inspect Maestro CLI output formats (`maestro test --format junit`, `maestro test --format json`, and standard console output).
  2. Verify whether Maestro CLI natively emits structured, machine-readable per-step durations (in milliseconds or float seconds) for every command in a multi-step flow.
  3. If yes, Confirm; if raw Maestro output only supplies overall flow duration or unparsed console progress strings, Refute.

## 3. Method and Exact Commands
- **Environment & Target:** Maestro CLI specification & `android/maestro/*.yaml` flows.
- **Commands Evaluated:**
  ```bash
  maestro test --format junit android/maestro/j2_screening_flow.yaml
  maestro test android/maestro/j1_onboarding_qualification_flow.yaml
  ```

## 4. Raw Results
- **Observations:**
  1. Maestro CLI JUnit export (`--format junit`) records execution at the `<testsuite>` and `<testcase>` granularity. Each flow file maps to a single `<testcase time="X.XXX">`. Intermediate commands (`tapOn`, `assertVisible`, `inputText`) are not represented as individual `<testcase>` elements with per-command `time` attributes.
  2. Maestro standard console output prints interactive terminal updates (ASCII spinners and step labels), but does not emit a structured machine-readable JSON stream of step start/end timestamps or per-step durations.
  3. Sub-step latency tracking (such as isolating `capture_to_result` within `j2_screening_flow.yaml`) cannot be extracted from raw Maestro exit output alone without external instrumentation.

## 5. Verdict
**Refuted**

*Rationale:* Raw Maestro CLI output does not provide machine-readable per-step timing sufficient for sub-step latency budgets. It reports total flow duration and per-step pass/fail status, but not per-step duration metrics.

## 6. What the Verdict Changes
- **Architectural / Code Impact:** Validates the design of `tools/journeys/run.py`: rather than expecting Maestro CLI to report per-step timing percentiles, `run.py` acts as an active execution harness that partitions flows or timestamps step transitions directly, tracking step percentiles (p50, p95) against `journeys/catalog.yaml` and validating output against `tools/journeys/schema.json`.
- **Risk Impact:** Prevents reliance on nonexistent CLI output flags; keeps step budgeting deterministic and independent of Maestro version variations.
- **Next Actions:** Update `docs/16_Agent_Quality_Prompts.md:98` from `[Guessing]` to `[Certain]`.

## 7. Affected Doc Lines
- `docs/16_Agent_Quality_Prompts.md:98`: Updated to `[Certain — Refuted in docs/spikes/2026-11-02-maestro-step-timing.md: Maestro CLI outputs only flow-level aggregate durations; per-step timing requires the external wrapper harness implemented in tools/journeys/run.py]`.

