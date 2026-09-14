# IMPL-PWA-01 — Installable Web PWA (iOS/iPad stopgap)

> Status: **planned** · Created 2026-09-13 · Source: audit findings PROD-004
> (web can't photo-log a meal, no PWA), XPLAT-006 (web zero-offline), and the
> iOS strategy decision in
> [`ios-client-strategy.md`](ios-client-strategy.md) — this is its Step 0.

## Goal and non-goals

**Goal:** iPhone/iPad users (starting with prospective user #2) get the daily
loop — dashboard, nutrition day, photo meal logging, today's doses — as an
installable home-screen app, at ~2% of the cost of a native client. Secondary
goal: measure real iOS demand before committing to the native/KMP decision.

**Non-goals:** offline mirror or outbox on web (stays android-only — the
offline story is explicitly out of scope), web push (FCM web can come later,
after the SEC-006 Firebase cert work), feature parity beyond the daily loop.

## Current state (verified in audit)

- No manifest, no service worker, no `apple-touch-icon` in `web/app` or
  `web/public` [Certain — D16/D7 sweeps].
- Meal photo capture is android-only; web has no upload path to
  `/api/nutrition/capture/*` even though the backend endpoints are
  client-agnostic [Certain].
- The async describe-meal rail (202 + job id + settle-poll) already works
  without FCM — the android client polls; web can use the identical shape
  [Certain — the rail predates push].
- Auth.js session cookie works fine in standalone-PWA WebKit; no change needed
  [Likely — verify on device in step 5].

## Implementation

1. **Manifest + icons** (`web/app/manifest.ts` — Next.js App Router native
   support): name, `display: "standalone"`, theme/background from the
   `@theme` tokens, 192/512 maskable icons + `apple-touch-icon`. Add
   `viewport-fit=cover` and safe-area padding to the root layout for notched
   devices.
2. **Minimal service worker** — cache-first for static assets only
   (`_next/static`, icons, fonts); network-only for all `/api` and page
   routes. No data caching: every past silent-staleness bug in this repo
   argues against a stale-while-revalidate dashboard. Offline = a branded
   "you're offline" fallback page, nothing else.
3. **Web photo capture**: on the nutrition day page, add a capture button
   using `<input type="file" accept="image/*" capture="environment">` (native
   camera sheet on iOS Safari, file picker on desktop). Client-side downscale
   to max-edge 1536px via canvas before upload (mirrors IMPL-PERF-01
   Workstream C; saves mobile upload time and backend heap), then POST to the
   existing capture endpoint and reuse the async-job settle-poll UI pattern:
   optimistic "analyzing…" entry card → poll job status → swap in the result
   (the same states the android op rail renders; web component is new but the
   contract exists).
4. **Empty/error state pass on the four daily-loop pages** — rides the
   M11/UX-003 fix (error must render as error, not zero-calorie day) since
   PWA users will hit cold-start delays more visibly. Depends on
   IMPL-PERF-01 Workstream B for the cold-start fix itself.
5. **Device verification**: iPhone Safari — install to home screen, camera
   capture, auth session survival after 7 idle days (Auth.js cookie in
   standalone mode), iPad split-view layout sanity (existing responsive
   breakpoints; audit A11Y-004 notes the dashboard has zero responsive
   prefixes — fix the dashboard grid as part of this step or accept
   phone-layout on iPad v1).
6. **Lighthouse PWA pass** in web-ci (playwright already runs; add a
   lighthouse-ci step, threshold installability only — don't gate on
   performance scores yet).

## Effort & sequencing

~3–4 operator-days total. Steps 1–2 are agent-unattended; 3 is agent-reviewed
(touches the capture contract); 5 is operator-required (physical device).
Sequence after IMPL-PERF-01 Workstream B (cold start) or the first-visit
experience will embarrass the install prompt.

**Do-nothing cost:** iOS-owning user #2 has no app-shaped entry point; the
native-client decision proceeds with zero demand data.

**Rollback:** manifest + SW are additive; removing the SW requires a
kill-switch update (`self.registration.unregister()` deploy) — include that
handler from day one.

## Verification checklist

- [ ] Installs from Safari share sheet with correct icon/name/splash
- [ ] Meal photo → analyzed entry end-to-end on iPhone, on cellular
- [ ] `/api` responses never served from SW cache (verify in devtools)
- [ ] Session survives 7-day idle in standalone mode
- [ ] Offline shows the fallback page, not a broken shell
- [ ] Lighthouse: installable ✓
