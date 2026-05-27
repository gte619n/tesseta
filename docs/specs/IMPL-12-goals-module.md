# IMPL-12: Goals Module

## Goal of this spec

Add a Goals section to Tesseta: a structured inventory of the user's
health objectives, organized as **Goal → Phase → Step**. Goals are the
planning layer that drives downstream behavior; Steps reference live
metrics from the other modules so progress updates automatically.

Implemented on both web (Next.js) and Android (Kotlin/Compose), sharing
the Spring backend. Assumes all other modules (Body, Blood, Workouts,
Nutrition, Meds) and their Firestore collections already exist.

## Decisions locked

| Decision | Choice |
|---|---|
| Gemini proposal lands as | Inline editable card in chat; user reviews and tweaks before saving |
| Step behavior | Simple checklist, done / not done |
| Step completion types | Three: threshold, sustained, count (plus plain manual) |
| Phase completion | All Steps checked |
| Phase ordering | Strict sequence — exactly one phase active at a time |
| Overdue handling | Flag and dim past-due Phase/Goal as "behind schedule", never block |
| Data integration | Full — Steps resolve against live module data |
| Active Goals expected | 4–8 — landing page is a list, not a gallery |
| Web chat library | assistant-ui (`@assistant-ui/react`) |
| Android chat | Thin custom Compose chat, no third-party chat SDK |
| Chat architecture | One shared chat engine; goals chat is the only thread scope in v1 |

---

## Concepts

**Goal** — the large overarching objective. Has a title, description,
domain tag, target date, and an ordered list of Phases. Example: "Lower
cardiovascular risk markers into optimal range."

**Phase** — a chapter of the roadmap. Has a title, target start and end
dates, an order index, and an ordered list of Steps. Phases run strictly
in sequence: phase N+1 cannot begin until phase N is complete. Exactly
one phase is "active" at a time. Example: "Phase 2 — Establish Zone 2
cardio base."

**Step** — an intermediate task inside a Phase. Done or not done. A Step
is one of four kinds:

- `manual` — checked by hand. No metric.
- `threshold` — done when a metric crosses a target (e.g. `blood.ldl < 100`).
- `sustained` — done when a metric holds a condition for a window
  (e.g. `vitals.restingHr < 55` for 30 consecutive days).
- `count` — done when a tallied metric reaches a target
  (e.g. 40 workouts logged since the Step was created).

Metric-tracked Steps auto-check when their condition is met; the user
can still manually override.

---

## Data model

### Firestore collections

Collections are multi-tenant, scoped under each user — same shape as
every other module (`users/{userId}/bloodReadings/{...}`, etc.). The
app is effectively single-user today, but the auth model
([ADR-0002](decisions/ADR-0002-google-id-tokens-as-auth.md)) is
per-Google-account and `CurrentUserProvider` is threaded through every
controller, so Goals follows suit.

```
users/{userId}/goals/{goalId}
users/{userId}/goals/{goalId}/phases/{phaseId}
users/{userId}/goals/{goalId}/phases/{phaseId}/steps/{stepId}
users/{userId}/goalChatThreads/{threadId}
users/{userId}/goalChatThreads/{threadId}/messages/{messageId}
```

Phases and Steps are subcollections so a Goal can be loaded shallow
(card view) or deep (roadmap view) on demand.

### Goal document

```
goalId: string
title: string
description: string
domain: enum  CARDIOVASCULAR | BODY_COMPOSITION | STRENGTH |
               METABOLIC | SLEEP | LONGEVITY | OTHER
status: enum  ACTIVE | COMPLETED | ARCHIVED
startDate: date
targetDate: date
createdAt: timestamp
updatedAt: timestamp
completedAt: timestamp | null
phaseOrder: string[]        ordered phaseIds, source of truth for sequence
source: enum  MANUAL | AI_GENERATED | AI_ASSISTED
```

### Phase document

```
phaseId: string
goalId: string
title: string
description: string
orderIndex: int             0-based position in the sequence
status: enum  LOCKED | ACTIVE | COMPLETED
targetStartDate: date
targetEndDate: date
completedAt: timestamp | null
stepOrder: string[]         ordered stepIds
```

