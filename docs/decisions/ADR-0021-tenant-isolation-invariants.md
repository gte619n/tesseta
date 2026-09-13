# ADR-0021: Tenant-isolation invariants

- Status: Accepted
- Date: 2026-09-13
- Context: `SecurityConfig`; 2026-09-13 audit (`docs/audit/2026-09-13/`, D1/D2)

## Context

Three load-bearing isolation rules have been in force since the multi-tenant
Firestore layout landed but were never recorded — the 2026-09-13 audit surfaced
them as unwritten fences that refactors (notably PERF fixes to the sync delta
reader) could silently break. This documents **existing practice**, not new policy.

## Decision

1. **All per-user Firestore access goes through `users/{uid}` paths — no
   `collectionGroup` queries on user data.** User-facing paths, above all the
   sync delta reader (`persistence/sync/FirestoreSyncChangeReader.java`), accept
   the N+1 read cost as the price of structural isolation: a scoping bug cannot
   return another user's documents when every query is rooted under their
   `users/{uid}` document. *Sole exception:* admin-gated catalog-merge
   maintenance (`LocationRepository.findAllReferencing`,
   `MedicationRepositoryImpl.findAllReferencingDrug`) deliberately scans
   cross-user; both callers sit behind `@AdminOnly`.
2. **Admin access fails closed.** The allow-list is `ADMIN_EMAILS`
   (`application.yml` → `app.admin.emails`); the default is **empty = no
   admins**, and `@AdminOnly` (`AdminAuthorizer`) additionally requires a
   verified email. Never invert this to fail-open, however inconvenient.
3. **Client-supplied user ids are never accepted on `/api/me/**`.** The user id
   is derived exclusively from the verified JWT `sub` (`SecurityConfig` pins
   each token family to its own decoder); no `/api/me/**` endpoint takes a user
   id parameter (no IDOR surface).

## Consequences

- PERF/refactor work must preserve rule 1 — no `collectionGroup` on user data
  even where it would be faster.
- New admin surfaces use `@AdminOnly` (rule 2); new `/api/me/**` endpoints
  resolve the user from the token, never a parameter (rule 3).
- These rules are the isolation backstop until cross-user API tests exist (TEST-001).
