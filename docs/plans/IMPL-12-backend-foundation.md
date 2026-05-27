# IMPL-12 Goals — Backend Foundation Plan

**Scope:** build-sequence steps 1–5 from
[`docs/specs/IMPL-12-goals-module.md`](../specs/IMPL-12-goals-module.md).
Pure backend. No Gemini, no web, no Android. One PR.

**Branch:** `feature/goals` (already checked out).

**Out of scope here:** Gemini tool calling / chat endpoints, all UI,
real ingestion producers for the stub metrics (nutrition,
weeklyVolume) — those land in later plans.

---

## Phase 0 — Discovery findings (locked in)

Verified against the codebase by parallel explorer agents on 2026-05-27.
Cite-able file paths in parentheses. Treat these as ground truth for the
rest of the plan.

### Codebase conventions to mirror verbatim

- **Records in `core`:** Java records, nullable wrapper types (`Integer`,
  `Double`, `String`), `Instant` for timestamps, no Lombok. Pattern
  source: `backend/core/.../bloodtest/BloodTestReport.java:14`.
- **Repo interface in `core`:** methods `findById`, `findByUser`, `save`,
  `delete`; return `Optional<T>` or `List<T>`; **every method takes
  `userId`**. Source: `backend/core/.../blood/BloodReadingRepository.java`.
- **Firestore impl in `persistence`:** `@Repository` +
  `@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)`,
  constructor injection of `Firestore`, manual `Map<String,Object>`
  serialization via a `toBody(...)` helper, deserialization via a
  `toEntity(snapshot)` helper, an `await(ApiFuture)` wrapper.
  Source: `backend/persistence/.../blood/BloodReadingRepository.java:27`.
- **Controllers in `api`:** `@RestController`,
  `@RequestMapping("/api/me/...")`, constructor inject
  `CurrentUserProvider` + the relevant repo or service, nested
  `*Request` record for POST/PATCH bodies, throw
  `IllegalArgumentException` for validation (Spring maps to 400),
  return `ResponseEntity<T>` with explicit status codes for create/204.
  Source: `backend/api/.../blood/BloodController.java:20`.
- **Services in `core`:** `@Service`, constructor injection only,
  depend on repo interfaces (also in `core`) — never on other modules'
  repos directly. Source: `backend/core/.../user/UserService.java`.
- **IDs:** `UUID.randomUUID().toString()` generated in the controller
  before calling `save`.
- **Timestamps:** `null` in the record on create; the Firestore impl
  substitutes `FieldValue.serverTimestamp()`.

### Discrepancy with the spec

- **Spec line 64** says "single-user app, so collections are flat and
  unscoped by user." The codebase is **multi-tenant** end-to-end:
  Firestore paths are `users/{userId}/...`, `CurrentUserProvider` is
  threaded through every controller, ADR-0002 binds auth to Google ID
  tokens with a per-user id. **Resolution:** keep multi-tenant for
  Goals. Collections become
  `users/{userId}/goals/{goalId}/phases/{phaseId}/steps/{stepId}`. The
  spec's intent (one Google account → one user's data) is preserved
  structurally without forking the auth model. Update the spec
  alongside this plan's first commit.

### Writer-service surface (where `MetricChangedEvent` publishes)

The spec lists six "services" to hook. Reality on the ground:

| Metric source | Writer today | Plan |
|---|---|---|
| `blood.ldl / apoB / hba1c / hsCRP` (BloodReading) | `BloodController.create` (`api`), repo call inline | Hook via helper at controller line 72 |
| `blood.*` (BloodTest panels) | `BloodTestService.upload` line 90, `updateField` line 172 (`app/bloodtest`) | Hook via helper at both lines |
| `body.weight / bodyFatPct / leanMass` | `WebhookHandlerService` (Google Health webhook, `integrations`) lines 67, 73 | Hook via helper at save + delete |
| `vitals.restingHr / hrv / sleepScore` | **No writer found.** `DailyMetricRepository` exists but no `DailyMetricService` and no controller writes. Likely written by the same Google Health webhook handler — but field-level publication is per-row, not per-metric-key. | Confirm writer in Phase 4. If none today, skip the publish hook and rely on on-demand `GET /api/goals/{id}` evaluation; the daily job catches SUSTAINED. |
| `workouts.count / weeklyVolume` | **No writer found.** Repo exists, no service or controller. | Same as above — defer hook until a writer exists. |
| `meds.adherence30d` | `AdherenceController.logDose` line 101, `undoDose` lines 134, 144 (`api`) | Hook via helper at all three lines |
| `nutrition.proteinAvg7d` | **Does not exist.** No package. | Ship stub record + repo, no writer to hook. |