`status` is derived and persisted: phase 0 starts ACTIVE, the rest
LOCKED. When a phase's Steps are all done it flips to COMPLETED and the
next phase flips LOCKED → ACTIVE. Only one ACTIVE phase per Goal, always.

### Step document

```
stepId: string
phaseId: string
goalId: string
title: string
orderIndex: int
kind: enum  MANUAL | THRESHOLD | SUSTAINED | COUNT
done: boolean
doneAt: timestamp | null
manualOverride: boolean      true if the user hand-set `done`,
                             suppresses auto-resolution

# present only when kind != MANUAL — the metric binding
metric: {
  metricKey: string          e.g. "blood.ldl", "body.weight",
                             "vitals.restingHr", "workouts.count"
  comparator: enum  LT | LTE | GT | GTE | EQ
  targetValue: number
  # SUSTAINED only:
  windowDays: int | null
  # COUNT only:
  countFrom: timestamp | null   tally start, usually Step creation
}
```

### Metric key registry

A fixed enum of metric keys the resolver understands. This is the
contract between Goals and the other modules. Initial set:

```
body.weight              latest weight
body.bodyFatPct          latest body fat %
body.leanMass            latest lean mass
blood.ldl                most recent panel LDL
blood.apoB               most recent panel ApoB
blood.hba1c              most recent panel HbA1c
blood.hsCRP              most recent panel hs-CRP
vitals.restingHr         daily resting HR
vitals.hrv               daily HRV
vitals.sleepScore        nightly sleep score
workouts.count           count of workouts logged
workouts.weeklyVolume    7-day rolling tonnage
nutrition.proteinAvg7d   7-day average protein
meds.adherence30d        30-day adherence %
```

Adding a metric is a one-line registry addition plus a resolver branch.
Gemini is given this list so it can only propose Steps that bind to real
metrics.

### Metric data sources

Each key resolves through a record + repository in `core`, following the
existing `DailyMetric` / `DailyMetricRepository` shape (Java records, no
Lombok, repository interface in `core`, Firestore impl in `persistence`).

| Metric key             | Backing source                                            | Status   |
|---|---|---|
| body.weight            | `core/bodycomposition` latest record                      | existing |
| body.bodyFatPct        | `core/bodycomposition` latest record                      | existing |
| body.leanMass          | `core/bodycomposition` latest record                      | existing |
| blood.ldl              | `core/blood` most recent panel                            | existing |
| blood.apoB             | `core/blood` most recent panel                            | existing |
| blood.hba1c            | `core/blood` most recent panel                            | existing |
| blood.hsCRP            | `core/blood` most recent panel                            | existing |
| vitals.restingHr       | `DailyMetric.restingHeartRate` (latest)                   | existing |
| vitals.hrv             | `DailyMetric.hrvMs` — new field on the existing record    | stub     |
| vitals.sleepScore      | `DailyMetric.sleepScore` — new field on the existing record | stub   |
| workouts.count         | count of `core/workout` records since timestamp           | existing |
| workouts.weeklyVolume  | new `WeeklyWorkoutAggregate` record + repo                | stub     |
| nutrition.proteinAvg7d | new `NutritionDailyLog` record + repo, 7-day average      | stub     |
| meds.adherence30d      | `core/medication` `AdherenceRepository` 30-day window     | existing |

Stub records this spec introduces, mirroring `DailyMetric`:

```java
public record NutritionDailyLog(
    String userId, LocalDate date,
    Double proteinGrams, Double carbsGrams, Double fatGrams,
    Instant createdAt, Instant updatedAt
) {}

public interface NutritionDailyLogRepository {
    Optional<NutritionDailyLog> findByDate(String userId, LocalDate date);
    List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to);
    void save(NutritionDailyLog log);
}

public record WeeklyWorkoutAggregate(
    String userId, LocalDate weekStart,
    Double totalTonnage, Integer sessionCount,
    Instant createdAt, Instant updatedAt
) {}

public interface WeeklyWorkoutAggregateRepository {
    Optional<WeeklyWorkoutAggregate> findByWeekStart(String userId, LocalDate weekStart);
    List<WeeklyWorkoutAggregate> findByDateRange(String userId, LocalDate from, LocalDate to);
    void save(WeeklyWorkoutAggregate agg);
}
```

