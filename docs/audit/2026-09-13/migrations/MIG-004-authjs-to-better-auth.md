# MIG-004 — Auth.js (next-auth v5-beta) → Better Auth: DECISION BRIEF (recommendation: DEFER with triggers)

Evidence (fetched 2026-09-13): https://blog.logrocket.com/best-auth-library-nextjs-2026/ (pub 2026-04-20) — Better Auth team took over Auth.js maintenance Sept 2025; Auth.js is in security-patch mode; "the Auth.js team's own guidance for new projects points to Better Auth" but "Auth.js v5 only makes sense if you are migrating an existing codebase" [i.e., staying is the sanctioned path for existing apps]. Corroborating 2026 comparisons surfaced in search: codercops.com, pkgpulse.com, devtoolbox.blog, kostra.io.

## Lead argument AGAINST migrating (per audit rule: presumptive rejection)
This app's web auth layer is deliberately thin: 13 files touch next-auth, one provider (Google), and the **real** session/authorization system lives in the Spring backend (its own refresh-token rotation with reuse-grace, third-party OAuth platform per ADR-0020). Better Auth's headline advantages — DB-backed sessions, type inference, plugin ecosystem — mostly duplicate machinery the backend already owns. Migrating re-plumbs sign-in, session cookies, and middleware for zero user-visible gain, with real regression risk in the one flow that locks users out when it breaks. Auth.js still receives security patches. Do not migrate now.

## Case for (eventually)
- Feature-frozen dependency pinned to a **beta** (5.0.0-beta.32) on the security boundary; ecosystem knowledge, docs, and integrations are consolidating on Better Auth (shipped v1 early 2025, actively gaining features through 2026).
- If a Next 16/17 release breaks the beta and no fix lands (security-patch mode ≠ compat guarantees), migration becomes forced under time pressure — the worst way to do it.

## Cheap hedge to do NOW (0.5 d)
Move off the beta pin to the v5 **stable** release of next-auth (verify changelog first). This captures any fixes between beta.32 and stable without changing architecture, and de-risks MIG-002.

## Migration sketch (if/when triggered)
Better Auth with the Google social provider; keep the pattern of exchanging the Google ID token with the Spring backend for its own tokens (unchanged); replace `auth()` calls/middleware in the 13 touching files; session storage choice: JWT/cookie-only mode to avoid adding a DB dependency to the stateless web tier. Effort: 4–8 solo days incl. e2e re-verification of sign-in, sign-out, session refresh, and the OAuth consent page (ADR-0020) interplay.

## Blast radius
13 files in `web/` (auth config, middleware, server actions calling `auth()`), login e2e tests. No backend change (backend token exchange is auth-library-agnostic).

## Reversibility / rollback trigger
Single PR behind a branch; rollback = revert (session cookie format changes force all web users to re-login on both switch and rollback — acceptable, small user base). Trigger to abort: Google sign-in or backend token exchange unreliable in staging after 2 days.

## User impact
One forced re-login at cutover. Otherwise none.

## Privacy re-verification (regulated mode)
Auth cookie contents/flags and any new storage (avoid DB sessions to keep data-inventory unchanged) must be re-reviewed; Google OAuth scopes unchanged.

## Do-nothing (RECOMMENDED for now)
Carrying cost: near-zero today — security patches continue; risk is concentrated in a future compat break. Becomes untenable when ANY of: (T1) Auth.js announces end of security patches; (T2) a Next.js major that the app needs is not supported by Auth.js; (T3) a needed auth capability (e.g., passkeys for the health app) exists only in Better Auth. Review triggers at each Next.js major and annually (next review: 2027-09).
