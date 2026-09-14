# DIFF — audit run 2026-09-13

## Progress update — 2026-09-14 (wave 2, branch `feature/audit-wave-2`, PR #253)

The last open critical + the observability and multi-user-gate clusters.
**Zero open criticals remain** after this wave. Full statuses in `findings.json`.

- **Wave-1 promoted to `resolved`** (merged to `main`, deployed, and infra
  applied+verified in prod on 2026-09-14): PERF-001/002, COST-001, SOTA-002/003/004,
  SUP-002/005, SEC-005/008/009/011, XPLAT-008, PROD-009, CICD-003/005, DATA-001/002,
  OBS-001, CICD-001. Consequentially resolved by the ruleset+monitoring apply:
  OBS-004, DX-002, TEST-002.
- **Wave-2 `implemented-on-branch`** (`feature/audit-wave-2`, pending PR #253
  merge): XPLAT-001 (critical), OBS-002/003/005/006, SEC-001, TEST-001, COMP-002.
- **Partial:** SEC-012 (Phase 1+2 done: signed-URL serving + client switch;
  Phase 3 bucket flip deferred to post-deploy) · COMP-001 (posture doc aligned;
  published policy still over-promises until DATA-003 deletion ships).
- **Also in PR #253:** backend `LB_PER_KG` twin fix (XPLAT-008 completion) +
  a web test locking in the SEC-012 photo-proxy `?date` forwarding.

Counts after this wave: **open criticals 0**, ~123 open total (mostly medium/low).

---

## Progress update — 2026-09-14 (branch `feature/audit-remediation`)

Implementation of the week-one safety batch + IMPL-PERF-01 workstreams A/B.
**10 findings addressed**; statuses + resolving commits are now in
`findings.json` (`status`, `implemented_by`, `implementation_note`). No new
findings, no regressions, no re-ranking. Detail: `docs/plans/IMPL-SAFETY-PERF-01-decision-log.md`.

| ID | Sev | New status | Resolving commit(s) | Effective when |
|---|---|---|---|---|
| PERF-001 | critical | implemented-pending-verify | c4d8e796, 06d2fc83 | merge+deploy; emulator RPC-drop test still pending |
| DATA-001 | critical | implemented-pending-apply | 248af4fe, 06d2fc83 | **`terraform apply` (prod still unprotected until then)** |
| CICD-001 | critical | implemented-pending-apply | 50f0a752 | merge, then run ruleset apply script |
| OBS-001 | critical | partial-pending-apply | 248af4fe | `terraform apply`; severity alerts still want OBS-003 |
| PERF-002 | high | implemented-pending-apply | c4d8e796, 06d2fc83 | next backend deploy |
| COST-001 | high | implemented-pending-apply | c4d8e796, 06d2fc83 | next backend deploy |
| SOTA-002 | high | implemented-on-branch | c4d8e796, 06d2fc83 | merge+deploy |
| SUP-002 | high | implemented-on-branch | c4d8e796 | merge+deploy (clears pending Trivy gate) |
| DATA-002 | medium | implemented-pending-apply | 248af4fe | `terraform apply` |
| SEC-012 | high | descoped → IMPL-SEC-01 | 375bb8ca | operator-backlogged |

**One critical still fully open: XPLAT-001** (three-way "today" computation) — not started.

### Status vocabulary (for run #2)
`implemented-on-branch` = code complete + locally verified, resolves on merge+deploy ·
`implemented-pending-apply` = code complete, needs operator `terraform apply`/deploy to take effect ·
`implemented-pending-verify` = code complete, a verification step remains ·
`partial-pending-apply` = partially addressed + pending apply ·
`descoped` = moved to a separate spec · `open` / `rejected` unchanged.

### Effective-status reality check
Code is committed to a branch, not merged or deployed. **Nothing is live in
prod yet.** The three infra/governance criticals (DATA-001, OBS-001, CICD-001)
require an explicit operator apply step after merge — see Next Steps in
`INDEX.md` / below. Until then the audit's prod-state findings remain true of
production.

---

## Run #1 baseline (2026-09-13) — established for future diffs

**First run — no prior run to diff against.** All 162 findings new; this is the
baseline run #3 should diff against (compare `findings.json` by `id`).

- **Total findings:** 162 (161 from 16 domain reports + 1 reconciler-promoted: SEC-012)
- **By severity (all statuses):** critical 5 · high 35 · medium 65 · low 57
- **Criticals (skeptic-surviving five):** PERF-001, DATA-001, XPLAT-001, OBS-001, CICD-001
- **Rejected (skeptic-killed, retained):** 2 — UX-002, SOTA-001
- **Skeptic-touched:** 16 — SUP-001, SUP-003, PERF-002, DATA-002, DATA-007, XPLAT-002, UX-001, UX-002, OBS-002, TEST-001, TEST-002, CICD-003, SOTA-001, SOTA-002, DX-001, PROD-002
- **Merged clusters:** 19 merge-map entries → 20 canonicals with `absorbs`, 27 with `absorbed_into`
- **Dependency edges:** OBS-001←OBS-003; PROD-001←SEC-006; COST-001←PERF-004; TEST-001→ARCH-002; CICD-001→"agent-unattended merges"; COST-001/PERF-002 joint-decision

### Normalizations applied (reuse for future runs)
- Severity `low-medium`/`medium-low` → `low`. Confidence free-text → Certain/Likely/Guessing.
- COST-007 + 15 others had prompts authored from report fix text.
- DX-001 (orig critical) → high (absorbed into OBS-001; only the five surviving criticals keep the label).