`DailyMetric` gains two nullable fields (`hrvMs`, `sleepScore`) — a
non-breaking Firestore add; older documents simply omit them.

Stubs ship with the record, the repository interface, and a Firestore
implementation, but no producer in IMPL-12. Until the ingestion spec
for each lands, Steps bound to stub-backed metrics never auto-check
and behave like `MANUAL` (user can still hand-check). The resolver
treats a missing reading as "metric unavailable" and leaves `done`
unchanged.

---

## Backend — Spring

### Module placement

New `goals` package in the existing backend multi-module project.
Controllers in `api`, services and domain in `core`, Firestore
repositories in `persistence`, the Gemini client in `integrations`.

### Metric resolver

The single seam between Goals and the rest of the app.

```
interface MetricResolver {
    // current scalar value of a metric
    MetricValue resolve(String metricKey);
    // for SUSTAINED: has the condition held for the whole window?
    boolean sustainedHolds(String metricKey, Comparator cmp,
                           double target, int windowDays);
    // for COUNT: tally since a timestamp
    long countSince(String metricKey, Instant from);
}
```

One implementation, `FirestoreMetricResolver`, with a branch per metric
key reading the relevant module's collection. This is the only class
that knows how each module stores its data; Goals code never touches
another module's collection directly.

### Step evaluation service

`StepEvaluationService` runs the auto-check logic.

- For a `THRESHOLD` Step: resolve the metric, apply the comparator,
  set `done` if satisfied.
- For `SUSTAINED`: call `sustainedHolds`.
- For `COUNT`: `countSince(countFrom)` compared to `targetValue`.
- `MANUAL` Steps are never auto-evaluated.
- A Step with `manualOverride = true` is skipped — the user's hand wins.

When a Step flips to done, the service checks whether its Phase is now
fully done; if so it completes the Phase and activates the next one; if
that was the last Phase it completes the Goal.

### When evaluation runs

1. **On write to a source module.** Each writer publishes a
   `MetricChangedEvent` via Spring `ApplicationEventPublisher` after a
   successful write. `StepEvaluationService` listens with `@EventListener`
   and re-evaluates only the Steps bound to that metric key. In-process
   only — no Pub/Sub topic, no infra. Events lost on restart are fine
   because the on-load and daily-job paths catch up.

   ```java
   public record MetricChangedEvent(String metricKey, Instant occurredAt) {}
   ```

   Services that need to publish (one publish per relevant write):
   - `BloodTestService` → `blood.ldl`, `blood.apoB`, `blood.hba1c`, `blood.hsCRP`
   - `BodyCompositionService` → `body.weight`, `body.bodyFatPct`, `body.leanMass`
   - `DailyMetricService` / Google Health webhook handler → `vitals.restingHr`, `vitals.hrv`, `vitals.sleepScore`
   - `WorkoutService` → `workouts.count`, `workouts.weeklyVolume`
   - `AdherenceService` → `meds.adherence30d`
   - `NutritionService` (new, alongside the stub) → `nutrition.proteinAvg7d`

2. **Daily Cloud Run Job.** A separate Cloud Run Job, fired by Cloud
   Scheduler at 03:00 America/New_York, calls
   `StepEvaluationService.reevaluateAllSustained()`. SUSTAINED truth
   depends on elapsed time, not writes, so it needs a time-driven
   trigger. Deployed from the same image as the main service with a
   different entrypoint.

3. **On demand.** `GET /api/goals/{id}` runs a fresh evaluation for the
   Goal's Steps before returning, so the UI always sees current truth
   on load.

### Regression — auto-eval never un-checks

Auto-evaluation is a one-way ratchet: it can flip a Step from undone to
done, never the reverse. If a metric later regresses across a done
Step's threshold, the Step stays done and the resolver surfaces a
transient `metricRegressed = true` on the Step's metric readout. The UI
renders a small "metric regressed" badge next to the auto tag so the
user can see truth diverging without the Step silently unwinding.

The user can manually un-check a Step at any time. Any manual
interaction — checking early or un-checking after auto — sets
`manualOverride = true`, freezing `done` against further auto-eval.
The Step row exposes a "Reset to auto" affordance that clears the
override and re-runs evaluation.

