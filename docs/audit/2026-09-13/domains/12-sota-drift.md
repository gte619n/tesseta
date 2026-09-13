# D12 — Dependency & State-of-the-Art Drift (SOTA)

Audit date: 2026-09-13. All currency claims fetched this run (R7); each cell cites its source.

## BLUF

Two hard deadlines have **already passed**: (1) Google Play's target-API-36 requirement took effect 2026-08-31 while the app targets 35 (wear targets 34, also behind) — app updates are blockable on Play today, extension available only until 2026-11-01; (2) the production image-generation model `gemini-3.1-flash-image-preview` passed its earliest-possible shutdown date (2026-06-25) and its stable replacement `gemini-3.1-flash-image` already exists — a ~15-minute config fix protects every food-photo/exercise-media path. Two more deadlines land within 7 weeks: Next.js 15 EOL 2026-10-21 and the Cloud Functions Node 20 runtime decommission 2026-10-30. The rest of the stack is 12–20 months behind current but not on fire. Approach-shift verdicts: Auth.js is now in security-patch mode with its own maintainers pointing new projects at Better Auth (stay, with trigger); the bespoke LWW sync engine remains the **right** call (no 2026 sync platform speaks Firestore; ElectricSQL pivoted to agents/Databricks); Compose strong-skipping is already default at Kotlin 2.0.21; the server-action + apiFetch web data pattern remains valid on Next 16; FCM is correctly on HTTP v1 via firebase-admin; JUnit4 is still the Android default.

## THE TABLE

Sources fetched 2026-09-13 (abbrev): [EOL-SB] https://endoflife.date/api/spring-boot.json · [EOL-JDK] https://endoflife.date/api/oracle-jdk.json · [EOL-NODE] https://endoflife.date/api/nodejs.json · [EOL-NEXT] https://endoflife.date/api/nextjs.json · [EOL-REACT] https://endoflife.date/api/react.json · [EOL-KT] https://endoflife.date/api/kotlin.json · [EOL-TOFU] https://endoflife.date/api/opentofu.json · [EOL-TF] https://endoflife.date/api/terraform.json · [AGP] https://developer.android.com/build/releases/gradle-plugin · [BOM] https://developer.android.com/develop/ui/compose/bom/bom-mapping · [PLAY] https://support.google.com/googleplay/android-developer/answer/11926878 · [GCF] https://docs.cloud.google.com/functions/docs/runtime-support · [GEM-M] https://ai.google.dev/gemini-api/docs/models · [GEM-D] https://ai.google.dev/gemini-api/docs/deprecations · [NEXT-B] https://nextjs.org/blog · [TS] https://github.com/microsoft/TypeScript/releases · [TW] https://github.com/tailwindlabs/tailwindcss/releases · [ROOM] https://developer.android.com/jetpack/androidx/releases/room · [WORK] https://developer.android.com/jetpack/androidx/releases/work · [DAGGER] https://github.com/google/dagger/releases · [RETRO] https://github.com/square/retrofit/releases · [OKHTTP-S] WebSearch "OkHttp 5 stable release" (changelog + release posts) · [MOSHI-S] WebSearch square/moshi releases/Maven Central · [PNPM] https://github.com/pnpm/pnpm/releases · [TFPG] https://github.com/hashicorp/terraform-provider-google/releases · [FCM] https://firebase.google.com/docs/cloud-messaging/server · [SSKIP] https://developer.android.com/develop/ui/compose/performance/stability/strongskipping · [LR-AUTH] https://blog.logrocket.com/best-auth-library-nextjs-2026/ (pub 2026-04-20) · [PSYNC] https://www.powersync.com/blog · [ELEC] https://electric.ax/