Implication: **Phase 4 is smaller than the spec suggests** — only four
real write paths to touch (BloodController, BloodTestService,
WebhookHandlerService body composition, AdherenceController).
DailyMetric and Workout publish hooks are deferred with a `TODO(IMPL-12)`
comment + a follow-up issue.

### Infra

- Single Spring Boot app: `HealthFitnessApplication`
  (`backend/app/.../HealthFitnessApplication.java:8`). Image built
  via `backend/cloudbuild.yaml` (3 steps), deployed to Cloud Run
  service `health-fitness-backend` in `us-central1` as
  `health-fitness-runtime@$PROJECT_ID.iam.gserviceaccount.com`.
- **No Cloud Run Jobs in the repo today.** No `@Scheduled`, no
  `cron`, no Cloud Scheduler config.
- SA already has `roles/datastore.user` — no extra IAM needed for the
  daily Job; it writes Firestore from the same SA.
- Spec-implied path (reuse image, `SPRING_PROFILES_ACTIVE=job`,
  `CommandLineRunner`) is the lowest-friction option and matches the
  Dockerfile's generic entrypoint (`java -jar /app/app.jar`).

### Event-publishing pattern is greenfield

- `grep -r ApplicationEventPublisher @EventListener backend/` returns
  zero hits. This PR introduces Spring events to the codebase. Keep it
  small and conventional.

---

## Phase 1 — Goals data model + CRUD

**Subagent:** general-purpose, model = `opus` (largest chunk of code).
**Estimated size:** ~1100–1400 lines incl. tests.
**Commit:** `feat(backend): goals/phases/steps data model + Firestore CRUD`.

### What to create — file by file

**`core` module — Java records and repo interfaces:**

```
backend/core/src/main/java/com/gte619n/healthfitness/core/goals/
  Goal.java
  GoalStatus.java                 enum ACTIVE | COMPLETED | ARCHIVED
  GoalDomain.java                 enum CARDIOVASCULAR | BODY_COMPOSITION | STRENGTH | METABOLIC | SLEEP | LONGEVITY | OTHER
  GoalSource.java                 enum MANUAL | AI_GENERATED | AI_ASSISTED
  Phase.java
  PhaseStatus.java                enum LOCKED | ACTIVE | COMPLETED
  Step.java
  StepKind.java                   enum MANUAL | THRESHOLD | SUSTAINED | COUNT
  Comparator.java                 enum LT | LTE | GT | GTE | EQ
  StepMetricBinding.java          nested record on Step
  GoalRepository.java             interface
  PhaseRepository.java            interface
  StepRepository.java             interface
```

Exact record shapes follow the spec (lines 76–138), with one change:
**add `userId` as the first field of `Goal`** (multi-tenant carry-over).
`Phase` and `Step` inherit scope via their parent IDs, so `userId` lives
on `Goal` only and is plumbed through repository method signatures
(`findById(String userId, String goalId)` etc).

Repo interfaces, mirroring `BloodReadingRepository`:

```java
public interface GoalRepository {
    Optional<Goal> findById(String userId, String goalId);
    List<Goal> findByUser(String userId, GoalStatus status);  // null = all
    void save(Goal goal);
    void delete(String userId, String goalId);  // soft via status=ARCHIVED in service
}

public interface PhaseRepository {
    Optional<Phase> findById(String userId, String goalId, String phaseId);
    List<Phase> findByGoal(String userId, String goalId);
    void save(Phase phase);
    void delete(String userId, String goalId, String phaseId);
}

public interface StepRepository {
    Optional<Step> findById(String userId, String goalId, String phaseId, String stepId);
    List<Step> findByPhase(String userId, String goalId, String phaseId);
    List<Step> findByGoal(String userId, String goalId);          // for metric reverse-lookup
    List<Step> findByMetricKey(String userId, String metricKey);  // for event-driven re-eval
    List<Step> findAllSustained(String userId);                   // for daily Job
    void save(Step step);
    void delete(String userId, String goalId, String phaseId, String stepId);
}
```

