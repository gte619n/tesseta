# Deferred major-version migrations

Dependabot's first run (after automation was enabled) grouped everything —
including **major** bumps — into three PRs that all failed CI because they bundle
breaking migrations. Those PRs were **closed**, and `.github/dependabot.yml` now
groups only minor/patch so majors arrive as individual, reviewable PRs.

The three breaking major bumps are captured here as deliberate migrations to plan
and do **one at a time**. None is a routine bump.

## 1. Backend — Spring Boot 3.5 → 4.x  *(largest)*

- **What:** `springBoot 3.5.0 → 4.1.0` (+ `google-cloud-firestore 3.30 → 3.43`).
- **Why it broke CI:** Spring Boot 4 reorganized packages —
  `org.springframework.boot.test.autoconfigure.web.servlet` no longer exists, so
  `@WebMvcTest` / `@AutoConfigureMockMvc` imports in the sync controller tests
  (`SyncDeltaControllerTest`, `SyncContractIntegrationTest`,
  `SyncRecentWindowControllerTest`, `IdempotentNestedWriteControllerTest`, …)
  fail to compile.
- **Scope:** Spring Boot 4 = Spring Framework 7 + Jakarta EE 11 + a Java 17
  baseline (we're on 21, fine). This is a **framework migration**: audit test
  imports, deprecations, and any removed autoconfig. Do it on its own branch with
  the full suite (incl. the emulator integration tests) as the gate.
- **Firestore 3.43** on its own is a safe minor bump and will now come via the
  grouped `backend-deps` PR once the majors are separated.

## 2. Web — Next.js 15 → 16 (+ TypeScript 6, ESLint 10)

- **What:** `next 15.5.18 → 16.2.10`, `typescript 5.7 → 6.0`,
  `eslint 9 → 10`, `eslint-config-next 15 → 16`.
- **Why it broke CI:** `pnpm lint` fails under the new toolchain (ESLint 10 +
  eslint-config-next 16 rule/flat-config changes).
- **Scope:** three coupled majors. Next 16 has App Router + build changes; TS 6
  and ESLint 10 each have their own breaking changes. Migrate together on a
  branch, re-run `typecheck` + `lint` + `test` + `test:e2e`. Keep `next-auth` on
  its current line until Next 16 compat is confirmed.

## 3. CI — GitHub Actions majors

- **What:** `actions/checkout v4→v7`, `actions/setup-java v4→v5`,
  `actions/cache v4→v6`, `actions/upload-artifact v4→v7`,
  `android-actions/setup-android v3→v4`.
- **Why it broke CI:** a workflow build fails on the new action majors (likely
  `upload-artifact` v4→v7, which changed artifact semantics).
- **Scope:** smallest of the three. Bump the actions (keeping SHA-pins), re-pin
  the SHAs, and fix any changed inputs/outputs — most likely the
  `upload-artifact` usages. Verify all workflows go green.

## How these will now surface

With the grouping fix, Dependabot will raise each of these as its **own** PR
(not bundled), so they can be taken on individually without blocking the routine
minor/patch group PRs. Until then they're tracked here.
