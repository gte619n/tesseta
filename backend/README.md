# health-fitness-backend

Spring Boot 3.5 on Java 21. Single-module Gradle (Kotlin DSL). Persists to
Cloud Firestore. Deploys to Cloud Run as `health-fitness-backend` in
`us-central1`.

## Run locally
```bash
./gradlew bootRun
```

Liveness: <http://localhost:8080/actuator/health>
Hello: <http://localhost:8080/api/hello>

## Test
```bash
./gradlew test
```

## Build for container
```bash
./gradlew bootJar
docker build -t health-fitness-backend .
```

## Package layout
A single Gradle module; code is grouped by `com.gte619n.healthfitness.*`
sub-packages (no compile-time enforcement — keep the layering by convention):
- root + `auth`/`config` — boot entrypoint, security, wiring; holds `application.yml`
- `api` — REST controllers, request/response DTOs
- `core` — domain models and services
- `persistence` — Firestore repositories
- `integrations` — Google Health API client + webhook receiver, Gemini clients