| Component | Current (in-repo) | Latest | Gap | EOL | Breaking changes | Effort (solo days) | Recommendation |
|---|---|---|---|---|---|---|---|
| Spring Boot | 3.5.14 (`backend/gradle/libs.versions.toml`) | 3.5.16 patch; 4.1.1 line (4.1 rel 2026-06-30) [EOL-SB] | 1 major behind | 3.5 OSS EOL **2026-06-30 (passed)**; commercial to 2032 [EOL-SB] | Boot 4: config-property + starter reshuffle, jakarta already done; Framework 7 | 0.25 (3.5.16) / 5–10 (4.x) | Patch to 3.5.16 now; Boot 4.x per **MIG-003** by Q1 2027 |
| Java LTS | 21 (`backend/build.gradle.kts:43`, `eclipse-temurin:21` Dockerfile) | 25 = current LTS (rel 2025-09-16, support→2030-09-30); 21 supported→2028-09-30 [EOL-JDK] | 1 LTS behind | 21 fine to 2028 [EOL-JDK] | Minimal for this codebase | fold into MIG-003 | Bump toolchain+base image with Boot 4 migration |
| Next.js | 15.5.24 (`web/package.json`) | 16.3.5 (2026-09-11) [EOL-NEXT]; 16.3 line 2026-08-03 [NEXT-B] | 1 major | **15 EOL 2026-10-21** [EOL-NEXT] | Turbopack default, Cache Components/`use cache` stable, async request APIs | 3–6 | **MIG-002** before 2026-10-21 |
| React | 19.2.7 | 19.3.0 (2026-09-09) [EOL-REACT] | patch/minor | 19 active [EOL-REACT] | none expected | 0.25 | Bump with MIG-002 |
| next-auth / Auth.js | 5.0.0-beta.32 | v5 stable exists; project in **security-patch mode**, maintenance transferred to Better Auth team Sept 2025 [LR-AUTH] | pinned beta | de-facto feature-EOL [LR-AUTH] | Better Auth = different session model | 4–8 (if migrated) | Stay for now; **MIG-004** decision framework + trigger |
| Tailwind CSS | 4.3.2 | 4.3.3 [TW] (fetch showed garbled years; patch-level gap) | patch | n/a | none | 0.1 | Bump opportunistically |
| TypeScript | 5.7.3 | 7.0.2 (2026-08-20, native/Go `typescript-go`); 6.0.3 interim [TS] | 2 majors | n/a | 6.x deprecation cleanups, then native 7 | 1–2 | Adopt 6.x with MIG-002; 7 after ecosystem plugins settle |
| Node (web Docker) | `node:22-alpine` (`web/Dockerfile`) | LTS line now 24 (24.21.0); 22 EOL 2027-04-30, active-LTS ended 2025-10-21 [EOL-NODE] | 1 LTS | 2027-04-30 | low | 0.25 | Move to `node:24-alpine` with MIG-002 |
| Node (functions runtime) | `"node": "20"` (`functions/exercise-thumbnails/package.json`) | GCF: Node 20 deprecated 2026-04-30, **decommission 2026-10-30**; Node 24 GA [GCF]; Node 20 OS EOL 2026-04-30 [EOL-NODE] | past deprecation | **2026-10-30** | sharp prebuilds (0.33.5 → current) | 0.5 | **SOTA-003**: bump engines to 22/24 + redeploy before Oct 30 |
| Kotlin | 2.0.21 (`android/gradle/libs.versions.toml`) | 2.4.20 (2026-09-07) [EOL-KT] | 4 minors (~20 mo) | n/a | KSP/Compose-compiler lockstep | in MIG-001 | Bump inside MIG-001 |
| AGP | 8.7.3 | 9.4.0 (Sept 2026; min Gradle 9.6, JDK 17) [AGP]; AGP 10 makes new Variant API mandatory [AGP] | 1 major | n/a | AGP 9 DSL/variant changes | in MIG-001 | 8.13→9.x path inside MIG-001 |
| Compose BOM | 2024.12.01 (ui 1.7.x, M3 1.3.1) | 2026.08.00 → ui 1.12.0, M3 1.4.0 [BOM] | ~20 months | n/a | M3 1.4 API/visual changes, deprecated-API removals across 1.8–1.12 | in MIG-001 | Bump inside MIG-001 |
| Hilt | 2.52 | 2.60.1 (Jul 2026) [DAGGER] | 8 releases | n/a | multidex support removed, minSdk 23 (ok: minSdk 29) | 0.5 | Bump inside MIG-001 |
| Room | 2.6.1 | 2.8.5 (2026-09-09) [ROOM] | 2 minors | n/a | 2.7+: KSP2/Kotlin-codegen default; SQLiteDriver optional | 1 | Bump inside MIG-001 (keep SupportSQLite wrapper for SQLCipher — see D3 SQLCipher finding) |
| Retrofit / Moshi / OkHttp | 2.11.0 / 1.15.1 / 4.12.0 | 3.0.0 (binary-compat w/ 2.x) [RETRO] / 1.15.2 [MOSHI-S] / 5.5.0 (2026-08-16) [OKHTTP-S] | 1 major / patch / 1 major | n/a | OkHttp 5: mostly compatible, Kotlin-first API | 0.5–1 | Bump inside MIG-001 |
| WorkManager | 2.9.1 | 2.11.2 (2026-03-25) [WORK] | 2 minors | n/a | 2.11: minSdk 23 (ok) | 0.25 | Bump inside MIG-001 (2.11.2 fixes background-network-call bugs relevant to sync workers) |
| minSdk/targetSdk | minSdk 29; target/compileSdk 35 (app), **wear targetSdk 34** | Play requires **target API 36 since 2026-08-31** (wear: 35); extension to 2026-11-01 [PLAY] | **deadline passed** | 2026-08-31 / hard stop 2026-11-01 | Android 16 behavior changes | 2–4 (minimal) | **SOTA-001 / MIG-001 — do first** |
| Gemini models | `gemini-3.8-flash`; `gemini-3.1-flash-image-preview`; `gemini-3.1-pro-preview` (`application.yml`, CLAUDE.md) | `gemini-3.8-flash` = stable ✔; image-preview promoted to **`gemini-3.1-flash-image`**, preview's earliest shutdown **2026-06-25 (passed)** [GEM-M][GEM-D]; pro-preview: no shutdown date, policy = 3–9 mo notice [GEM-D] | image model past due | image-preview: **now** | prompt/quality re-verification | 0.25 + 0.5 verify | **SOTA-002**: swap 4 yml defaults + prod env vars; SOTA-008 for pro-preview |
| FCM API surface | firebase-admin 9.10.0, `FirebaseMessaging` bean (`FirebaseMessagingFcmSender.java`) | Admin SDK = HTTP v1, current recommended surface [FCM] | none | n/a | n/a | 0 | ✔ No action |
| IaC (OpenTofu, not Terraform) | `tofu init` lock, provider google **7.39.0**, `required_version >= 1.5.0` floating (`infra/terraform/versions.tf`, `.terraform.lock.hcl`) | OpenTofu 1.12.6 (2026-08-19) [EOL-TOFU]; provider google v8.2.0 latest [TFPG] | provider 1 major | n/a | provider 8.x resource behavior changes | 0.5–1 | Pin tofu `required_version`; plan provider 8.x bump with a clean `tofu plan` diff |
| pnpm | 10.32.1 (`packageManager`) | 12.4.1 (2026-09-10) [PNPM] | 2 majors | n/a | lockfile format bumps | 0.25 | Bump with MIG-002 |
| SQLCipher | net.zetetic 4.5.4 | (D3 finding — deprecated artifact; not re-fetched per shared context) | — | — | — | — | See D3 |

