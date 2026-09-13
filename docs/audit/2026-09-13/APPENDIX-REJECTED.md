# APPENDIX — Skeptic pass: killed & demoted findings

Skeptic pass 2026-09-13. Method: every cited file/line re-opened; live re-verification performed for DATA-001 (Firestore Admin REST: `pointInTimeRecoveryEnablement: POINT_IN_TIME_RECOVERY_DISABLED`, `versionRetentionPeriod: 3600s`, `deleteProtectionState: DELETE_PROTECTION_DISABLED`, `backupSchedules: {}`) and CICD-001 (`gh api .../branches/main/protection` → 404; `android-ci FAILURE main 15ecf18 2026-09-13T19:57`). Global constraint applied: ≤7 criticals may survive; **5 survive**.

## Surviving criticals (for the record, ranked)

1. **DATA-001** — no backups/PITR/delete-protection on prod health-data Firestore. Re-verified live this pass. [Certain]
2. **OBS-001** — zero alert policies/uptime checks/channels/log metrics/dashboards; Error Reporting disabled. Root enabler of all four documented silent incidents. [Certain]
3. **CICD-001** — no branch protection; CI does not gate deploys; red-test SHA live-proven shipped. Re-verified 404 this pass. [Certain]
4. **PERF-001** — sync reader re-enumerates every parent doc (`listDocuments()`, unbounded) per page; verified verbatim in `FirestoreSyncChangeReader.java` (leafCollections frontier walk + one `scan()` query per leaf). Chesterton check: the N+1 is a *deliberate, documented* privacy trade ("never issues a collectionGroup query... never reads another user's documents") — the fence explains the shape, not the absence of any growth bound; the proposed fix (per-leaf `userId` field + collectionGroup, or cursor-derived date floor on day-doc enumeration) preserves the user-scoping property. Mechanism [Certain], magnitude [Likely]. Survives.
5. **XPLAT-001** — nutrition "today" computed three ways. Verified: `web/app/me/nutrition/page.tsx:41-43` `toISOString()` (UTC, server component); `NutritionController.java:123,136,861` `LocalDate.now()`; `X-Timezone` consumed only by WorkoutProgram/TodaysDoses/RequestTimeZone (repo-wide grep). Silent misdating of evening web logs in a regulated health ledger; the correct in-repo pattern exists and was skipped. [Certain code / Likely prod impact — server-UTC asserted by two in-repo comments]. Survives.

---

## KILLED (as stated)