**`core/goals/GoalService.java`** — orchestrates Goal/Phase/Step
lifecycle. Handles `phaseOrder` / `stepOrder` array maintenance, soft
delete (set `status=ARCHIVED`), and **all writes to `done`** (because
Phase auto-progression depends on it). Step evaluation lives in a
separate `StepEvaluationService` (Phase 3); `GoalService` calls it.

**`persistence` module — Firestore impls:**

```
backend/persistence/src/main/java/com/gte619n/healthfitness/persistence/goals/
  FirestoreGoalRepository.java
  FirestorePhaseRepository.java
  FirestoreStepRepository.java
```

Collection paths:
- `users/{userId}/goals/{goalId}`
- `users/{userId}/goals/{goalId}/phases/{phaseId}`
- `users/{userId}/goals/{goalId}/phases/{phaseId}/steps/{stepId}`

`findByMetricKey` and `findAllSustained` use Firestore collection-group
queries (`firestore.collectionGroup("steps").whereEqualTo(...)`). This
requires a single-field exemption + composite index — call out in the
PR description for the Firestore admin step.

**In-memory test impls** in `app/src/test/java/.../persistence/`:

```
backend/app/src/test/java/com/gte619n/healthfitness/persistence/goals/
  InMemoryGoalRepository.java
  InMemoryPhaseRepository.java
  InMemoryStepRepository.java
```

Mirror the existing `InMemory*` style used by `TestPersistenceConfig`.

**`api` module — REST controllers:**

```
backend/api/src/main/java/com/gte619n/healthfitness/api/goals/
  GoalController.java
  PhaseController.java
  StepController.java
  dto/
    GoalResponse.java            shallow (no phases)
    GoalDeepResponse.java        with phases + steps
    PhaseResponse.java
    StepResponse.java
    CreateGoalRequest.java
    UpdateGoalRequest.java       (PATCH)
    CreatePhaseRequest.java
    UpdatePhaseRequest.java
    CreateStepRequest.java
    UpdateStepRequest.java       (incl. manual check via `done`)
    ReorderRequest.java          shared { List<String> ids }
    StepMetricBindingDto.java
```

**Endpoints** (`/api/me/goals/...` — multi-tenant base path, like
`/api/me/blood`):

```
GET    /api/me/goals?status=ACTIVE              GoalController.list
POST   /api/me/goals                            GoalController.create
GET    /api/me/goals/{id}                       GoalController.getDeep
PATCH  /api/me/goals/{id}                       GoalController.update
DELETE /api/me/goals/{id}                       GoalController.archive
POST   /api/me/goals/{id}/reevaluate            GoalController.reevaluate  (wired in Phase 3)

POST   /api/me/goals/{id}/phases                PhaseController.create
PATCH  /api/me/goals/{id}/phases/{pid}          PhaseController.update
DELETE /api/me/goals/{id}/phases/{pid}          PhaseController.delete
PUT    /api/me/goals/{id}/phases/order          PhaseController.reorder

POST   /api/me/goals/{id}/phases/{pid}/steps    StepController.create
PATCH  /api/me/goals/{id}/phases/{pid}/steps/{sid}   StepController.update
DELETE /api/me/goals/{id}/phases/{pid}/steps/{sid}   StepController.delete
PUT    /api/me/goals/{id}/phases/{pid}/steps/order   StepController.reorder
```

`POST /api/me/goals/chat*` endpoints are **deferred** — they belong to
the Gemini PR.

### Documentation references

- Record shape: spec L76–138
- Endpoint table: spec L227–245
- State machine notes: spec L109–111, regression section
- Controller pattern: copy from `backend/api/.../blood/BloodController.java`
- Firestore repo pattern: copy from `backend/persistence/.../blood/BloodReadingRepository.java`

### Verification

- `./gradlew :app:test --tests "*GoalController*"`
- `./gradlew :app:test --tests "*PhaseController*"`
- `./gradlew :app:test --tests "*StepController*"`
- Slice tests assert: create → get-deep round trip; PATCH updates a
  single field; DELETE soft-archives; reorder permutes the array;
  invalid metric binding payload returns 400.
- `grep -r "JpaRepository\|@Entity" backend/core backend/persistence`
  must return zero (no JPA leakage).
- `grep -r "Lombok\|@Data\|@Builder" backend/` must return zero.

### Anti-patterns

- Don't reintroduce Lombok or builders.
- Don't put `@RestController` in `core` — controllers stay in `api`.
- Don't query Firestore from controllers — go through repositories.
- Don't generate document IDs server-side from Firestore; use UUID in
  the controller (matches existing pattern).