## APPROACH-SHIFT VERDICTS

**(a) Auth.js v5-beta — SHIFTED.** As of September 2025 the Better Auth team took over Auth.js maintenance and the library is in security-patch mode; "the Auth.js team's own guidance for new projects points to Better Auth" ([LR-AUTH], pub 2026-04-20; corroborated by comparative guides surfaced in search: codercops.com, pkgpulse.com, devtoolbox.blog). But the same sources say v5 "only makes sense if you are migrating an existing codebase" — which is exactly this repo's position: 13 files touch next-auth, Google-only OAuth, sessions are thin JWT wrappers over the backend's own token system. Verdict: the ecosystem default has moved, but for THIS app the auth layer is small and the backend owns real session state, so staying is defensible. Decision brief with explicit triggers: **MIG-004**.

**(b) Bespoke offline-first sync vs 2026 sync platforms — NOT SHIFTED (for this architecture).** PowerSync is thriving (active releases through 2026-09-09) but its backend list is Postgres/MongoDB/MySQL/SQL Server/Azure DocumentDB — **no Firestore** [PSYNC]. ElectricSQL pivoted to an agent platform, joined Databricks 2026-08-11, and PowerSync even publishes an "Electric Cloud → PowerSync" migration guide (2026-08-12) [ELEC][PSYNC] — a cautionary tale about betting a data layer on a venture-backed sync startup. Firebase's own offline persistence would require clients to talk to Firestore directly, abandoning the REST backend that carries authz, LWW merge rules, and the op rail. The 31-file hand-rolled sync engine (`android/**/sync*`) plus known fixable bugs (slash-collection alias, LWW flake) is cheaper than any migration on offer. Verdict: keep; revisit only if the backend ever moves off Firestore.

