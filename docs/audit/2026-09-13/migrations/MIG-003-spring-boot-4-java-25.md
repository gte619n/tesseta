# MIG-003 — Spring Boot 3.5 → 4.1 + Java 21 → 25 (backend/)

Trigger: Boot 3.5 OSS EOL passed 2026-06-30 (D3 finding, re-confirmed this run: https://endoflife.date/api/spring-boot.json fetched 2026-09-13 — 3.5 latest is 3.5.16, current line 4.1.1 released 2026-06-30). Java 25 is the current LTS (released 2025-09-16, support to 2030-09-30; Java 21 supported to 2028-09-30) — https://endoflife.date/api/oracle-jdk.json fetched 2026-09-13.

## Case for
- The repo's own `libs.versions.toml` header documents that it depends on Boot's **coordinated CVE trains** (spring-framework/security/tomcat bumped in lockstep). Those trains stop on the OSS line after EOL; each month past EOL widens the unpatched window on an internet-facing API holding health data.
- Skipping to 4.1 (not 4.0) lands on the line with the longest runway (OSS EOL 2027-07-31).
- Java 25 fold-in is nearly free once the build is already open: toolchain `languageVersion` 21→25 (`backend/build.gradle.kts:43`) + `eclipse-temurin:25` in `backend/Dockerfile`; Java 21 remains supported to 2028 so this is opportunistic, not deadline-driven.

## Case against
- Boot 4 / Framework 7 is a major: property renames, starter reorganization, possible springdoc/actuator/security DSL churn; the Google client stack (firestore 3.46.0, google-auth 1.51.0 with its documented mtls lockstep trap, genai, firebase-admin, tink) must be re-verified against the new Framework baseline.
- Nothing is on fire the way MIG-001/002 are: commercial-support backport availability and the low CVE surface of a single-tenant-ish API make a measured Q4-2026/Q1-2027 slot acceptable.

## Plan (incremental)
1. **Now (0.25 d):** springBoot 3.5.14 → 3.5.16 patch (still published on the line) — restores currency while the major migration waits.
2. Boot 3.5 → 4.0 → 4.1 stepwise on a branch: run the properties migrator, fix starter/config changes, re-run full test suite (watch the deploy-gate traps in memory: Trivy CVE gate + stale path-filtered checks will surface on the first backend merge).
3. Java toolchain 21 → 25 + Dockerfile base images; CI JDK.
4. Re-verify Google client BOM alignment (the firestore/google-auth mtls lockstep comment in versions.toml) and jjwt/jackson/netty overrides — Boot 4's managed versions likely supersede the hand-pinned CVE overrides (delete them where superseded; D3's netty note).

Effort: 5–10 solo days (majority in steps 2 and 4).

## Blast radius
`backend/` only: ~1 build file + versions.toml + Dockerfile + application.yml property renames; controller/service code largely untouched (jakarta migration already done in Boot 3). No client-visible API change intended.

## Reversibility / rollback trigger
Branch-isolated; prod deploy is merge-to-main, so rollback = revert merge + redeploy. Trigger: startup failure or Firestore/KMS bean wiring breakage not fixed within 2 days → revert, file the specific incompat, retry next cycle.

## User impact
None intended (API-compatible). Watch auth (JWT resource server DSL) and the refresh-token grace config (`app.session.reuse-grace`, memory) survive property migration.

## Privacy re-verification (regulated mode)
Same data flows; re-verify security filter chain ordering and actuator exposure after the security DSL migration (health-data API).

## Do-nothing
Carrying cost: growing lag between CVE disclosure and a patch you can actually consume; the repo's history (four CVE-driven bumps documented in versions.toml comments alone) shows this is a real, recurring need — expect ~1 forced firefight per quarter, each harder without Boot's coordinated train. Untenable date: **~Q2 2027**, or immediately upon the first HIGH/CRITICAL CVE in spring-security/tomcat with no 3.5 OSS backport (the Trivy deploy gate will then block all backend deploys — memory shows this failure mode has already occurred).
