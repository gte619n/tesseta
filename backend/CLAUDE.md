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
- `com.gte619n.healthfitness` (root) + `auth`/`config` — boot entrypoint,
  security, and bean wiring.
- `core` — plain domain records + services (pulls in `spring-context` for
  `@Service`/events/`@Cacheable`). Keep **Spring Web** out of it.
- `api` — controllers and DTOs (may use `integrations` clients). Feature
  services that orchestrate `integrations` (dexa, bloodtest, …) live here too.
- `persistence` — Firestore repository implementations.
- `integrations` — Google Health API client + webhook receiver and the Gemini
  clients.

For the full picture (data model, endpoints, the Goals metric-event engine,
Gemini/KMS/caching patterns) see [`docs/reference/`](../docs/reference/).

## Conventions
- DTOs are records.
- Controllers depend on services; trivial pass-through reads may use a
  repository directly (several already do — `WhoAmI`, `Device`, `BodyComposition`).
- Configuration via `application.yml` and env vars. No `application-{profile}.yml`
  beyond `application-test.yml` for tests.