**(c) Compose idioms — PARTIALLY SHIFTED, cheap to catch up.** Strong-skipping has been compiler-default since Kotlin 2.0.20 [SSKIP]; the repo is on 2.0.21, so it already benefits — no idiom debt there. The real pressure is the ~20-month BOM gap (ui 1.7→1.12, M3 1.3.1→1.4.0 [BOM]) accumulating deprecated-API removals, and the AGP 9/AGP-10-variant-API wave [AGP]. Folded into **MIG-001** — the Play deadline forces the toolchain anyway.

**(d) Next.js App Router data patterns — VERSION SHIFTED, PATTERN OK.** Next 16 is stable (2025-10-21) with Turbopack default and Cache Components/`use cache`/PPR all stable (16.3, 2026-08-03) [NEXT-B]. The repo's pattern — 30 files with `use server`, 28 with `apiFetch`, all data fetched from the Spring backend — is still a supported first-class model; `use cache` is an optimization for cacheable server data, mostly irrelevant when every read is per-user authenticated API data. The forcing function is 15's EOL (2026-10-21) and the fact that the Aug 2026 critical security release patched 15.5.24 — the exact pinned version — showing 15 is already on life-support cadence [NEXT-B]. **MIG-002**.

**(e) Gemini preview models in prod — SHIFTED, urgent for images.** Google's lifecycle: preview models get 3–9 months, shutdown-date table lists *earliest possible* retirement [GEM-D]. `gemini-3.1-flash-image-preview` (4 refs in `application.yml`: nutrition image, exercise media, image-gen, imagen-model) is listed with earliest shutdown 2026-06-25 — already past — and the stable `gemini-3.1-flash-image` shipped [GEM-M][GEM-D]. When Google flips the switch, every food-photo and exercise-media generation 404s at once (and the app has a documented history of silent image-gen dead-ends). `gemini-3.1-pro-preview` (2 refs: goals chat ADR-0005, program designer ADR-0013) has no announced date but the same sword hangs over it. Findings SOTA-002/-008; no brief needed — it's config.

**(f) FCM legacy vs v1 — NOT AN ISSUE.** Verified in code first: sends go through the `FirebaseMessaging` bean from firebase-admin 9.10.0 (`backend/.../FirebaseMessagingFcmSender.java`), and Firebase docs confirm the Admin SDK is built on the HTTP v1 protocol, the current recommended surface [FCM].

**(g) JUnit4 on Android — NOT SHIFTED.** JUnit 4 remains the Android default in 2026; Google still ships no first-party JUnit 5 support, the third-party android-junit5 plugin is the only path and is currently wrestling with AGP 9/KMP-plugin docs gaps (github.com/mannodermaus issues, fetched via search). With 95 test files importing org.junit, migrating buys nothing. Keep JUnit4; revisit only if Google ships first-party JUnit5 support.

## FINDINGS

### SOTA-001 — Play target-API-36 deadline passed; app targets 35, wear targets 34 [CRITICAL]
- Confidence: Certain (policy fetched [PLAY]; `android/app/build.gradle.kts:29` targetSdk 35; `android/wear/build.gradle.kts:14` targetSdk 34)
- Evidence: "Starting August 31, 2026: new apps and app updates must target Android 16 (API 36)… Wear OS… must target Android 15 (API 35) or higher"; extension available to 2026-11-01.
- Impact: Play can reject every app update today. All shipped fixes (push, sync, portions) become undeliverable. Hard stop 2026-11-01 even with extension.
- Effort: 2–4 days minimal (compileSdk/targetSdk 36 + Android 16 behavior-change review); full toolchain in MIG-001. Autonomy: high (agent can do the bump + build; human validates on device and files the extension request as insurance).
- Null option: undeliverable app updates; untenable **now** (deadline passed), absolute wall 2026-11-01.
- Prompt: "Raise compileSdk/targetSdk to 36 (app) and 35 (wear) per MIG-001 phase 1; review Android 16 behavior changes for FGS, exact alarms, and notification changes used by reminders/sync."

