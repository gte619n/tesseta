# MIG-002 — Next.js 15.5 → 16.3 (web/)

Deadline: **Next 15 EOL 2026-10-21** (https://endoflife.date/api/nextjs.json, fetched 2026-09-13). Next 16 stable since 2025-10-21; 16.3 (2026-08-03) is the current line; 16.3.5 latest (2026-09-11). https://nextjs.org/blog fetched 2026-09-13.

## Case for
- After 2026-10-21 the internet-facing, authenticated web app gets no security patches. The pinned 15.5.24 was itself an emergency security release (2026-08-25) — this line lives on CVE life-support already.
- Next 16 is a year old and on its third minor: the early-adopter tax is gone.
- Turbopack default matches the repo (`next dev --turbopack` already in scripts).

## Case against
- next-auth 5.0.0-beta.32 compatibility with Next 16 must be verified first (Auth.js is security-patch-only; a Next-16 incompat would force MIG-004 early — check its issue tracker before starting).
- Cache Components/`use cache` are a new mental model — but **adoption is optional**; the existing server-action-as-prop + apiFetch pattern (30 files `use server`, 28 files apiFetch) remains supported.

## Plan (incremental)
1. Branch; `next@16.3.x` + `eslint-config-next@16` + official codemods; React 19.2.7 → 19.3.0.
2. Fix async-request-API and config deprecations (`experimental.serverActions.bodySizeLimit` — check new location); keep data patterns as-is; do NOT adopt `use cache` in this pass (per-user authenticated API data gains little).
3. Ride-alongs (SOTA-011): node:22-alpine → node:24-alpine in `web/Dockerfile`, pnpm 10.32 → 12.4 (lockfile regen), Tailwind 4.3.3, try TypeScript 6 (SOTA-009; revert to 5.7 if plugin friction).
4. `pnpm build`, vitest suite, Playwright e2e, manual auth flow (Google sign-in → session → apiFetch authz), deploy via normal main-merge pipeline (watch deploy-* check-runs per memory).

Effort: 3–6 solo days.

## Blast radius
`web/` only (~30 `use server` files, 28 apiFetch files, Dockerfile, CI node version). No backend/android impact.

## Reversibility / rollback trigger
Single PR; revert cleanly restores 15.5.24 (still receiving security releases until 2026-10-21 — hence do it BEFORE EOL so the rollback target is still safe). Rollback trigger: auth flow broken in prod smoke test, or e2e suite cannot be made green in 2 days.

## User impact
None intended; faster builds/dev. Auth session cookies unchanged (same next-auth version).

## Privacy re-verification (regulated mode)
No data-flow changes; re-verify session cookie flags and that the security-headers middleware survives the upgrade.

## Do-nothing
Carrying cost: from 2026-10-21, every Next.js CVE (recent cadence: monthly security releases since July 2026) is an unpatched exposure on the login-bearing web app. Untenable date: **2026-10-21**.
