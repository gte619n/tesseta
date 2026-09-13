# `.github/` — CI governance

This directory owns GitHub Actions CI and the branch-protection ruleset. It is
the remediation surface for audit finding **CICD-001** ("nothing gates deploys;
no branch protection; a red-CI commit provably shipped").

## The early-exit CI pattern (CICD-001, Part 1)

**Problem.** The component CI workflows were `paths:`-gated at the trigger. On a
PR that doesn't touch a component, that component's workflow was **skipped** and
produced no check run at all. A branch ruleset that requires a status check
treats a skipped/missing check as *never satisfied* — so requiring them would
wedge every unrelated PR. And with nothing required today, a genuinely red CI
run ships anyway (commit `15ecf18` failed `android-ci` on main yet was
distributed to testers).

**Fix.** Each component workflow (`android-ci`, `backend-ci`, `web-ci`,
`terraform-ci`) now:

1. **Always triggers** on `pull_request` (and `push`) to `main` — no top-level
   `paths:` filter that could skip the whole workflow.
2. Runs a first **`changes` guard job** using
   [`dorny/paths-filter`](https://github.com/dorny/paths-filter) (SHA-pinned)
   with the same path lists the trigger used to carry.
3. Makes the heavy build/test jobs `needs: changes` and
   `if: needs.changes.outputs.changed == 'true'`, so they **skip** when the
   component is untouched — no runner minutes burned.
4. Ends with an **aggregate gate job named after the workflow**
   (`android-ci`, `backend-ci`, `web-ci`, `terraform-ci`) that `needs:` the
   heavy jobs, runs with `if: always()`, and:
   - **passes** when the heavy jobs were `skipped` (component unchanged) **or**
     all `success`;
   - **fails** if any heavy job failed.

Net effect: **every workflow reports a conclusion (success) on every PR**, so a
ruleset can safely require them, and a genuinely failing component still fails
the aggregate check. Build commands, runner images, and steps are unchanged.

`codeql.yml` is intentionally left as-is: it already runs on all PRs to `main`
with no path filter, so its two matrix check runs
(`analyze (javascript-typescript)`, `analyze (java-kotlin)`) always report.

### Why an aggregate gate job (not requiring each heavy job)

The ruleset requires **one stable check name per workflow**. This avoids two
traps: (a) two workflows both had a job literally named `build` — ambiguous as a
required context; (b) requiring `test` / `release` / `validate` directly would
break if a component is unchanged (those jobs are `skipped`, which a ruleset
counts as unsatisfied). The aggregate job is always present and always concludes.

## Branch ruleset as code (CICD-001, Part 2)

- **`branch-ruleset.json`** — the ruleset payload for `main`. Requires a PR
  before merge (**0 approvals** — the gate is the checks, not review), requires
  the six status checks below, blocks deletion and force-push. `strict` (require
  branches up to date) is **off** (see decision below). Repository **Admins can
  bypass** (break-glass).
- **`scripts/apply-branch-ruleset.sh`** — idempotent apply script (create via
  POST, update via PUT if a same-named ruleset exists). It first lists current
  rulesets read-only, then prompts before changing anything.

### Required status checks (exact contexts)

```
android-ci
backend-ci
web-ci
terraform-ci
analyze (javascript-typescript)
analyze (java-kotlin)
```

### Apply sequence (operator, manual)

The ruleset is **NOT auto-applied** (lead-agent decision DEC-001). A misconfigured
ruleset can lock the repo, and the required checks only exist once the refactored
workflows are on `main`. Apply in this order:

1. **Merge this workflow refactor to `main`.** (Until then, main defines the old
   job names and the aggregate contexts don't exist.)
2. **Confirm the 6 checks report** on a PR (any PR post-merge will show the four
   aggregate checks + two CodeQL checks concluding).
3. **Run the apply script:**
   ```bash
   ./.github/scripts/apply-branch-ruleset.sh
   ```
   Requires `gh` authenticated with admin (Administration:write) on
   `gte619n/tesseta`.
4. **Verify:**
   ```bash
   gh api repos/gte619n/tesseta/rulesets --jq '.[] | {id,name,enforcement}'
   ```

If you enable the ruleset *before* step 1, PRs become un-mergeable because the
required contexts never report — recover via the Admin bypass, disable the
ruleset, and re-apply after the merge.

## Decisions (CICD-001)

- **DEC — guard = `dorny/paths-filter` (not raw `git diff`)** — the audit fix
  prompt named it; it handles the PR-base-vs-push-previous diff correctly and
  fails safe (treats an unresolvable base as changed → runs). SHA-pinned to
  match the repo's action-pinning convention.
- **DEC — aggregate gate job per workflow (not per-heavy-job required checks)** —
  one stable context per workflow; immune to the `build`-name collision across
  workflows and to skipped-heavy-jobs counting as unsatisfied.
- **DEC — required checks = the 4 aggregates + 2 CodeQL matrix contexts** —
  these are the names that provably report on every PR after the refactor.
- **DEC — 0 required approvals** — solo operator; the value is the status-check
  gate, not review ceremony (CICD-012).
- **DEC — Admins bypass / not enforce-for-admins** — the operator keeps a
  break-glass path (needed to recover from a misconfigured ruleset, and to land
  an emergency hotfix if CI infra itself is down). Recorded as
  `DEC-CICD-001-admin`.
- **DEC — `strict` (require up-to-date branches) OFF** — for a solo operator it
  forces a serial rebase-then-remerge treadmill for no safety gain at ~2
  merges/day. Recommended against; flip `strict_required_status_checks_policy`
  to `true` in `branch-ruleset.json` only if concurrent conflicting merges ever
  become real.
- **DEC — `codeql.yml` unchanged** — already runs on all PRs to `main`; no guard
  needed.