- Don't use `findAll()` without `userId` scoping — multi-tenant.

---

## Phase 2 — Metric data layer stubs + DailyMetric extension

**Subagent:** general-purpose, model = `sonnet` (mechanical, small).
**Estimated size:** ~250–350 lines incl. tests.
**Commit:** `feat(backend): metric stubs (nutrition, weekly workout aggregate) + DailyMetric hrv/sleepScore`.

### What to create

**Extend `DailyMetric`:**

```java
// backend/core/.../metric/DailyMetric.java
public record DailyMetric(
    String userId,
    LocalDate date,
    Integer steps,
    Integer restingHeartRate,
    Integer sleepMinutes,
    Integer hrvMs,           // NEW — nullable
    Integer sleepScore,      // NEW — nullable, 0–100
    Instant createdAt,
    Instant updatedAt
) {}
```

Update the Firestore impl's `toBody` and `toMetric` helpers
(`backend/persistence/.../metric/FirestoreDailyMetricRepository.java`)
to round-trip the new fields. Firestore documents missing the fields
deserialize as `null` (existing pattern uses `snapshot.getLong(...)`
which returns null for missing fields — confirm at the file).

**New `nutrition` package:**

```
backend/core/src/main/java/com/gte619n/healthfitness/core/nutrition/
  NutritionDailyLog.java
  NutritionDailyLogRepository.java
backend/persistence/src/main/java/com/gte619n/healthfitness/persistence/nutrition/
  FirestoreNutritionDailyLogRepository.java
backend/app/src/test/java/.../persistence/nutrition/
  InMemoryNutritionDailyLogRepository.java
```

Record + interface as defined in the spec (Metric data sources
section). Collection path:
`users/{userId}/nutritionDailyLogs/{date-iso}`.

**New `workout/aggregate` package** (or a sibling — recommend a flat
`workoutaggregate` package to avoid nesting under existing `workout`):

```
backend/core/src/main/java/com/gte619n/healthfitness/core/workoutaggregate/
  WeeklyWorkoutAggregate.java
  WeeklyWorkoutAggregateRepository.java
backend/persistence/src/main/java/.../workoutaggregate/
  FirestoreWeeklyWorkoutAggregateRepository.java
backend/app/src/test/java/.../persistence/workoutaggregate/
  InMemoryWeeklyWorkoutAggregateRepository.java
```

Collection path: `users/{userId}/weeklyWorkoutAggregates/{week-start-iso}`.

**No controllers, no services, no event publishers** — these are pure
data-layer stubs. Population comes from later ingestion specs.

### Verification

- `./gradlew :persistence:test :core:test` passes.
- `grep -r "NutritionDailyLog" backend/api` returns zero — no API
  surface for nutrition in this PR.
- `DailyMetric` round-trip test in
  `FirestoreDailyMetricRepositoryTest` exercises the new fields.

### Risk

Low. Pure additive change. Existing `DailyMetric` callers that
constructed the record positionally will fail to compile — search and
update.

`grep -rn "new DailyMetric(" backend/` — there's almost certainly only
one or two call sites (none found by Phase 0 — confirms there's no
DailyMetric writer in the repo yet).

---

## Phase 3 — Metric registry + resolver + step evaluation

**Subagent:** general-purpose, model = `opus` (the trickiest logic of
the PR; regression semantics matter).
**Estimated size:** ~700–950 lines incl. tests.
**Commit:** `feat(backend): metric resolver + step evaluation with notify-never-undo policy`.

### What to create

```
backend/core/src/main/java/com/gte619n/healthfitness/core/goals/eval/
  MetricKey.java                  enum of all 14 keys
  MetricValue.java                record(Optional<Double> value, Instant asOf)
  MetricResolver.java             interface
  FirestoreMetricResolver.java    @Service implementation
  StepEvaluationService.java      @Service
  EvaluationResult.java           record(boolean done, boolean metricRegressed)
```

**`MetricKey` enum** — exact strings match the registry:

```java
public enum MetricKey {
    BODY_WEIGHT("body.weight"),
    BODY_BODY_FAT_PCT("body.bodyFatPct"),
    BODY_LEAN_MASS("body.leanMass"),
    BLOOD_LDL("blood.ldl"),
    BLOOD_APOB("blood.apoB"),
    BLOOD_HBA1C("blood.hba1c"),
    BLOOD_HS_CRP("blood.hsCRP"),
    VITALS_RESTING_HR("vitals.restingHr"),
    VITALS_HRV("vitals.hrv"),
    VITALS_SLEEP_SCORE("vitals.sleepScore"),
    WORKOUTS_COUNT("workouts.count"),
    WORKOUTS_WEEKLY_VOLUME("workouts.weeklyVolume"),
    NUTRITION_PROTEIN_AVG_7D("nutrition.proteinAvg7d"),
    MEDS_ADHERENCE_30D("meds.adherence30d");
    /* ... key string, fromKey(String) helper ... */
}
```

**`MetricResolver` interface:**

```java
public interface MetricResolver {
    MetricValue resolve(String userId, MetricKey key);
    boolean sustainedHolds(String userId, MetricKey key, Comparator cmp, double target, int windowDays);
    long countSince(String userId, MetricKey key, Instant from);
}
```

**`FirestoreMetricResolver`** — one branch per `MetricKey`. For each
case, it reads from the relevant repo and computes the scalar. Inject
all needed repos: `BloodReadingRepository`,
`BloodTestReportRepository`, `BodyCompositionRepository`,
`DailyMetricRepository`, `WorkoutRepository`,
`WeeklyWorkoutAggregateRepository`, `NutritionDailyLogRepository`,
`AdherenceRepository`.

For stub-backed metrics (HRV, sleepScore, weeklyVolume, protein), the
branch returns `MetricValue.unavailable()` if the underlying record
doesn't exist yet — Step evaluation treats unavailable as "no change",
not "false".

**`StepEvaluationService`** — the regression policy lives here.

```java
@Service
public class StepEvaluationService {
    private final StepRepository steps;
    private final PhaseRepository phases;
    private final GoalRepository goals;
    private final MetricResolver resolver;
    /* constructor */

    /** Re-evaluate every Step bound to a given metric key for a user. */
    public void onMetricChanged(String userId, MetricKey key) { /* ... */ }

    /** Re-evaluate every Step in a Goal — called on GET /api/me/goals/{id}. */
    public void evaluateGoal(String userId, String goalId) { /* ... */ }

    /** Re-evaluate every SUSTAINED Step across all users — called by daily Job. */
    public void reevaluateAllSustained() { /* ... */ }

    /** Per-Step evaluation core — returns whether anything changed. */
    EvaluationResult evaluateStep(String userId, Step step) {
        if (step.kind() == StepKind.MANUAL) return EvaluationResult.noChange(step);
        if (step.manualOverride()) return EvaluationResult.noChange(step);
        if (step.done()) {
            // Notify, never auto-undo: only check for regression
            boolean regressed = !conditionHolds(userId, step);
            return new EvaluationResult(true, regressed);
        }
        // Was undone — check if now done
        boolean nowDone = conditionHolds(userId, step);
        return new EvaluationResult(nowDone, false);
    }
}
```

**Critical semantics (spec L222 onward):**

1. Auto-eval is a one-way ratchet — `done: false → true` allowed,
   `done: true → false` **never**.
2. `manualOverride = true` skips auto-eval entirely.
3. Setting `done` to a new `true` value sets `doneAt = Instant.now()`.
4. When a Step transitions to `done`, `GoalService` checks Phase
   completion — if every Step in the active Phase is `done`, Phase
   flips `COMPLETED`, next Phase flips `ACTIVE`, last Phase completes
   the Goal.
5. Phase and Goal `completedAt` are sticky — never cleared.
6. Regression flag is **transient** — not stored on the Step; computed
   on read by `StepEvaluationService.computeRegressionFlag(...)` and
   surfaced on `StepResponse.metricRegressed` (set by the controller's
   response mapper).

**Reverse-lookup index** for `onMetricChanged`: `StepRepository.findByMetricKey(userId, metricKey)` uses a Firestore
collection-group query with a `metricKey` index. Document the index
requirement in the PR description.

**Wire `POST /api/me/goals/{id}/reevaluate`** in `GoalController` to
call `StepEvaluationService.evaluateGoal`. Wire `GET
/api/me/goals/{id}` to call it just before responding.

### Test plan

