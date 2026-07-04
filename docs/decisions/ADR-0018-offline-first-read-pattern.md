# ADR-0018: Offline-first read pattern (cache-first ViewModels)

- Status: Accepted
- Date: 2026-07-04

## Context

[ADR-0007](ADR-0007-android-offline-first-sync.md) established the offline-first
*core* on Android: a Room (SQLCipher) mirror kept fresh by delta sync + an
outbox. The core is sound. But "offline-first" was applied unevenly at the
**ViewModel/repository read layer**, so the app still *felt* online-bound: users
reported "things load way too frequently."

The recurring anti-pattern: a ViewModel starts in `Loading` and does a one-shot
network fetch in `init`, often backed by a network-first repository. The result
is a spinner on **every** screen entry — even when the mirror already holds the
data — and a re-fetch on every re-entry. The mirror was there; the read path
just didn't use it first.

Two reference implementations already showed the right shape:

- **Reactive + TTL-guarded refresh** — `DashboardBloodViewModel`: display state is
  `.stateIn` off the Room mirror (renders instantly), and the background refresh
  is skipped when it ran within a 30 s TTL.
- **Cache-first-then-revalidate-with-fallback** — `MedicationRepository.get()`:
  seed from the mirror, revalidate over the network, keep the cached value on
  failure.

## Decision

**Every user-facing read is cache-first.** This is now a required pattern for all
Android read surfaces, not a per-screen judgement call:

- ViewModels **never start in `Loading` when cached data exists**. Seed instantly
  from Room/DataStore, then revalidate in the background and keep the cached value
  on failure. `Loading` is acceptable **only** pre-first-sign-in (nothing cached
  yet).
- Repositories **serve the mirror/cache first and revalidate**. A network read is
  only the *revalidation*, never the gate for first paint.
- Reactive screens use `.stateIn` off the Room mirror with a **TTL-guarded**
  refresh (30 s) so re-entry within the window reuses the mirror instead of
  re-hitting the network.
- A screen must **not** show a spinner on re-entry when it has last-known data.

Reject in review: a `loading = true` default paired with a one-shot `init` fetch;
a repository read that hits the network before serving cache; a spinner on
re-entry.

The rule and its two reference files are documented in `android/CLAUDE.md`
("Offline-first (required)").

## Consequences

- Screens snap to last-known data on entry; the network fades into background
  revalidation. Genuinely first-run (pre-sync) screens still show a loader once.
- Repositories that were network-only (e.g. `DrugRepository`, `FoodRepository`,
  `EquipmentRepository`) need a fetched-entity Room cache to satisfy the rule for
  their detail/list screens — a bounded cache of what the user has actually
  fetched, not full-catalog replication.
- Some detail reads still need the network for fields the mirror doesn't hold
  (e.g. a DEXA scan's full breakdown vs. the mirrored summary); those seed the
  summary instantly and revalidate the detail, rather than blanking to a spinner.
- Related: [ADR-0019](ADR-0019-successor-chain-refresh-token-rotation.md) applies
  the same "survive inconsistent connectivity" principle to the auth layer.
