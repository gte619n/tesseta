# CLAUDE.md — backend

- Single-module Gradle (Kotlin DSL build file, Java source). One `build.gradle.kts`
  at `backend/`; all source under `src/main/java` + `src/test/java`.
- Java 21 LTS, virtual threads enabled by default. Don't add
  `spring-boot-starter-webflux` without a real reason — reactive is heavier
  to read and maintain than synchronous-on-virtual-threads for this app.
- **No Lombok.** Explicit code only. Use Java records for DTOs.
- **No JPA, no SQL.** Cloud Firestore is the source of truth.
- Run locally: `./gradlew bootRun`
- Test: `./gradlew test`

## Layering (packages, by convention)
This was a five-module Gradle build whose only payoff was compile-time
enforcement of the layering below. For a single, non-shared Cloud Run
deployable that ceremony wasn't worth it, so it's now **one module** and the
layering is a **convention** (no build/test enforcement — keep it in review):
```
api → core, integrations    persistence → core    integrations → core
```
Packages are **`<layer>.<feature>`** — e.g. `api.goals`, `core.goals`,
`persistence` (Firestore impls), `integrations.googlehealth`. Put new code in
its feature sub-package under the right layer; don't reintroduce root-level
feature packages.
- `com.gte619n.healthfitness` (root) — boot entrypoint (`HealthFitnessApplication`,
  `SecurityConfig`), plus `auth`/`config`/`admin`/`push` (cross-cutting infra) and
  `jobs` (Cloud Run batch entrypoints, `@Profile`-gated `CommandLineRunner`s).
- `core.<feature>` — plain domain records + **pure-domain** services (pulls in
  `spring-context` for `@Service`/events/`@Cacheable`). Keep **Spring Web** out.
- `api.<feature>` — controllers and DTOs, **and** the feature's
  integration-orchestrating service (the one that calls `integrations` clients;
  its controller is its only caller). May use `integrations` clients.
- `persistence` — Firestore repository implementations.
- `integrations.<feature>` — Google Health API client + webhook receiver and the
  Gemini clients.

For the full picture (data model, endpoints, the Goals metric-event engine,
Gemini/KMS/caching patterns) see [`docs/reference/`](../docs/reference/).

## Conventions
- DTOs are records.
- Controllers depend on services; trivial pass-through reads may use a
  repository directly (several already do — `WhoAmI`, `Device`, `BodyComposition`).
- Configuration via `application.yml` and env vars. No `application-{profile}.yml`
  beyond `application-test.yml` for tests.