- `StepEvaluationServiceTest` — plain JUnit with a fake
  `MetricResolver`:
  - THRESHOLD `LT` flips undone → done when value crosses target.
  - SUSTAINED only flips when `sustainedHolds` returns true.
  - COUNT respects `countFrom` timestamp.
  - MANUAL is never auto-evaluated.
  - `manualOverride=true` skips everything.
  - Done Step + regressed metric → stays done, regression flag = true.
  - Phase cascade: all Steps done → Phase COMPLETED, next LOCKED → ACTIVE.
  - Goal cascade: last Phase done → Goal COMPLETED, `completedAt` set.
  - Sticky test: completed Goal does **not** un-complete on Step
    manual un-check (which sets `manualOverride=true`).
- `FirestoreMetricResolverTest` — uses in-memory repos via
  `TestPersistenceConfig`, exercises one branch per `MetricKey`.

### Documentation references

- Regression policy: spec "Regression — auto-eval never un-checks"
  section.
- Step evaluation rules: spec L196–211.
- Metric data sources table: spec "Metric data sources" subsection.

### Anti-patterns

- Don't write a Step `done=false` in auto-eval — this is the most
  load-bearing rule. Cover with a dedicated test.
- Don't compute Phase/Goal completion in `StepRepository` — flow
  through `GoalService` so it owns state-machine transitions.
- Don't read Firestore directly from `StepEvaluationService` — go
  through repos and the resolver.

### Risk

Medium. Lots of branching, easy to get wrong. The unit tests are the
safety net.

---

## Phase 4 — Wire MetricChangedEvent publishers

**Subagent:** general-purpose, model = `sonnet`.
**Estimated size:** ~200–350 lines incl. tests.
**Commit:** `feat(backend): publish MetricChangedEvent from writer paths + listener wiring`.

### What to create

**Event record + publisher helper, in `core`:**

```
backend/core/src/main/java/com/gte619n/healthfitness/core/goals/events/
  MetricChangedEvent.java
  MetricChangedPublisher.java
```

```java
public record MetricChangedEvent(
    String userId,
    String metricKey,
    Instant occurredAt
) {}

@Component
public class MetricChangedPublisher {
    private final ApplicationEventPublisher publisher;
    public MetricChangedPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }
    public void publish(String userId, MetricKey key) {
        publisher.publishEvent(new MetricChangedEvent(userId, key.key(), Instant.now()));
    }
    /** Publish multiple keys at once — used when one write touches several markers. */
    public void publishAll(String userId, Set<MetricKey> keys) {
        keys.forEach(k -> publish(userId, k));
    }
}
```

**Listener in `StepEvaluationService`:**

```java
@EventListener
public void onMetricChanged(MetricChangedEvent event) {
    MetricKey key = MetricKey.fromKey(event.metricKey());
    if (key == null) return;
    onMetricChanged(event.userId(), key);
}
```

### Where to publish (verified write paths)

1. **`backend/api/.../blood/BloodController.java`** — at the end of
   `create()` (around line 72, after `readings.save(reading)`):
   ```java
   metricChangedPublisher.publish(userId, MetricKey.fromBloodMarker(marker));
   ```
   Map `BloodMarker → MetricKey` with a small helper.
2. **`backend/app/.../bloodtest/BloodTestService.java`** — at the end
   of `upload()` (~line 90) and `updateField()` (~line 172). A panel
   write can touch multiple markers — extract the set of changed
   markers and `publishAll`.
3. **`backend/integrations/.../googlehealth/WebhookHandlerService.java`** —
   in `handleUpsert` (around line 67) and `handleDelete` (around line
   73), for the body composition metric (`body.weight / bodyFatPct /
   leanMass`).
4. **`backend/api/.../medication/AdherenceController.java`** — at the
   end of `logDose()` (~line 101), `undoDose()` (~lines 134, 144).
   `meds.adherence30d` is rolling — publish unconditionally on any
   change.

**Deferred (no writer exists today):** `vitals.restingHr / hrv /
sleepScore`, `workouts.count / weeklyVolume`. Drop a
`TODO(IMPL-12 follow-up)` comment at each repo and open one
follow-up issue summarizing.

### Test plan

- `MetricChangedPublisherTest` — verify the event record fields.
- For each of the four touched writer files, add an integration test
  that calls the endpoint / service method and asserts the event was
  published using `ApplicationEvents`
  (`@RecordApplicationEvents` test annotation — Spring 6.1+).
- `StepEvaluationServiceListenerTest` — publish a `MetricChangedEvent`
  to a real `ApplicationContext` and assert the bound Step's `done`
  flips.

### Risk

