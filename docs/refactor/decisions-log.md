# Phase 2 — Decision log

Every non-obvious decision made during autonomous execution, for later review.
Format: **DEC-NN** — decision — rationale — reversibility.

---

## DEC-01 — All slices land on `performance-refactor-fable`, one commit per slice, not 13 separate branches
The plan specifies `refactor/<slice-name>` branches. I'm executing inside a
single pre-created worktree on `performance-refactor-fable`, and the slices are
sequentially dependent (6 needs 5's receipt order; 11 needs 4's data layer; 12
deletes what earlier slices obsolete). Thirteen branches with cross-branch
dependencies inside one worktree would mean constant rebasing and would make the
cumulative result un-reviewable as one branch — which is how this worktree is
set up to be reviewed. **Decision:** one single-concern commit per slice on this
branch, commit subject `refactor(<slice>): …`. **Reversible:** yes — commits are
per-slice, so any slice can be reverted or cherry-picked onto its own branch.

## DEC-02 — Autonomous mode: proceed on best judgment, log, never block
Per the user's instruction ("do not stop to ask questions"), every ambiguity is
resolved with the most conservative choice that preserves behavior and satisfies
the offline contract, and recorded here rather than surfaced as a question.

## DEC-03 — Perf harness: committed runnable probes + documented procedures for the live-stack legs
Slice 0 asks for backend latency, Android cold start, web bundle, web LCP, and a
24h-offline sync timer. The first three are committed as self-contained,
verified scripts (`infra/perf/{backend-latency.mjs,android-coldstart.sh,web-bundle.mjs}`).
Web LCP and the device-side sync timer need a live authenticated stack + a
physical/emulated device with a real backend, which can't be reduced to one
hermetic committed command in this environment. **Decision:** ship the fixture
**seeder** (`seed-fixture-user.mjs`, emulator-guarded) and document the LCP +
sync-timing procedures in `perf-harness.md` rather than fake a one-command run.
**Reversible:** yes — the documented legs can be scripted later when run against
real infra.

## DEC-04 — Android Macrobenchmark module deferred in favor of the `am start -W` probe
A full Macrobenchmark module measures authenticated cold start + DB-query timing
more faithfully, but it's a new Gradle module (build-time cost + R8/baseline-
profile coupling) added mid-refactor. The `am start -W` probe tracks the
slice-8/slice-9 deltas adequately (it already showed release 428 ms vs debug
1190 ms). **Decision:** defer the Macrobenchmark module; revisit only if the
800 ms target proves marginal on real hardware. **Reversible:** yes — additive
module, can be introduced without touching app code.

## DEC-05 — integrationTest zero-test guard is CI-only
The new guard throws if `integrationTest` runs 0 tests while
`firestore.emulator.required=true` (the CI condition). Locally, where the
firebase CLI may be absent, the suite is allowed to skip so a dev without the
emulator isn't blocked. **Reversible:** trivially — one `if` in build.gradle.kts.
