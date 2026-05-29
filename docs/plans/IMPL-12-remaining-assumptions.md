# IMPL-12 Goals — Remaining-Work Assumptions Log

This document records best-guess assumptions made while completing the
remaining IMPL-12 build-sequence steps (6–10) autonomously. Each entry is
a decision the spec left open or where the spec diverged from the
codebase. Review and correct any you disagree with.

**Author:** automated build session, 2026-05-28.
**Scope of remaining work:** backend Gemini chat endpoints (step 7 backend),
web roadmap detail + manual editor + write mutations (step 6), web chat
(step 7), Android Goals list/roadmap/chat (steps 8–9).

---

## Cross-cutting

1. **Route prefix is `/api/me/goals/...`, not `/api/goals/...`.** The whole
   backend is multi-tenant (`CurrentUserProvider`, ADR-0002). The spec's
   `/api/goals/...` paths were reconciled to `/api/me/goals/...` in the
   foundation PR; chat endpoints follow suit:
   - `POST   /api/me/goals/chat` (SSE)
   - `POST   /api/me/goals/chat/{threadId}/commit`
   - `GET    /api/me/goals/chat/threads`

2. **Web route is `/me/goals`, not `/goals`.** The existing landing page
   lives at `web/app/me/goals/page.tsx`. Detail is `/me/goals/[id]`, chat is
   `/me/goals/chat` (or a modal/panel — see web section).

## Backend — Gemini chat

3. **Gemini client:** reuse the existing `com.google.genai:google-genai`
   (1.53.0) client in the `integrations` module, same pattern as
   `BloodTestExtractor`. Model `gemini-3.5-flash`, API key from
   `${GEMINI_API_KEY}` via `@Value("${app.goals.gemini-api-key:${GEMINI_API_KEY:}}")`.
   New config block `app.goals.*` in `application.yml`.

4. **Tool calling:** `propose_goal_structure` declared as a Gemini
   `FunctionDeclaration` with a JSON schema mirroring the spec's tool
   params. The metric key registry is injected verbatim into the system
   prompt. When the model emits a function call, the backend validates it
   and streams the structured proposal to the client.

5. **SSE transport:** `SseEmitter` + virtual thread, mirroring
   `BloodTestController`. Event types streamed: `token` (assistant text
   deltas), `proposal` (validated `GoalProposalDto` JSON), `error`, and a
   terminal `done`. 120s timeout.

6. **Chat persistence:** `users/{userId}/goalChatThreads/{threadId}` with a
   `messages` subcollection (`role`, `content`, `createdAt`, optional
   `proposalJson`). A new `core/goals/chat` package with records +
   repository interface, Firestore impl in `persistence`, in-memory test
   impl. Thread is created lazily on first message if `threadId` omitted.

7. **Proposal validation** produces a `GoalProposalDto` where each field
   can carry an inline `validationError` string (not silently dropped), per
   spec acceptance criteria. Validations: metricKey ∈ registry; comparator
   legal for metric kind (COUNT ⇒ GTE/GT/EQ only, no LT/LTE); windowDays
   present iff SUSTAINED; countFrom defaulted to now iff COUNT; phase dates
   ordered & non-overlapping; targetDate in the future.

8. **Commit flow:** `POST /api/me/goals/chat/{threadId}/commit` accepts the
   (user-edited) final `GoalProposalDto`, re-validates, then composes the
   existing `GoalService` create + phase-add + step-add calls in one
   server-side transaction-like sequence, runs an initial evaluation, and
   returns the new `goalId`. It does **not** invent a new persistence path —
   it reuses the foundation's service methods.

9. **Manual creation does not go through chat or Gemini.** The web manual
   editor posts directly to the existing CRUD endpoints (create goal, then
   phases, then steps). The commit endpoint is chat-only.

## Web

10. **Chat library:** install `@assistant-ui/react` (latest stable) per the
    spec. If integration friction is high, the fallback is a thin custom
    chat client over the SSE endpoint using the existing fetch/stream
    primitives — but the first attempt uses assistant-ui as specified.
    **(If this assumption changed during build, it is noted in the final
    summary.)**

11. **`<GoalProposalCard>` is the shared editable structure** used both
    inline in chat and as the standalone manual editor (opened blank). One
    component, two entry points, per spec.

12. **Write mutations use Next.js server actions** calling `apiFetch`, then
    `revalidatePath` — matching the established blood/meds pattern. No
    client-side direct backend calls (backend URL is server-only).

13. **RangeIndicator:** extract the inline `RangeBar` logic from
    `blood/page.tsx` into a reusable `components/ui/RangeIndicator.tsx`
    rather than duplicating, used for metric-bound step readouts.

14. **Optimistic step checking:** the roadmap detail checkboxes call a
    server action and revalidate; no client optimistic state in v1 (keeps it
    consistent with the rest of the app's server-component model).

## Android — biggest assumptions (infra is greenfield)

15. **Networking, DI, and navigation are not yet wired in the Android app.**
    Completing Goals requires standing up that infra. Decisions:
    - **Activate Hilt:** add `@HiltAndroidApp` Application class, register it
      in the manifest, add a `core-data` network module providing a shared
      `Retrofit` (base URL from `BuildConfig`/existing config) with an
      OkHttp auth interceptor that injects the bearer token from the
      existing `IdTokenCache`.
    - **Navigation:** introduce a minimal `NavHost` in the app module with
      routes for the dashboard, the Goals list, and the Goals roadmap
      detail, rather than leaving Goals as a static fixture. Goals is added
      to the foldable rail nav and reachable from phone "More". This is the
      least-invasive way to make Goals actually navigable; pre-existing
      dashboard screens remain their current static composables behind the
      `start` route.
    - If wiring a full NavHost proves too invasive in one pass, the fallback
      is an `Activity`/intent launch for the Goals screens. The build notes
      record which path was taken.

16. **`core-chat` Android module** holds the reusable chat composables
    (message list, user/assistant bubbles, composer, SSE client, and the
    `GoalProposalCard` composable), parameterized by thread scope, per spec.
    `feature-goals` depends on it.

17. **Markdown rendering:** add the `compose-markdown` (Jetpack) library for
    assistant message rendering. SSE client built on the existing OkHttp
    dependency (manual chunked `BufferedReader` reader exposed as a
    `Flow<String>`), no third-party SSE lib.

18. **Android domain models** mirror the backend DTOs as Kotlin data classes
    with Moshi annotations, living in `core-domain` (or `feature-goals` if
    `core-domain` proves to be UI-only). Repository + ViewModel per the
    existing feature-module convention.

19. **Wear OS is out of scope** for Goals in this pass (spec only specifies
    phone + foldable). No `:wear` changes.

## Testing & verification

20. Backend: JUnit slice/unit tests for the chat controller, proposal
    validation, and commit flow, plus the existing foundation tests must
    stay green (`./gradlew build test`).
21. Web: TypeScript must compile (`pnpm build` / `tsc --noEmit`) and lint
    clean. No e2e harness is added (project has none for other modules).
22. Android: the project must assemble (`./gradlew :app:assembleDebug`) and
    unit tests pass. Compose UI is verified by build + preview, not
    instrumentation (no device in CI).
23. **Web browser testing**, if any is needed, uses the Claude Chrome
    extension — never Playwright/Puppeteer (per machine policy).
