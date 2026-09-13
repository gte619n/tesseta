# DIFF — audit run 2026-09-13

**First run — no prior run to diff against.** All 162 findings are new; this file establishes the baseline that run #2 should diff against (compare `findings.json` by `id`: new / resolved / severity-changed / status-changed).

## Baseline counts (post-skeptic, post-reconciliation, post-normalization)

- **Total findings:** 162 (161 from the 16 domain reports + 1 reconciler-promoted: SEC-012 public meal-photo bucket)
- **By severity (all statuses):** critical 5 · high 35 · medium 65 · low 57
- **By severity (status=open only):** critical 5 · high 35 · medium 64 · low 56
- **Criticals (exactly the skeptic-surviving five):** PERF-001, DATA-001, XPLAT-001, OBS-001, CICD-001
- **Rejected (skeptic-killed, retained for the machine record):** 2 — UX-002, SOTA-001
- **Skeptic-touched (demoted or killed, `skeptic` field present):** 16 — SUP-001, SUP-003, PERF-002, DATA-002, DATA-007, XPLAT-002, UX-001, UX-002, OBS-002, TEST-001, TEST-002, CICD-003, SOTA-001, SOTA-002, DX-001, PROD-002
- **Merged clusters (reconciliation M1-M19; M20 is a sequencing program, not a merge):** 19 merge-map entries → 20 canonical findings carrying `absorbs`, 27 findings carrying `absorbed_into` (absorbed items stay listed; 3 dual/partial absorptions: DX-002, OBS-004, SUP-009)
- **Dependency edges encoded:** OBS-001←OBS-003; PROD-001←SEC-006; COST-001←PERF-004; TEST-001→ARCH-002; CICD-001→"agent-unattended merges"; COST-001/PERF-002 joint-decision noted in both impacts

## Normalizations applied (rules for run #2 to reuse)

- Severity: `low-medium` (DATA-006) and `medium-low` (A11Y-005) → `low` (lower tier).
- Confidence free-text → Certain/Likely/Guessing by leading term (`certain-*` → Certain; `likely-*`, `high` → Likely).
- COST-007 empty prompt → one-line prompt authored; 15 further findings without a prompt field (SUP prose prompts existed; DATA-006/007/009/010, TEST-003..009, COMP-004/006/007/008, CICD-011/012) had prompts authored from their reports' fix text.
- DX-001 (originally critical) demoted to high in consolidation: absorbed into OBS-001 (M2), and only the five skeptic-surviving criticals may carry the label.