### SOTA-002 — Prod image model `gemini-3.1-flash-image-preview` past earliest shutdown; stable replacement exists [CRITICAL]
- Confidence: Certain ([GEM-D]: earliest shutdown 2026-06-25; [GEM-M]: stable `gemini-3.1-flash-image` current; 4 defaults in `backend/src/main/resources/application.yml` lines 227, 289, 309, 345)
- Impact: all image generation (food photos, exercise media) can be shut off with no code-side warning; app history shows image failures degrade silently (food-photo self-heal memory).
- Effort: 0.25 day config + 0.5 day output-quality spot-check. Autonomy: high; human eyeballs a few generated images. Privacy note (regulated mode): same API surface/provider, no new data flow — no privacy re-verification needed beyond confirming model id in the DPA-covered API.
- Null option: sudden total image-gen outage at a date Google chooses; untenable **now**.
- Prompt: "Change the four `gemini-3.1-flash-image-preview` defaults in application.yml to `gemini-3.1-flash-image`, check prod env overrides (NUTRITION_IMAGE_MODEL, GEMINI_IMAGE_MODEL, EXERCISE_MEDIA_MODEL, IMAGEN_MODEL), update CLAUDE.md model policy, and spot-check output quality."

### SOTA-003 — exercise-thumbnails on Node 20 runtime; decommission 2026-10-30 [HIGH]
- Confidence: Certain ([GCF]; `functions/exercise-thumbnails/package.json` engines "20")
- Impact: GCS-triggered thumbnail function stops being deployable/supported; eventual forced shutdown.
- Effort: 0.5 day (engines → 22 or 24, sharp 0.33.5 prebuild check, redeploy). Autonomy: high.
- Null option: function decommissioned 2026-10-30 → new exercise media never gets thumbnails; untenable 2026-10-30.
- Prompt: "Bump functions/exercise-thumbnails to Node 24 (engines + deploy config in infra), verify sharp prebuilds, redeploy, and confirm a thumbnail generates."

### SOTA-004 — Next.js 15 EOL 2026-10-21 [HIGH]
- Confidence: Certain ([EOL-NEXT]; `web/package.json` next 15.5.24)
- Impact: no security patches after EOL on an internet-facing auth'd app; 15.5.24 itself was an emergency security patch (2026-08-25 [NEXT-B]) — the line is already patch-only.
- Effort: 3–6 days (MIG-002). Autonomy: medium-high (agent migrates, human smoke-tests auth + PWA flows).
- Null option: unpatched CVEs on the public web app; untenable 2026-10-21.
- Prompt: "Execute MIG-002: upgrade web/ to Next 16.3.x + React 19.3 + node:24-alpine, run codemods, verify next-auth beta.32 compat, e2e pass."

### SOTA-005 — Spring Boot 3.5 past OSS EOL; also 2 patches behind on own line [MEDIUM — EOL itself owned by D3, referenced]
- Confidence: Certain ([EOL-SB]; libs.versions.toml 3.5.14 vs 3.5.16)
- Impact: no more coordinated CVE trains (the exact mechanism this repo has depended on repeatedly, per the CVE comment block in libs.versions.toml).
- Effort: 0.25 day (3.5.16); 5–10 days Boot 4.1 + Java 25 (MIG-003). Autonomy: patch bump high; major migration medium.
- Null option: security-fix latency grows each month; untenable by ~Q2 2027 when the first unpatched-in-3.5-OSS CVE lands.
- Prompt: "Bump springBoot to 3.5.16 now; schedule MIG-003 (Boot 4.1 + Java 25 toolchain/base image) for a quiet sprint before Q2 2027."

### SOTA-006 — Auth.js v5-beta pinned while project is in security-patch mode [MEDIUM]
- Confidence: High ([LR-AUTH] pub 2026-04-20 + corroborating 2026 comparisons; `web/package.json` 5.0.0-beta.32)
- Impact: no new features/fixes; beta pin means even v5-stable improvements aren't picked up; ecosystem knowledge and integrations migrate away.
- Effort: 0.5 day (move beta → v5 stable release) or 4–8 days (Better Auth, MIG-004). Autonomy: version bump high; migration medium.
- Null option: acceptable short-term (security patches continue); untenable when (a) a needed feature/provider lands only in Better Auth, or (b) Auth.js announces end of security patches — watch for that announcement.
- Prompt: "Move next-auth from 5.0.0-beta.32 to the v5 stable release; review MIG-004 triggers annually."