Phase and Goal completion are sticky: once `completedAt` is set, they
stay completed. Phase progression (LOCKED → ACTIVE → COMPLETED) is
one-way.

### REST endpoints

```
GET    /api/goals                       list (shallow, no phases)
POST   /api/goals                       create
GET    /api/goals/{id}                  deep (phases + steps)
PATCH  /api/goals/{id}                  update Goal fields
DELETE /api/goals/{id}                  archive (soft)
POST   /api/goals/{id}/reevaluate       force re-evaluation

POST   /api/goals/{id}/phases           add Phase
PATCH  /api/goals/{id}/phases/{pid}     update Phase
DELETE /api/goals/{id}/phases/{pid}     remove Phase
PUT    /api/goals/{id}/phases/order     reorder (array of phaseIds)

POST   .../phases/{pid}/steps           add Step
PATCH  .../phases/{pid}/steps/{sid}     update Step (incl. manual check)
DELETE .../phases/{pid}/steps/{sid}     remove Step
PUT    .../phases/{pid}/steps/order     reorder

POST   /api/goals/chat                  send a chat message (SSE stream)
POST   /api/goals/chat/{threadId}/commit  persist an AI-proposed structure
GET    /api/goals/chat/threads          list goal chat threads
```

---

## Gemini integration

### Approach

Gemini designs Goal structures through **tool calling**, not free-text
parsing. The model is given a tool whose schema is the Goal/Phase/Step
structure, plus the metric key registry. It calls the tool with a
proposed structure; the backend validates it and streams it to the UI
as a structured object, which renders as an editable card.

### The tool

```
tool: propose_goal_structure
description: Propose a complete Goal with Phases and Steps for the
             user to review and edit.
parameters: {
  title, description, domain, targetDate,
  phases: [
    {
      title, description, targetStartDate, targetEndDate,
      steps: [
        {
          title,
          kind: MANUAL | THRESHOLD | SUSTAINED | COUNT,
          metric: { metricKey, comparator, targetValue,
                    windowDays?, countFrom? }   # omit for MANUAL
        }
      ]
    }
  ]
}
```

### System prompt essentials

The Gemini system prompt for the Goals thread must:

- State the Goal/Phase/Step model and the strict-sequence rule for
  Phases.
- Include the metric key registry verbatim. Gemini may only emit
  metric-bound Steps using a key from the registry; anything else must
  be a `MANUAL` Step.
- Instruct that Phases are a sensible roadmap — earlier Phases build
  foundation for later ones, since they run in strict order.
- Tell it to prefer 3–6 Phases and 2–6 Steps per Phase; warn against
  over-granular plans.
- Tell it to call `propose_goal_structure` once it has enough context,
  rather than describing the plan in prose.
- It must not invent metrics, dates in the past, or comparators that
  do not match the metric (e.g. a count metric with `LT`).

### Validation before the card renders

Backend validates every proposed structure:

- Every `metricKey` exists in the registry.
- Comparator is legal for the metric type.
- `windowDays` present iff `kind == SUSTAINED`; `countFrom` defaulted
  to "now" iff `kind == COUNT`.
- Phase dates are ordered and non-overlapping (strict sequence).
- `targetDate` is in the future.

Invalid fields are flagged inline on the card, not silently dropped, so
the user sees what Gemini got wrong.

### Commit flow

1. User chats; Gemini calls `propose_goal_structure`.
2. Backend validates, streams the structure to the chat UI.
3. UI renders it as an **editable roadmap card** inline in the thread.
4. User edits titles, dates, Steps, metric targets directly on the card.
5. User taps "Save goal". UI posts the final structure to
   `/api/goals/chat/{threadId}/commit`.
6. Backend writes the Goal/Phase/Step documents, runs an initial
   evaluation, returns the new `goalId`.
7. The card collapses to a "Goal created" confirmation linking to the
   roadmap.

Manual creation bypasses chat entirely — same editable structure, blank,
via the REST endpoints.

---

## Web UI — Next.js

### Navigation

New primary destination in the left nav, placed after Meds:

```
Goals    icon: ti-route   or ti-target
```

### Goals landing page (`/goals`)

Header: title "Goals", "New goal" primary button (opens the chat),
filter segment (`Active / Completed / Archived`).