Medium. Touching four existing files. Risk: a publish call lands
inside a transaction that later rolls back, leaving Goals with a
stale event. Mitigation: today's writes are non-transactional
Firestore calls — once `save()` returns, the data is durable — so
publishing right after the save is safe. Confirm by reading each
write method's body before adding the publish call.

### Anti-patterns

- Don't publish before the save — if save throws, Goals would
  re-evaluate against stale data.
- Don't introduce `@TransactionalEventListener` — Firestore writes
  aren't in a JTA transaction.
- Don't make the listener throw — wrap in try/catch + log so a Goals
  bug can't break the writing module.

---

## Phase 5 — Daily Cloud Run Job + Cloud Scheduler

**Subagent:** general-purpose, model = `opus` (infra surface area:
Spring profile, Dockerfile, cloudbuild, Cloud Scheduler resource).
**Estimated size:** ~150–250 lines incl. shell + YAML.
**Commit:** `feat(backend, infra): daily SUSTAINED re-evaluation Cloud Run Job + scheduler`.

### What to create

**Spring side:**

```
backend/app/src/main/java/com/gte619n/healthfitness/app/jobs/
  ReevaluateSustainedJob.java
```

```java
@Component
@Profile("job-sustained")
public class ReevaluateSustainedJob implements CommandLineRunner {
    private final StepEvaluationService evaluator;
    /* constructor injection */

    @Override
    public void run(String... args) {
        evaluator.reevaluateAllSustained();   // iterates all users
        // CommandLineRunner returning normally → JVM exits with 0
    }
}
```

`reevaluateAllSustained()` must iterate users. Add
`UserRepository.findAllIds()` if it doesn't already exist (verify in
Phase 5 implementation; if needed, add to that phase's commit).

**Infra side:**

```
infra/scripts/deploy-goals-sustained-job.sh    # gcloud run jobs deploy
infra/scripts/bootstrap-goals-scheduler.sh     # gcloud scheduler jobs create
```

Job deploy uses the **same image** as the service (`backend:latest`):

```bash
gcloud run jobs deploy goals-sustained-reeval \
  --image us-central1-docker.pkg.dev/$PROJECT_ID/health-fitness/backend:latest \
  --region us-central1 \
  --service-account health-fitness-runtime@$PROJECT_ID.iam.gserviceaccount.com \
  --set-env-vars SPRING_PROFILES_ACTIVE=job-sustained \
  --set-secrets <same secret bindings as the service> \
  --max-retries 1 \
  --task-timeout 600
```

Scheduler:

```bash
gcloud scheduler jobs create http goals-sustained-reeval-daily \
  --schedule "0 3 * * *" \
  --time-zone "America/New_York" \
  --uri "https://<region>-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/$PROJECT_NUMBER/jobs/goals-sustained-reeval:run" \
  --http-method POST \
  --oauth-service-account-email health-fitness-runtime@$PROJECT_ID.iam.gserviceaccount.com
```

**Cloud Build wiring** in `backend/cloudbuild.yaml` — append a fourth
step that updates the Job's image alongside the service. The Job
re-uses the same image, so the existing build + push steps don't
change.

### Verification

- `gcloud run jobs describe goals-sustained-reeval --region us-central1`
  shows the job.
- `gcloud run jobs execute goals-sustained-reeval --region us-central1
  --wait` runs to completion (manual smoke test from the deploying
  developer).
- `gcloud scheduler jobs list --location us-central1` shows the daily
  trigger.
- Unit-level: `ReevaluateSustainedJobTest` — `@SpringBootTest` with
  `@ActiveProfiles({"test","job-sustained"})` + a tiny in-memory Goal
  fixture; asserts `reevaluateAllSustained` was invoked.

### Risk

Medium-high — first Cloud Run Job in the repo, first Scheduler. The
non-Spring parts (gcloud commands, IAM, Scheduler) are the things
that go wrong; build them as scripts (not invoked by Cloud Build) so a
developer runs them once and we can see the resources in the GCP
console.

### Anti-patterns

- Don't add an HTTP endpoint that the Scheduler hits — that bypasses
  the Cloud Run Job model and complicates auth.
- Don't deploy the Job from a separate Dockerfile — reuse the service
  image to avoid drift.
- Don't run the job at 00:00 — pick 03:00 ET so it's after the latest
  end-of-day Health writes.

---

## Phase 6 — Verification

**Subagent:** general-purpose, model = `sonnet` (mostly checking).
**Commit:** none (verification only). If issues found, fix in a
follow-up commit on the same branch.