### SOTA-007 — Android library stack ~20 months old (Kotlin/AGP/BOM/Hilt/Room/WM/OkHttp/Retrofit) [MEDIUM]
- Confidence: Certain (versions.toml vs [EOL-KT][AGP][BOM][DAGGER][ROOM][WORK][RETRO][OKHTTP-S])
- Impact: compounding upgrade cliff; WorkManager 2.11.2 contains background-network fixes directly relevant to the sync/op-rail workers; AGP 10's mandatory Variant API is queued behind AGP 9.
- Effort: 8–15 days total inside MIG-001's phased plan. Autonomy: medium (build-breakage triage).
- Null option: each quarter of delay adds ~1–2 days of migration friction; untenable when a required fix (e.g., a Play policy or a Compose bug) demands a library version that needs AGP 9 — realistically mid-2027.
- Prompt: "Execute MIG-001 phases 2–4 (Kotlin 2.4/AGP 9/BOM 2026.08 + library train) after the phase-1 targetSdk bump ships."

### SOTA-008 — `gemini-3.1-pro-preview` in two prod paths with no stable pin [MEDIUM]
- Confidence: Certain ([GEM-D]: preview, no shutdown date, 3–9-month-notice policy; application.yml lines 283, 318)
- Impact: goals chat + workout-program designer break on Google's schedule, not yours.
- Effort: 0.5 day when a stable 3.1-pro (or successor) ships: swap + regression-chat. Autonomy: high with human quality check.
- Null option: fine today; untenable the day Google announces the shutdown date — subscribe to the release-notes page and treat announcement as the trigger.
- Prompt: "Watch ai.google.dev release notes for gemini-3.1-pro GA; when it lands, update GOALS_GEMINI_MODEL/WORKOUT_PROGRAM_GEMINI_MODEL defaults and re-run ADR-0005/0013 quality checks."

### SOTA-009 — TypeScript 5.7 vs 6.x/7.0 native [LOW]
- Confidence: Certain ([TS]; package.json 5.7.3)
- Impact: none functional today; growing ecosystem assumption of 6+ (Next 16 supports 5.x fine).
- Effort: 1–2 days. Null option: fine until some dependency requires TS ≥ 6 — likely 2027.
- Prompt: "During MIG-002, try typescript@6 (then 7 native once Next/vitest plugins confirm support); fix new strictness errors."

### SOTA-010 — IaC pins: provider google 7.39.0 vs 8.x; floating `required_version >= 1.5.0` [LOW]
- Confidence: Certain (`.terraform.lock.hcl`, versions.tf; [TFPG][EOL-TOFU]). Note repo actually runs **OpenTofu** (lock provenance registry.opentofu.org).
- Impact: drift risk and surprise-diff risk on eventual forced bump; floating tofu constraint means CI/laptop version skew.
- Effort: 0.5–1 day. Null option: fine ~12 months; untenable when a needed google-provider resource/field is 8.x-only.
- Prompt: "Pin OpenTofu required_version (~> 1.12), then bump provider to 8.x in a dedicated PR whose only acceptance gate is an empty `tofu plan`."

### SOTA-011 — Web minor drift bundle: React 19.2.7→19.3.0, Tailwind 4.3.2→4.3.3, pnpm 10→12, node:22→24 image [LOW]
- Confidence: Certain (package.json/Dockerfile vs [EOL-REACT][TW][PNPM][EOL-NODE])
- Impact: none acute; Node 22 image EOL 2027-04-30.
- Effort: 0.5 day total, ride along with MIG-002. Null option: fine until 2027-04-30 (node image).
- Prompt: "Fold React/Tailwind/pnpm/node-image bumps into the MIG-002 PR."

Positive verdicts (no finding): FCM HTTP v1 ✔; strong-skipping already default ✔; JUnit4 still correct ✔; bespoke sync engine still correct ✔; `gemini-3.8-flash` is a current stable model ✔; Java 21 comfortably in support ✔.

## HYPOTHESES

- H1: The Tailwind and terraform-provider-google release dates returned by fetch summaries showed impossible years (2024) for versions newer than the repo's 2026 pins; version numbers are trusted, those two dates are low-confidence.
- H2: Wear OS app may be exempt-able from the 36 deadline if it's distributed only as a bundled companion (not independently updated) — verify in Play Console before spending effort on wear targetSdk.
- H3: `google-genai` 1.69.0 and `firestore` 3.46.0 backend clients likely have newer releases (not fetched this run — no currency claim made); check during MIG-003.
- H4: Play Console likely shows an active warning banner for the API-36 requirement with a one-click extension request; filing it buys until 2026-11-01 and should be done today regardless of MIG-001 timing.
- H5: vitest ^3.0.5 latest not fetched; probable major drift (v4?) — check during MIG-002.