### SOTA-001 — "Play targetSdk-36 deadline passed; updates blockable now" — KILLED as critical; residual **medium**
**Killing argument:** the premise is false. The app is **not on Google Play**. `docs/reference/deployment.md:117`: "There is **no Play Store / production track** wired — Android 'release' today means distribute to the internal tester group." `android/cloudbuild.yaml` distributes an APK via `firebase appdistribution:distribute --groups internal-testers`; no Play/AAB/track config exists anywhere in the repo (CICD-004's evidence, verified this pass). Firebase App Distribution imposes **no targetSdk requirement**, and Android itself does not block installs/updates by targetSdk at these levels. Therefore "Play can reject every app update today" and "hard stop 2026-11-01" describe a distribution channel this app does not use; nothing becomes undeliverable on any date. **Residual truth (medium):** targetSdk 35/34 (verified `android/app/build.gradle.kts:29`, `android/wear/build.gradle.kts:14`) is a hard prerequisite with lead time the day a Play listing is desired — same latent, Play-gated class as SUP-003. Fold into MIG-001 planning; no emergency. [Certain]

### UX-002 — "notifications default lock-screen-public; med names are PHI on lock screen" — KILLED as high; residual **low**
**Killing argument:** the mechanism claim ("no `setVisibility` → every notification is VISIBILITY_PUBLIC-equivalent") is factually wrong. The Android default for `Notification.visibility` is **`VISIBILITY_PRIVATE`**, not public. Consequences: (a) for users who enable "show sensitive content only when unlocked," the OS *already redacts* these notifications today — medication names are already hidden for exactly the privacy-conscious population the finding worries about; (b) under the default OS setting ("show all notification content"), content is shown — but adding `.setVisibility(VISIBILITY_PRIVATE)` changes **nothing** there, because it is already the default; the app cannot override the user's OS-level choice short of `VISIBILITY_SECRET`, which would hide medication reminders from the lock screen entirely (a worse outcome for a med-adherence app). The absence of `setVisibility` calls is verified (repo grep — only unrelated biometrics methods match), but the proposed fix's real delta is only a nicer custom `setPublicVersion` for redaction-enabled users, who currently get the OS-generic "Content hidden" redaction — which already conceals the med names. Cosmetic hardening: **low**. [Certain on Android default semantics; Certain on repo absence]

---

## DEMOTED

### PERF-002 — critical → **high**
Measurements are real (15.7s startup p95, no `min-instances` — grep re-verified) and the cold-start chain is credible. But the harm is a once-daily ~20s latency hit for an n≈1 user base — it cannot share a tier with unrecoverable-health-data-loss exposure. Additionally it is in direct tension with COST-001: COST-001's fix (drop `--no-cpu-throttling`, go request-based billing) *increases* cold-start frequency, while PERF-002's fix (`--min-instances=1` with always-on CPU) *increases* the idle burn COST-001 targets. The reconciled end-state (request-based billing + min-instances=1 idle-throttled, plus CDS) must be decided jointly — neither finding should be executed alone. High.

### OBS-002 — critical → **high**
Evidence solid (no Crashlytics/Sentry anywhere — re-verified in `libs.versions.toml` and `web/package.json`). But the impact framing assumed a "Play-distributed" fleet with Play-vitals fallback; actual distribution is Firebase App Distribution to a handful of internal testers (CICD-004), Wear is a 5-file stub, and the "no signals" critical slot is already occupied by OBS-001, which is the root fix. Client crash reporting is the correct second step, not a co-equal critical. Also note COMP §5's friction flag: adding Crashlytics/Sentry adds a sub-processor and requires privacy-policy §4 update before shipping. High.

### TEST-001 — critical → **high**
Claim verified: only 3 backend test files call `subject(` at all; none uses two distinct subjects; the sole scoping test is repo-level. But D2's independent sweep verified the actual scoping convention **holds everywhere today** ("userId is a document-path segment from JWT `sub` in every repo checked" — SEC Verified-safe). This is a missing regression guard against a *future* refactor mistake, not a live defect; criticals here are reserved for live-verified harms (DATA-001, CICD-001 class). Highest-priority high; pairs with the fact that main is currently a refactoring branch. High.

### TEST-002 — critical → **high**
Largely duplicative of CICD-001: same root cause (no branch protection / no deploy gating), same live proof, same 0.5-day fix — keeping both as criticals double-counts one defect. The genuinely distinct halves (instrumented tier runs nowhere; UAT rotting; playwright vacuous) are already carried as TEST-003/TEST-004/TEST-007. High, explicitly cross-referenced to CICD-001 as the fix owner.

### SUP-001 — critical → **high**
EOL facts verified (Boot 3.5.14 pinned; 3.5/6.2 OSS ended 2026-06-30 per fetched endoflife.date). But no currently-known-unpatched Spring CVE is identified; the repo's demonstrated pin treadmill still covers the high-churn CVE sources (jackson/netty/tomcat — the `build.gradle.kts` override block is actively maintained, last touched for the 6.2.19 CVE train); commercial support exists as an emergency valve. The forcing event is probabilistic and the migration (Boot 4.1, 3–5+ days) is planned-work class with a sensible Q1-2027 window. A time-bomb with an unexercised trigger is a high, not a critical. High — schedule MIG-003; bump to 3.5.16 now (0.25d, still-available final patches).

### SOTA-002 — critical → **high**
Facts verified (4 defaults at `application.yml:227,289,309,345` = `gemini-3.1-flash-image-preview`; earliest-shutdown date passed per fetched deprecations page). But the model **demonstrably still serves** — "earliest possible shutdown" passed is a risk window, not an outage — and the blast radius on shutdown is cosmetic image generation (studio food images, exercise media) with a documented history of *graceful-if-silent* degradation, on a path COST-002 recommends mostly turning off anyway. A 15-minute config swap should simply be done this week; urgency ≠ criticality. High.

### UX-001 — critical → **medium**
Evidence verified verbatim (one-tap `clickable { onDelete() }`, 18dp icon, no Confirm/AlertDialog in either file; web confirms with `useConfirm`). But calibration fails the critical bar by a wide margin: the loss is a **single meal entry**, destroyed by an action the user takes with immediate visible feedback (row shows a pending spinner and disappears in front of them), re-loggable, and server-side tombstoned (operator-recoverable in principle). The same report rates the *invisible* manufacture of a false zero-calorie day (UX-003) high — a visible, bounded, user-initiated mis-tap cannot outrank an invisible data-display failure. Real fix, 0.5 day, platform-consistency and touch-target arguments stand. Medium.

### CICD-003 — high → **medium**
Mechanism verified certain (`|| true` unshallow, epoch fallback ≈1.49M vs count 634). But the landmine requires a transient unshallow failure to arm (unobserved to date — H2 unconfirmed), and the post-arming blast radius is the internal-tester fleet (a handful of installs) with a bounded recovery (uninstall/reinstall; local data re-derivable from server sync). Fix is cheap and worth doing; probability×impact is medium.

### SUP-003 — high → **medium**
Deprecation verified (fetched README), but: the library is stable, no CVE in the shipped artifact is cited, and both cited forcing functions are Play-gated (16KB page compliance, Play distribution) — and the app is not on Play (see SOTA-001 kill). Same latent class, same recalibration. Migrate with MIG-001; medium.

### DATA-002 — high → **medium**
Premise verified live (no `ttlConfig` on prod; `firestore_ttl.tf:33` targets `(default)`). But impact is inert accumulation: idempotency correctness is preserved by the in-code expired-as-absent re-check (`FirestoreIdempotencyStore.java:47-49`, cited by the finding itself); revoked refresh tokens are stored as SHA-256 hashes and are inert; cost at this scale is cents. "IaC record is falsified" is a hygiene/compliance-doc point. 0.5-day fix, medium.

### DATA-007 — medium → **low** (explicitly NOT promoted)
The report asked whether this is silent PHI loss deserving promotion. Verified the opposite direction — two mitigations the finding underweights: (1) the outbox wipe is a **deliberate, documented design decision**, not an oversight — `DbWiper.kt:27-29`: "The outbox is cleared too — on a schema bump any pending local mutations are encoded against the old schema and must be re-derived, not blindly replayed" (replaying old-schema payloads risks 400-parked chains or corrupt writes — the cure the finding proposes is the disease the design avoids); (2) sync ordering drains the outbox **before** pulling (`SyncWorkers.kt:62-63,83-85`: `outbox.drain(); syncEngine.pull()`), so at the moment a schemaVersion mismatch is detected the outbox is empty for any online device — the loss window is offline-authored writes coinciding with a rare, operator-controlled protocol bump. A WARN log when nonzero outbox rows are wiped is a fair 1-hour ask; low.

### XPLAT-002 — high → **medium**
Absence of version negotiation verified. But "fleet parse crash" calibrated against the actual fleet: Firebase App Distribution to internal testers (~1 real user, the operator), no store-review lag, ~12-minute hotfix latency, and the operator controls both ends of every deploy — MTTR and blast radius are tiny today. The additive-and-nullable DTO discipline risk on a refactoring branch is real, which keeps this medium rather than low. Re-promote trigger: any external install base or Play distribution.

### PROD-002 — high → **medium**
Evidence verified (error-styled copy on null in `NutritionCard.tsx:59-62`; no onboarding routes). But there is no user #2; the finding's own null-option cost is "zero conversion from *future* marketing." The sibling UX auditor rated the identical shared evidence low (UX-011: "near zero at n=1 users") — the domains disagree and the calibrated middle at n=1 with an explicit trigger is medium: schedule the week before showing the product to anyone.

---

## Contradiction resolutions (requested)

1. **SOTA-001 vs CICD-004 (Play status):** resolved in CICD-004's favor — not on Play. Authority: `docs/reference/deployment.md:117` ("no Play Store / production track"), `android/cloudbuild.yaml` (App Distribution APK, debug-keystore comment at lines 2-4), no fastlane/Play config repo-wide. targetSdk deadlines do not bind App Distribution installs. SOTA-001 killed as critical (above). Any future SOTA run must check distribution channel before applying store policy deadlines.
2. **COST-006 / DATA H1 ((default) DB populated):** confirmed independently by two agents (live `listCollectionIds`: `drugs, equipment, exercises, foodCatalog, mealCatalog, refreshTokens, users`). No skeptic action — noted as settled; the risk owner is DATA (unmanaged PHI + credential copy), the dollar cost is ~nil.
3. **SEC-006 (medium) vs CICD-004 (high) on the debug keystore:** not a contradiction — different harms from one artifact. SEC-006's *key-exposure* angle is correctly medium (exploitation requires user-side sideload); CICD-004's *migration-trap* angle is correctly high (every added install raises the cost of the unavoidable one-time signature break, and the fix pairs with the FCM cert registration that unblocks prod push). Not-on-Play removes the Play-App-Signing forcing function but doesn't change either severity.

## Evidence errata found (for report owners; findings otherwise intact)

- **OBS-002** impact text says "For a Play-distributed app" — wrong channel (App Distribution). Weakens impact, not evidence.
- **SOTA-001** premise wrong (above). SOTA report's own H2 (wear exemption) was moot for the same reason.
- **UX-002** mechanism wrong: Android default visibility is `VISIBILITY_PRIVATE`, not public-equivalent (above).
- **UX-001 vs UX-011 / PROD-002 vs UX-011**: same evidence rated critical/high/low across three findings; reconciled here (UX-001→medium, PROD-002→medium, UX-011 stays low).
- **PERF-002 vs COST-001**: fixes are mutually exclusive as prompted; must be executed as one decision (noted in both demotion entries).
- **TEST-002 vs CICD-001**: duplicate root cause; CICD-001 is the fix owner.

## Survived-at-severity (verified, no change) — one line each

SUP-002 (netty pin 4.1.137 verified at `build.gradle.kts:31`; Trivy-gate blockage matches documented deploy-gate history) · SOTA-003 (`"node": "20"` verified; hard GCF decommission date) · SOTA-004 (Next 15.5.24 pinned; EOL in 5 weeks on internet-facing auth'd app) · SEC-001 (open provisioning verified `UserProvisioningFilter.java:32-34`; no `/api/**` limiter; 30MB multipart) · UX-003 (verified verbatim `getDay(date).catch(() => null)` → `emptyDay()`) · COST-001 (flags + rationale comment verified; the `runAsync` Chesterton fence is real and the finding's fix correctly sequences queue-migration *before* flag removal; keepalive math consistent with 880 req/day vs ~15-min idle-out; joint decision with PERF-002 required) · COST-002 (cache bypass verified: `boolean cacheable = refBytes == null` — every photo capture pays full image generation by design comment "a photo-referenced meal is unique") · OBS-003 (default console appender verified; prerequisite for OBS-001 alert quality) · COMP-001 (promises verified at `privacy.html:131,153,165,175` vs verified-absent deletion and verified-public buckets `bootstrap-gcp.sh:78,94` — note §5 is false *today* even at n=1, given unauthenticated photo URLs; not purely user-#2-gated).