Body: a vertical list of **Goal cards** (4–8 expected, so a list, not a
gallery). Each card:

- Domain tag pill, Goal title (`heading.md`), target date in caps mono
- A compact horizontal **phase spine**: one segment per Phase, colored
  by status — completed olive-filled, active olive-outlined, locked
  muted. The active segment shows a small label.
- Overall progress: "Phase 2 of 4 · 7 of 19 steps"
- A thin progress bar
- If past `targetDate` and not complete: a "Behind schedule" warn pill
  and the whole card dims slightly (reduced opacity on the spine)

Clicking a card opens the roadmap detail.

### Goal roadmap detail (`/goals/{id}`)

The centerpiece. A vertical **roadmap / timeline**:

- A vertical spine down the left, one node per Phase, in order.
- Completed phases: olive node, olive spine segment above.
- Active phase: larger olive-outlined node, pulsing-free (static),
  spine segment above it olive, below it muted.
- Locked phases: muted node, muted spine.
- Each Phase is a panel to the right of its node:
  - Phase title (`heading.md`), target date range in caps mono
  - Phase status pill
  - Its Steps as a checklist. Each Step row: checkbox, title, and for
    metric-bound Steps a live readout — current value vs target
    (e.g. "LDL 112 → target < 100") with a mini `RangeIndicator`.
  - Metric-bound Steps that are auto-done show a small "auto" tag so
    the user knows the check came from data, not a hand-tap.
  - Manual Steps are a plain checkbox.
- Locked phases render their Steps collapsed/dimmed.

Top of the page: Goal title, description, target date, an "Edit in
chat" button that opens the Goals chat pre-loaded with this Goal's
context, and an overflow menu (archive, delete, duplicate).

Past-due Phases get the "Behind schedule" treatment: warn pill on the
Phase panel, node tinted warn instead of muted.

### Goals chat

Built with **assistant-ui** (`@assistant-ui/react`). Reasons: composable
primitives that take the Tesseta oatmeal/olive tokens directly, native
streaming, and generative-UI support so a tool call renders as a custom
React component.

- Thread UI from assistant-ui primitives (`Thread`, `Message`,
  `Composer`), styled with the Tesseta tokens — not the default theme.
- The `propose_goal_structure` tool result renders as a custom
  **`<GoalProposalCard>`** React component inside the assistant message.
  This is assistant-ui's generative-UI / tool-UI feature.
- `<GoalProposalCard>` is fully editable in place: editable Phase and
  Step titles, date pickers, metric target inputs, add/remove Step
  buttons, drag-reorder. It is essentially the roadmap editor embedded
  in a chat bubble.
- Card actions: "Save goal" (commit) and "Discard".
- Suggested starter prompts on the empty state, e.g. "Help me build a
  plan to get my ApoB into optimal range", "Plan a 12-week strength
  base", "I want to improve my sleep score — design a roadmap".
- Backend connection: assistant-ui talks to `/api/goals/chat` over SSE.
- Threads: assistant-ui's `ThreadList`, scoped to goal chat threads.
  The thread scope is a parameter on the engine so later modules can
  reuse the same plumbing; in v1 there is only one scope.

### Manual editor

The same `<GoalProposalCard>` component, opened blank from a "Create
manually" option on the landing page. No chat involved; "Save goal"
posts straight to `POST /api/goals`.

---

## Android UI — Kotlin / Compose

### Navigation

Goals is a phone destination. Phone bottom nav is four items (Today,
Log, Trends, More); Goals lives under **More**, or replaces a slot if
the user pins it. On foldable unfolded, Goals is a top-level icon in the
rail like the web nav.

Rationale: phone is the capture-and-glance surface. Deep roadmap
planning is a web/foldable activity. The phone still gets full Goals
viewing and Step checking, just not pride-of-place in the bottom nav.

### Screens

**Goals list** — `LazyColumn` of Goal cards mirroring the web landing
page: domain pill, title, compact phase spine (a `Row` of segments),
progress text, behind-schedule treatment. "New goal" FAB-style button
in the top app bar opens the chat.