### Checks

1. `./gradlew build` — full multi-module build green.
2. `./gradlew test` — all tests pass.
3. Spotless / linters: `./gradlew check` (whichever the repo runs).
4. `grep -r "@Entity\|JpaRepository\|@Data\|@Builder" backend/` — zero.
5. `grep -r "ApplicationEventPublisher" backend/` — only the Goals
   publisher.
6. `grep -rn "TODO(IMPL-12" backend/` — only the deferred-publisher
   TODOs from Phase 4 — count matches the follow-up issue.
7. Local smoke via `bash infra/scripts/dev.sh`:
   - `curl -X POST localhost:8080/api/me/goals -H 'X-Dev-User: testuser'
     -H 'Content-Type: application/json' -d @sample-goal.json`
   - `curl localhost:8080/api/me/goals` lists it.
   - `curl localhost:8080/api/me/goals/{id}` returns deep form.
   - Create a `THRESHOLD` Step bound to `blood.ldl`, then POST a blood
     reading under target, then GET the goal — the Step should
     auto-complete.

### Acceptance criteria mapped from spec

| Spec criterion | Phase that satisfies it |
|---|---|
| Goal with Phases and Steps can be created (web/Android renderers TBD) | Phase 1 — REST creates a valid roadmap |
| `THRESHOLD` Step auto-checks when metric crosses target | Phase 3 + Phase 4 (event-driven) and Phase 1 (on-demand via GET) |
| `SUSTAINED` Step flips only after daily job confirms window | Phase 3 + Phase 5 |
| Completing all Steps in active Phase advances state machine | Phase 3 |
| Past-due Goal shows "behind schedule" treatment | UI concern — not in this PR; the `targetDate` field is present from Phase 1 so the next PR can render it |
| Regressed metric on a done Step stays done; badge surfaces | Phase 3 |
| Manually un-checking is durable until "Reset to auto" | Phase 3 |
| Invalid Gemini proposal flagged inline | Gemini PR — not this PR |

---

## Commit boundaries (one PR, six commits)

1. `docs: reconcile IMPL-12 spec with multi-tenant collection paths`
2. `feat(backend): goals/phases/steps data model + Firestore CRUD`  ← Phase 1
3. `feat(backend): metric stubs (nutrition, weekly workout aggregate) + DailyMetric hrv/sleepScore`  ← Phase 2
4. `feat(backend): metric resolver + step evaluation with notify-never-undo policy`  ← Phase 3
5. `feat(backend): publish MetricChangedEvent from writer paths + listener wiring`  ← Phase 4
6. `feat(backend, infra): daily SUSTAINED re-evaluation Cloud Run Job + scheduler`  ← Phase 5

PR title: `feat(backend): IMPL-12 Goals foundation — data model, evaluator, events, daily job`.

---

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Multi-tenant deviation from spec missed during review | low | First commit reconciles the spec text. |
| Firestore collection-group index missing → Step reverse-lookups fail in prod | medium | PR description lists the required indexes; on-demand evaluation (GET path) still works without them. |
| Direct repo writes from controllers expand the publish-call surface unexpectedly | low | Centralize publishing in `MetricChangedPublisher`; PR description enumerates touch points so reviewers don't get surprised. |
| Daily Job runs before any Goals exist → no-op but consumes warm-start cost | low | Acceptable — single execution per day. |
| `manualOverride` semantics misread by a future developer → silent regression | medium | Dedicated `StepEvaluationService` test cases for each transition; comment on `Step.manualOverride` field documenting the invariant. |
| Stub-backed metrics have no writer → Steps bound to them never auto-complete | high | Documented in the spec ("Metric data sources" section) and surfaced by `MetricResolver` returning unavailable, not false. UI will be added to show "metric unavailable" badge in a later PR. |

---

## Follow-up issues to open after PR lands

1. **Daily metric writer hook-up** — find or create a `DailyMetricService` and add the publish call for `vitals.restingHr / hrv / sleepScore`.
2. **Workout writer hook-up** — same for `workouts.count / weeklyVolume`.
3. **Ingestion specs** for stubbed metrics (`NutritionDailyLog`, `WeeklyWorkoutAggregate`).
4. **Goals chat / Gemini integration** (build sequence steps 6–7) — a separate plan.
5. **Web UI** (build sequence steps 6–7).
6. **Android UI** (build sequence steps 8–9).
