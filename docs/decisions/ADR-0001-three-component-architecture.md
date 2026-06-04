# ADR-0001: Three-component architecture (native Android + Next.js web + Spring backend)

- Status: Accepted
- Date: 2026-05-20

## Context

The product needs to surface live workout sessions, longitudinal health data,
medical research browsing, and an LLM chat interface, on the user's phone,
on their wrist, and on the web. We needed to pick a stack that covers all
three surfaces without producing the worst version of each.

Three alternatives were considered:

1. **Flutter everywhere** — single Dart codebase for phone, watch, and web.
2. **Native Android + a PWA bundled inside the Android app** — no web app,
   the web view is a feature of the phone app.
3. **Native Android + a standalone Next.js web app + Spring Boot backend** —
   three deployable components, clear boundaries between them.

Constraints that influenced the choice:

- **Wear OS** is a first-class surface. Flutter's Wear OS story is weak;
  Health Services Client and Tiles are Android-native.
- **Health Connect** is the Android-canonical health data API and has only a
  Kotlin client.
- **Foldables and large screens** matter (Pixel Fold class devices). Compose
  has the strongest tooling for adaptive layouts here.
- **Medical research browsing** wants SSR for fast first paint and SEO-style
  link sharing — a PWA inside an app cannot do that.
- **LLM chat** wants Server-Sent Events streaming from a long-lived server-
  side fetch; Next.js Server Components plus a Spring streaming endpoint is
  the cleanest path.
- The backend is the integration point for the Google Health API webhook
  and needs to run on a stable JVM with long-lived libraries.

## Decision

Adopt three deployable components:

- A **native Android** application (Kotlin 2.0, Jetpack Compose, Material 3),
  with a phone app and a standalone Wear OS app sharing a Gradle multi-module
  layout.
- A **Next.js 15 App Router** web application (TypeScript strict, Tailwind v4,
  pnpm) deployed to Cloud Run.
- A **Spring Boot 3.5** backend (Java 21, Gradle Kotlin DSL, single-module)
  deployed to Cloud Run, persisting to Cloud Firestore.

iOS is explicitly out of scope for now. No directory is reserved for it.

## Consequences

Positive:

- Each surface uses the platform-canonical toolchain. No second-class
  experience on Wear OS or on the web.
- Backend and web share the same deployment target (Cloud Run), the same
  region, the same container patterns, and the same CI/CD shape.
- Firestore is owned by exactly one component (the backend), so consistency
  rules and migrations have one home.
- The web app can stream LLM responses with SSE without bridging through the
  Android app.

Negative:

- Two client codebases to maintain (Android + web). Some product surfaces
  will be built twice.
- More moving parts in CI: three separate workflows, three deploy paths.
- Type-sharing across the language boundary (Java DTOs vs TypeScript types
  vs Kotlin models) has to be done by convention or codegen; we accept the
  duplication for now.

## Revisit when

- iOS becomes scope (introduces a fourth client and forces a re-evaluation
  of cross-platform options).
- The set of features that exist *only* on the watch or *only* on the web
  becomes small enough that consolidating surfaces would be cheaper than
  maintaining two clients.
