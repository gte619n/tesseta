# Backend API surface

All endpoints are under `/api`. Everything under `/api/me/**` requires a valid
bearer JWT — a Google ID token (web) or a backend session access token (native
clients); see [patterns.md → Auth](patterns.md#auth). `/api/hello`,
`/api/webhooks/**`, and the public session endpoints (`/api/auth/refresh`,
`/api/auth/logout`) are the exceptions. Controllers live in the backend
`api.<feature>` packages and delegate to services (pure-domain services in
`core.<feature>`, integration-orchestrating ones alongside the controller in
`api.<feature>`); trivial pass-through reads may use a repository directly.

Two transport flags appear inline below:
- **[SSE]** — streams `text/event-stream` (used for all LLM streaming; see
  [patterns.md → Streaming](patterns.md#streaming-sse--multipart)).
- **[multipart]** — accepts a `multipart/form-data` upload (PDFs, photos).

## Auth / profile
| Method · path | Purpose |
|---|---|
| `GET /api/hello` | Public health probe (no auth) |
| `POST /api/auth/exchange` | Exchange a Google ID token (header) for a backend access + refresh pair (ADR-0010) |
| `POST /api/auth/refresh` | Public — trade a refresh token (body) for a new pair; 401 when dead |
| `POST /api/auth/logout` | Public — revoke a refresh token (body) |
| `GET /api/me` | Whoami — identity from the verified token |
| `PATCH /api/me` | Update profile (e.g. heightCm) |

## Blood
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/blood`, `DELETE /api/me/blood/{readingId}` | Manual marker readings (POST publishes a metric event) |
| `POST /api/me/blood/reports` **[multipart][SSE]** | Upload lab PDF → Gemini extraction, phased SSE events |
| `GET /api/me/blood/reports`, `GET /{reportId}`, `GET /{reportId}/pdf`, `PATCH /{reportId}/field`, `DELETE /{reportId}` | Report list / detail / PDF / edit / delete |

## Body composition / DEXA / metrics / devices
| Method · path | Purpose |
|---|---|
| `GET /api/me/body-composition` | Body-composition series (Google Health) |
| `POST /api/me/dexa/scans` **[multipart][SSE]** | Upload DEXA PDF → extraction stream |
| `GET·…/dexa/scans`, `GET /{scanId}`, `GET /{scanId}/pdf`, `PATCH /{scanId}/field`, `DELETE /{scanId}` | DEXA list / detail / PDF / edit / delete |
| `GET /api/me/daily-metrics` | Steps / RHR / sleep / HRV / sleepScore |
| `GET /api/me/devices` | Per-platform last-sync status |
| `POST·DELETE /api/me/google-health/connect`, `GET /api/me/google-health/status` | Connect / disconnect / status for Google Health |

## Medications
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/medications`, `GET·PUT·DELETE /{id}` | Medication CRUD |
| `POST /{id}/dosage`, `POST /{id}/discontinue`, `POST /{id}/reactivate` | Dosage-period change / discontinue / reactivate |
| `GET /api/me/medications/today` | Today's doses across all meds |
| `GET·POST /{medId}/adherence`, `DELETE /{date}/{window}` | Adherence logging (POST publishes a metric event) |
| `GET·PUT /api/me/medications/reminder-settings` | Dose-reminder config (IMPL-16): master switch, default "HH:mm" per `TimeWindow`, per-med overrides (mute / custom slot times). Scheduling is on-device; the backend only stores this doc |

## Drugs (shared catalog)
| Method · path | Purpose |
|---|---|
| `GET·POST /api/drugs`, `GET·PUT /{drugId}` | Drug catalog read/write |
| `POST /api/drugs/lookup`, `POST /lookup/stream` **[SSE]** | Gemini + Google-Search grounded drug lookup |
| `GET /api/drugs/search`, `POST /{drugId}/regenerate-image` **[SSE]** | Search / image regen |

## Goals
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/goals`, `GET·PATCH·DELETE /{goalId}` | Goal CRUD (`GET /{id}` triggers evaluation) |
| `POST /{goalId}/reevaluate` | Force re-evaluation |
| `POST·PATCH·DELETE /{goalId}/phases…`, `PUT /phases/order` | Phase CRUD + reorder |
| `POST·PATCH·DELETE /…/steps…`, `PUT /steps/order` | Step CRUD + reorder |
| `POST /api/me/goals/chat` **[SSE]** | Gemini-Pro goal-planning chat (`propose_goal_structure`) |
| `POST /api/me/goals/chat/{threadId}/commit`, `GET /threads`, `DELETE /threads/{id}` | Commit proposal / thread management |

## Workout programs (IMPL-15 / IMPL-17 / IMPL-18)
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/workout-programs`, `GET·PATCH·DELETE /{id}` | Program CRUD (`GET /{id}` is the deep tree) |
| `POST /{id}/activate`, `GET /{id}/calendar?from&to` | Materialize + read dated `ScheduledWorkout`s |
| `POST·PATCH·DELETE /{id}/phases…`, `/days…`, `PUT …/order` | Phase / day CRUD + reorder |
| `PUT /{id}/sessions/{scheduledId}` | Log/complete a session (actuals fan-out, IMPL-17) |
| `POST /api/me/workout-programs/chat` **[SSE]** | Gemini-Pro history-grounded designer chat. Tools: `propose_workout_program` (terminal) + read-only `get_exercise_history` / `get_lab_history` (mid-stream round-trips, IMPL-18). Proposal event = `{program, issues, warnings}` (issues block commit; warnings are advisory, R1). Optional `programId` on the first turn binds the thread to an active program for **in-place editing** (IMPL-18b) |
| `POST /chat/{threadId}/commit`, `GET /chat/threads`, `GET /chat/{id}`, `DELETE /chat/threads/{id}` | Commit proposal / thread management. A program-bound thread (IMPL-18b) updates that program in place + re-materializes forward (**200**); an unbound thread creates a new draft (**201**) |
| `GET /api/me/workout-programs/chat/trt-context` | TRT monitoring panel for the designer's labs surface (ADR-0015): `{onTrt, markers[], dangerFlags[]}` |

## Nutrition
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/nutrition`, `GET /today`, `GET·PUT /target`, `GET /{date}` | Daily logs + macro target |
| `POST·PATCH·DELETE /{date}/entries…` | Food entry CRUD |
| `POST /api/nutrition/capture/meal` **[multipart]**, `POST /capture/label` **[multipart]** | Gemini meal-photo / label extraction |
| `POST /api/nutrition/describe` `{description}` | Describe a meal → Gemini itemizes, matches a previously-saved meal (user's own first) or creates+saves a new one (macros + generating photo); returns the resolved `SavedMeal` |
| `POST /api/me/nutrition/{date}/describe-meal` `{mealId?, description?, meal?}` | Log a described meal onto a day as a composite entry — by resolved `mealId`, or one-shot by `description` |
| `POST /api/me/nutrition/{date}/describe-meal-async` `{description, meal?}` | Fire-and-forget describe (IMPL-16): returns an `ANALYZING` placeholder (202) named with the description; resolution finalizes it server-side and the day view polls it in |
| `GET /api/me/nutrition/recent-meals?days&limit` | Distinct foods/meals logged recently (deduped by foodId or kind+name, newest first) — backs the add flow's one-tap recents list |
| `POST /api/me/nutrition/{date}/relog` `{sourceDate, sourceEntryId, meal?}` | One-tap re-log: server-side copy of a past entry (reuses catalog foods, macros, ingredients and the finished-meal image — no AI rework) |
| `GET /api/foods/search`, `GET /{foodId}`, `GET /barcode/{code}`, `POST`, `POST /{id}/confirm`, `POST /{id}/image/regenerate` | Food catalog |

## Gyms / equipment
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/gyms`, `GET·PATCH·DELETE /{id}` | Location CRUD |
| `POST /{id}/default`, `POST /{id}/photo` **[multipart]**, `DELETE /{id}/photo` | Default flag / cover photo |
| `PATCH·DELETE /{id}/equipment/{equipmentId}` | Per-location equipment override |
| `POST /{locationId}/equipment/import/preview`, `/import/confirm` | Bulk equipment import (preview → confirm) |
| `GET /api/equipment`, `GET /{id}`, `GET /categories` | Shared equipment catalog |
| `POST·GET·DELETE /api/me/equipment…` | User-contributed equipment |

## Admin (`/api/admin/**`, email-gated by `@AdminOnly` → `AdminAuthorizer` / `app.admin.emails`)
| Area | Endpoints |
|---|---|
| Drugs | `GET·POST·PATCH·DELETE /admin/drugs…`, image-prompt / regenerate / `upload-image` **[multipart]** / select / delete-image, `merge-into/{targetId}` |
| Equipment | `GET /pending`, `GET /catalog`, `POST`, `approve` / `reject`, `PATCH`, image-prompt / regenerate / `upload-image` **[multipart]** / select / delete, `merge-into` |

## Webhook (ingestion)
| Method · path | Purpose |
|---|---|
| `POST /api/webhooks/google-health` | Google Health push receiver — bypasses the JWT filter, authenticated by a shared-secret `Authorization` header (`app.googlehealth.webhook-secret`) |