```json
[
  {"id":"SOTA-001","title":"Play target-API-36 deadline passed (app targets 35, wear 34); updates blockable now, hard stop 2026-11-01","severity":"critical","confidence":"certain","evidence":"android/app/build.gradle.kts:29 targetSdk=35; android/wear/build.gradle.kts:14 targetSdk=34; policy fetched 2026-09-13: https://support.google.com/googleplay/android-developer/answer/11926878 (target 36 required 2026-08-31, wear 35; extension to 2026-11-01)","impact":"Play can reject all app updates; every shipped fix becomes undeliverable","effort_days":3,"autonomy":"high","null_option_cost":"Undeliverable updates immediately; absolute wall 2026-11-01","prompt":"Raise compileSdk/targetSdk to 36 (app) and 35 (wear) per MIG-001 phase 1; review Android 16 behavior changes for FGS/exact alarms/notifications; file Play extension request as insurance."},
  {"id":"SOTA-002","title":"Prod image model gemini-3.1-flash-image-preview past earliest shutdown (2026-06-25); stable gemini-3.1-flash-image available","severity":"critical","confidence":"certain","evidence":"application.yml lines 227/289/309/345 default to gemini-3.1-flash-image-preview; https://ai.google.dev/gemini-api/docs/deprecations (earliest shutdown 2026-06-25) and https://ai.google.dev/gemini-api/docs/models (stable gemini-3.1-flash-image), fetched 2026-09-13","impact":"All food-photo/exercise-media generation can be shut off at any moment with silent-failure history","effort_days":0.75,"autonomy":"high","null_option_cost":"Sudden total image-gen outage on Google's schedule; untenable now","prompt":"Swap the four image-model defaults to gemini-3.1-flash-image, check prod env overrides, update CLAUDE.md, spot-check output quality."},
  {"id":"SOTA-003","title":"exercise-thumbnails Cloud Function on Node 20; runtime decommission 2026-10-30","severity":"high","confidence":"certain","evidence":"functions/exercise-thumbnails/package.json engines=20; https://docs.cloud.google.com/functions/docs/runtime-support (deprecated 2026-04-30, decommission 2026-10-30), fetched 2026-09-13","impact":"Thumbnail pipeline becomes undeployable then shut down","effort_days":0.5,"autonomy":"high","null_option_cost":"Forced outage 2026-10-30","prompt":"Bump engines to Node 24, verify sharp prebuilds, redeploy, confirm a thumbnail generates."},
  {"id":"SOTA-004","title":"Next.js 15 EOL 2026-10-21; repo pinned to 15.5.24 (itself an emergency security patch)","severity":"high","confidence":"certain","evidence":"web/package.json next=15.5.24; https://endoflife.date/api/nextjs.json (15 EOL 2026-10-21; 16.3.5 latest 2026-09-11) and https://nextjs.org/blog (16 stable 2025-10-21; 2026-08-25 security release), fetched 2026-09-13","impact":"Unpatched CVEs on the internet-facing auth'd web app after EOL","effort_days":5,"autonomy":"medium-high","null_option_cost":"Security-patch cutoff 2026-10-21","prompt":"Execute MIG-002 (Next 16.3 + React 19.3 + node:24 image + codemods + e2e)."},
  {"id":"SOTA-005","title":"Spring Boot 3.5 past OSS EOL (D3 ref) and 2 patches behind own line (3.5.14 vs 3.5.16)","severity":"medium","confidence":"certain","evidence":"backend/gradle/libs.versions.toml springBoot=3.5.14; https://endoflife.date/api/spring-boot.json (3.5.16 latest; 3.5 OSS EOL 2026-06-30; 4.1.1 current), fetched 2026-09-13","impact":"No more coordinated CVE trains, the repo's demonstrated patch mechanism","effort_days":7,"autonomy":"medium","null_option_cost":"Security-fix latency grows; untenable ~Q2 2027","prompt":"Bump to 3.5.16 now; execute MIG-003 (Boot 4.1 + Java 25) before Q2 2027."},
  {"id":"SOTA-006","title":"next-auth pinned to 5.0.0-beta.32 while Auth.js is in security-patch mode (maintainers recommend Better Auth for new projects)","severity":"medium","confidence":"high","evidence":"web/package.json; https://blog.logrocket.com/best-auth-library-nextjs-2026/ (pub 2026-04-20): Better Auth team took over Auth.js maintenance Sept 2025, security-patch mode, new-project guidance points to Better Auth; fetched 2026-09-13","impact":"Feature-frozen beta pin on the auth layer; ecosystem moving away","effort_days":0.5,"autonomy":"high","null_option_cost":"Acceptable until Auth.js ends security patches or a needed capability is Better-Auth-only","prompt":"Move to next-auth v5 stable; review MIG-004 triggers annually."},
  {"id":"SOTA-007","title":"Android toolchain/library stack ~20 months old (Kotlin 2.0.21→2.4.20, AGP 8.7.3→9.4.0, BOM 2024.12→2026.08, Hilt/Room/WM/OkHttp/Retrofit behind)","severity":"medium","confidence":"certain","evidence":"android/gradle/libs.versions.toml vs endoflife.date/kotlin, developer.android.com AGP/BOM/Room/Work pages, dagger+retrofit GitHub releases, OkHttp 5.5.0 search — all fetched 2026-09-13","impact":"Compounding upgrade cliff; WM 2.11.2 background-network fixes relevant to sync workers unavailable; AGP 10 Variant API queued","effort_days":12,"autonomy":"medium","null_option_cost":"~1-2 extra migration days per quarter of delay; untenable ~mid-2027","prompt":"Execute MIG-001 phases 2-4 after the targetSdk-36 bump ships."},
  {"id":"SOTA-008","title":"gemini-3.1-pro-preview (goals chat, program designer) has no stable pin; preview lifecycle is 3-9 months notice","severity":"medium","confidence":"certain","evidence":"application.yml lines 283/318; https://ai.google.dev/gemini-api/docs/deprecations (preview policy; no shutdown date announced), fetched 2026-09-13","impact":"Two flagship AI features break on Google's schedule","effort_days":0.5,"autonomy":"high","null_option_cost":"Fine until shutdown announcement — that announcement is the trigger","prompt":"Watch Gemini release notes; on 3.1-pro GA, swap defaults and re-run ADR-0005/0013 quality checks."},
  {"id":"SOTA-009","title":"TypeScript 5.7.3 vs 6.0.3/7.0.2 (native)","severity":"low","confidence":"certain","evidence":"web/package.json; https://github.com/microsoft/TypeScript/releases (7.0.2, 2026-08-20), fetched 2026-09-13","impact":"None acute; ecosystem drift","effort_days":1.5,"autonomy":"high","null_option_cost":"Fine until a dep requires TS>=6 (~2027)","prompt":"Try TS 6 during MIG-002; TS 7 native after plugin ecosystem confirms."},
  {"id":"SOTA-010","title":"OpenTofu provider google locked at 7.39.0 (latest 8.2.0); required_version floats >=1.5.0","severity":"low","confidence":"certain","evidence":"infra/terraform/.terraform.lock.hcl (registry.opentofu.org, 7.39.0), versions.tf; https://github.com/hashicorp/terraform-provider-google/releases (v8.2.0) + https://endoflife.date/api/opentofu.json (1.12.6, 2026-08-19), fetched 2026-09-13","impact":"Version-skew and surprise-diff risk on forced bump","effort_days":1,"autonomy":"high","null_option_cost":"Fine ~12 months; untenable when an 8.x-only resource is needed","prompt":"Pin tofu ~>1.12; bump provider to 8.x in a PR gated on empty tofu plan."},
  {"id":"SOTA-011","title":"Web minor drift: React 19.2.7→19.3.0, Tailwind 4.3.2→4.3.3, pnpm 10.32→12.4, node:22-alpine (LTS now 24)","severity":"low","confidence":"certain","evidence":"web/package.json + web/Dockerfile vs endoflife.date/react+nodejs, tailwind + pnpm GitHub releases, fetched 2026-09-13","impact":"None acute; node:22 image EOL 2027-04-30","effort_days":0.5,"autonomy":"high","null_option_cost":"Fine until 2027-04-30","prompt":"Fold into the MIG-002 PR."}
]
```