**Goal roadmap detail** — vertical timeline in a `LazyColumn`. Each item
is a Phase: a `Canvas`- or `Box`-drawn spine node on the left, Phase
panel on the right. Steps are checklist rows; metric-bound Steps show
the live "current → target" readout and the "auto" tag. Compose handles
the locked/active/completed states with the standard color tokens.

On a foldable unfolded, this becomes a two-pane layout: Goal list left,
selected roadmap right.

### Goals chat — custom Compose

No third-party chat SDK (decision 5b). The surface is modest:

- `LazyColumn` of message items, `reverseLayout = true`, auto-scroll.
- Two message composables: `UserMessage` (right-aligned bubble) and
  `AssistantMessage` (left-aligned, markdown-rendered).
- Markdown via the `compose-markdown` library (or Markwon bridged into
  Compose) — assistant text only, no editor needed.
- Streaming: the backend SSE stream appends tokens to the last
  assistant message's state; Compose recomposition handles the live
  update. A simple typing indicator while the stream is open.
- Composer: a `TextField` plus send button, pinned to the bottom,
  insets-aware.
- The `propose_goal_structure` tool result renders as a Compose
  **`GoalProposalCard`** composable inline in the assistant message —
  the Android twin of the web component. Editable: phase/step titles,
  Material3 date pickers, metric target fields, add/remove/reorder.
- Card actions "Save goal" and "Discard" call the same
  `/api/goals/chat/{threadId}/commit` endpoint.

This is one screen, two message composables, an SSE client, and one
editable card composable. Genuinely modest; a chat SDK would be more
integration surface than it saves.

### Shared chat module

Chat composables live in a `core-chat` Android module, parameterized by
thread scope and the tool set available, so later modules can reuse
them without rewriting the surface. For IMPL-12 there is one scope:
goal chat.

---

## Build sequence

1. Data model + Firestore collections + Goal/Phase/Step CRUD endpoints.
2. Metric data layer: new stub records/repos (`NutritionDailyLog`,
   `WeeklyWorkoutAggregate`) and `DailyMetric` field additions
   (`hrvMs`, `sleepScore`).
3. Metric key registry + `FirestoreMetricResolver` + `StepEvaluationService`.
4. Publish `MetricChangedEvent` from each writer service listed above.
5. Daily Cloud Run Job for SUSTAINED re-evaluation + Cloud Scheduler trigger.
6. Web: Goals landing + roadmap detail, manual editor, REST wired.
7. Web: Gemini tool calling + assistant-ui chat + `<GoalProposalCard>`.
8. Android: `core-chat` module, Goals list + roadmap detail.
9. Android: custom Compose chat + `GoalProposalCard` composable.
10. End-to-end: propose via chat, edit, commit, watch a Step auto-check
    when its metric crosses target.

## Out of scope for IMPL-12

- Parallel/overlapping Phases (strict sequence only for v1).
- Goal templates / a library of pre-built roadmaps.
- Sharing or exporting Goals.
- Notifications when a Step auto-completes (a later notifications spec).
- Step-level dependencies within a Phase (Steps are a flat checklist).
- Gemini revising an existing live Goal in place (v1: chat proposes new
  structures; edits to live Goals are manual on the roadmap).

## Acceptance criteria

- A Goal with Phases and Steps can be created manually on web and
  Android, and the two render the same roadmap.
- In the Goals chat, a request like "plan to get my LDL under 100"
  produces an editable `GoalProposalCard`; editing and saving persists
  a real Goal.
- A `THRESHOLD` Step bound to `blood.ldl` auto-checks when a new blood
  panel brings LDL under target, with no manual action.
- A `SUSTAINED` Step flips to done only after the daily job confirms
  the window held.
- Completing all Steps in the active Phase completes that Phase and
  activates the next; completing the last Phase completes the Goal.
- A Goal past its target date with incomplete Phases shows the
  "behind schedule" treatment on both platforms.
- A done THRESHOLD Step whose metric later regresses across the target
  stays done and shows a "metric regressed" badge; nothing un-completes
  automatically.
- Manually un-checking an auto-completed Step is durable: subsequent
  metric writes do not re-flip it until the user taps "Reset to auto".
- An invalid Gemini proposal (unknown metric key, illegal comparator,
  overlapping Phase dates) renders with the offending fields flagged
  inline on the `<GoalProposalCard>`, not silently dropped.
