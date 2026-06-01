# Backend API surface

All endpoints are under `/api`. Everything under `/api/me/**` requires a valid
Google ID token (see [patterns.md → Auth](patterns.md#auth)); `/api/hello` and
`/api/webhooks/**` are the exceptions. Controllers live in the backend `api`
and `app` modules and delegate to services in `core` — they never touch
repositories directly.

Two transport flags appear inline below:
- **[SSE]** — streams `text/event-stream` (used for all LLM streaming; see
  [patterns.md → Streaming](patterns.md#streaming-sse--multipart)).
- **[multipart]** — accepts a `multipart/form-data` upload (PDFs, photos).

## Auth / profile
| Method · path | Purpose |
|---|---|
| `GET /api/hello` | Public health probe (no auth) |
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

## Nutrition
| Method · path | Purpose |
|---|---|
| `GET·POST /api/me/nutrition`, `GET /today`, `GET·PUT /target`, `GET /{date}` | Daily logs + macro target |
| `POST·PATCH·DELETE /{date}/entries…` | Food entry CRUD |
| `POST /api/nutrition/capture/meal` **[multipart]**, `POST /capture/label` **[multipart]** | Gemini meal-photo / label extraction |
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

## Admin (`/api/admin/**`, email-gated by `AdminCheckAspect`)
| Area | Endpoints |
|---|---|
| Drugs | `GET·POST·PATCH·DELETE /admin/drugs…`, image-prompt / regenerate / `upload-image` **[multipart]** / select / delete-image, `merge-into/{targetId}` |
| Equipment | `GET /pending`, `GET /catalog`, `POST`, `approve` / `reject`, `PATCH`, image-prompt / regenerate / `upload-image` **[multipart]** / select / delete, `merge-into` |

## Webhook (ingestion)
| Method · path | Purpose |
|---|---|
| `POST /api/webhooks/google-health` | Google Health push receiver — bypasses the JWT filter, authenticated by a shared-secret `Authorization` header (`app.googlehealth.webhook-secret`) |
