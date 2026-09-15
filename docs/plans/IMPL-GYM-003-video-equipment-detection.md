# IMPL-GYM-003: Gym Equipment Detection from a Video Walkthrough

> **STATUS: PLANNED (not yet built)** — greenfield. Supersedes the manual
> paste-a-list step of the implemented IMPL-GYM-002 (bulk import) by adding a
> video-first capture path that *feeds the same review/confirm pipeline*.
> Owner decisions locked via interview 2026-09-15 (see Decisions).

## Goal

When a user adds (or edits) a gym, let them **record/upload a short video walking
through the gym** and have the system detect every distinct piece of equipment.
The detected list flows into the **existing bulk-import preview → confirm review
UX** (IMPL-GYM-002): fuzzy-matched against the equipment catalog, unmatched items
become `PENDING_REVIEW` catalog submissions, and confirmed items are added to the
location. The user reviews before anything is written.

This is how the operator originally bootstrapped their gym (a one-off Gemini run
over a walkthrough video); this spec turns that into a real, repeatable in-app
feature on **Android and web**.

## Background — what already exists (reuse, don't reinvent)

- **Location/gym model + persistence**: `Location` record with an
  `equipmentIds: List<String>` array (NOT a subcollection) at
  `users/{userId}/locations/{locationId}`. Adding equipment =
  `LocationService.addEquipmentToLocation()` (dedup append).
- **Bulk import (IMPL-GYM-002), fully live**:
  - `BulkImportController` — `POST /api/me/gyms/{locationId}/equipment/import/{preview,confirm}`.
  - `BulkImportService` — preview (parse → Jaccard match vs catalog + user's
    PENDING submissions → per-item action `MATCH_AUTO ≥0.85 / MATCH_SUGGESTED
    0.6–0.84 / CREATE_NEW <0.6`) and confirm (link match / `submitEquipment`
    new / skip → `equipmentIdsToAdd`).
  - `EquipmentParserService` — Gemini (`gemini-3.8-flash`) turns *text* into
    `List<ParsedEquipment>` via the house taxonomy (categories, subcategories,
    spec schemas).
  - Web `EquipmentImportModal.tsx` — two-stage preview/confirm review table.
- **AI media rails to mirror**:
  - **Structured Gemini extraction via tool-calling**: `MealPhotoExtractor`
    (`extract_meal_items` FunctionDeclaration → structured JSON).
  - **GCS media storage**: `MealPhotoStorage` (`gs://{bucket}/…/{uuid}.ext` →
    public URL; never leaks GCS errors).
  - **Durable async job rail**: `MealCaptureService` + Cloud Tasks
    (`CloudTasksNutritionJobQueue`, `/internal/**` secret-gated handler,
    idempotent, reconcile sweep) — the pattern for slow work that must survive
    Cloud Run scale-in.
  - **Signed URLs**: `SignedUrlService` (SEC-012) mints V4 **read** URLs; extend
    for **resumable upload** URLs here.
- **Gemini client**: official `com.google.genai` SDK **v1.69.0**, API-key mode
  (Gemini Developer API, not Vertex), shared `Client` bean in `GeminiConfig`.

Nothing of a video pipeline exists today — confirmed no half-built endpoints,
flags, or frame-extraction code.

## Scope

**In scope (v1):**
- Direct-to-GCS **resumable upload** of a walkthrough video (Android + web), so
  large videos never transit the backend (Cloud Run request-size limit).
- Backend **`GymVideoScanService`** (core) orchestrating: register scan → durable
  Cloud Tasks job → download from GCS → **Gemini Files API** upload → wait for
  `ACTIVE` → structured `detect_equipment` call on a video-capable Gemini flash
  model → **reuse `BulkImportService` matching** → persist a preview → mark READY.
- **`EquipmentVideoDetector`** (integrations) — returns the SAME
  `List<ParsedEquipment>` the text parser returns, so the entire matching/confirm
  path is reused verbatim.
- Scan session persistence + status endpoints; **confirm reuses
  `BulkImportService.confirm`**.
- Android capture/pick + upload + poll + review UI; web upload + poll + review UI
  (extend `EquipmentImportModal` with a "from video" source stage).
- Feature flag + gated GCS/Files beans; raw video deleted after analysis.

**Out of scope (deferred):**
- On-device frame extraction (we send the whole video — Decision D1).
- Real-time / streaming detection while recording.
- Multi-video merge into one gym, or re-scan diffing against existing equipment.
- Detecting equipment *quantity/count* (e.g. "6 treadmills") — v1 detects
  presence of each distinct model; counts can be edited after.
- Admin bulk approval UI for the resulting `PENDING_REVIEW` submissions (existing
  flow applies).
- Auto image-generation for detected items (existing async image flow applies).

## Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Ingestion | **Whole video via the Gemini Files API** (model samples frames natively). Not client/server frame extraction — least client code, best coverage, matches the original bootstrap. |
| D2 | Platforms | **Android + web together** in v1. Android = record or pick; web = upload a file. |
| D3 | Review | **Review-then-confirm**, reusing the IMPL-GYM-002 preview/confirm UX. Nothing is added to the gym or catalog until the user confirms. |
| D4 | Upload path | Client uploads **directly to GCS via a resumable signed URL**; backend reads the object from GCS. Avoids Cloud Run's ~32 MB request cap and gives resumability on flaky gym Wi-Fi/mobile. Multipart-through-backend is NOT used. |
| D5 | Files API | Dev-API key mode does **not** accept `gs://` `fileData`, so the backend **downloads the GCS object and uploads bytes to the Files API**, polls until `state=ACTIVE`, then references it via `Part.fromUri(file.uri, mimeType)`. Files auto-expire (~48 h) — fine for one-shot analysis. |
| D6 | Async | **Durable Cloud Tasks job** + `ANALYZING → READY/FAILED` scan session the client polls. Same rationale as the nutrition durable rail: Files-processing + generateContent on a multi-minute video is too slow/fragile for a request thread. |
| D7 | Model | A **video-capable Gemini flash** id (config `app.gym.video-scan.model`, default the current GA flash that accepts video). **Spike S1** confirms the exact GA id + that video input works on the Developer API before pinning; prefer GA over `-preview` (SOTA-002). |
| D8 | Output contract | `detect_equipment` FunctionDeclaration returns an array whose items map 1:1 to **`ParsedEquipment`** (name, brand, category, subcategory, specSchema, specs, confidence, rawText). No new matching code. |
| D9 | Dedup | Prompt instructs "each distinct model once"; backend additionally de-dupes the detected list by the existing Jaccard normalization before matching (same model seen at multiple points in the video). |
| D10 | Retention | The raw video is **deleted from GCS after the scan reaches READY/FAILED** (a gym video may show bystanders — privacy + cost). Short TTL lifecycle rule as backstop. The Files API copy expires on its own. |
| D11 | Confirm reuse | A thin `POST …/scan/{scanId}/confirm` internally calls `BulkImportService.confirm` with the user's per-item decisions — same idempotency, same catalog-submission + add-to-location semantics. |
| D12 | Limits | v1 caps: length ≤ 3 min, size ≤ 200 MB, mime `video/mp4` or `video/quicktime`. Enforced client-side (fail fast) and validated at register time. |

## Data model

New per-location subcollection **`equipmentScans`**:
`users/{userId}/locations/{locationId}/equipmentScans/{scanId}`

```
record EquipmentScan {
  String   userId, locationId, scanId
  ScanStatus status        // REGISTERED, UPLOADED, ANALYZING, READY, FAILED
  String   videoRef        // gs:// object (cleared after terminal state)
  String   mimeType
  Long     sizeBytes
  Integer  detectedCount   // populated at READY
  PreviewResult preview    // REUSED IMPL-GYM-002 shape; populated at READY
  String   error           // populated at FAILED (user-safe message)
  Instant  createdAt, updatedAt
}
```

- `PreviewResult` / per-item DTOs are the **existing** bulk-import types — no new
  matching DTOs.
- No change to `Location` or `Equipment`; confirm writes through the existing
  `submitEquipment` + `addEquipmentToLocation` paths.

## API

All under the existing `/api/me/gyms/{locationId}/equipment` group; authenticated
(`/api/me/**`), per-user scoped (ADR-0021).

1. **Register + get an upload URL**
   `POST …/scan` → body `{ mimeType, sizeBytes }`
   → `201 { scanId, uploadUrl, uploadMethod: "PUT", headers, status: "REGISTERED" }`
   - Validates D12 limits. `uploadUrl` = resumable V4 signed GCS PUT for
     `gs://{gym-video-bucket}/gym-scans/{userId}/{scanId}.{ext}`.

2. **Mark uploaded → start analysis**
   `POST …/scan/{scanId}/start` → `202 { scanId, status: "ANALYZING" }`
   - Verifies the GCS object exists + size/type, flips to `UPLOADED`, enqueues the
     Cloud Tasks job, flips to `ANALYZING`.

3. **Poll status / preview**
   `GET …/scan/{scanId}`
   → `{ scanId, status, detectedCount?, preview?, error? }`
   - `preview` present only at `READY`, shaped exactly like the bulk-import
     preview response so the same review UI renders it.

4. **Confirm (reuses bulk import)**
   `POST …/scan/{scanId}/confirm` → body = the existing
   `BulkImportConfirmRequest` (per-item `USE_MATCH | CREATE_NEW | SKIP`,
   editable names)
   → existing `BulkImportConfirmResponse` (`equipmentIdsToAdd`, added count).

5. **Internal job handler**
   `POST /internal/gym-scans/analyze` (secret-gated like nutrition jobs; body =
   `{ userId, locationId, scanId, videoRef, mimeType }`; idempotent — early-return
   if the scan is already `READY/FAILED`).

## Backend components

- **`GymVideoStorage`** (integrations) — mint resumable upload URL; read object
  bytes/stream; delete object. Mirrors `MealPhotoStorage`; bucket
  `app.gym.video-scan.bucket`. New: **write** (resumable) signed URL — extend the
  SEC-012 `SignedUrlService` (add a `signedResumableUploadUrl(objectName, mime)`).
- **`GeminiFilesService`** (integrations) — thin wrapper over
  `client.files.upload(InputStream, length, cfg)` + poll `client.files.get(name, cfg)`
  until `state().knownEnum() == ACTIVE` (bounded, with timeout) → returns the file
  `uri()`; delete the file after use. **Confirmed available in 1.69.0** (Spike S1).
- **`EquipmentVideoDetector`** (integrations) — upload via `GeminiFilesService`,
  `generateContent(model=app.gym.video-scan.model, [Part.fromUri(uri,mime),
  systemPrompt], tools=[detect_equipment])`, parse tool args →
  `List<ParsedEquipment>`. **Same output type as `EquipmentParserService`.**
- **`GymVideoScanService`** (core) — register/start/status/confirm orchestration;
  writes `EquipmentScan`; enqueues the durable job; the job body runs the detector
  then **`BulkImportService`** matching (dedup per D9) and persists `preview`;
  deletes the video (D10). Confirm delegates to `BulkImportService.confirm`.
- **`GymScanJob` + queue binding** — reuse the nutrition Cloud Tasks
  infrastructure (or a sibling queue `gym-scans`); local in-process fallback for
  dev/tests, same as nutrition.
- **Controller** — `GymVideoScanController` for the four `/api/me` routes;
  internal handler on the existing secret-gated `/internal` controller family.

### Gemini prompt (system) — sketch
Reuse the **exact taxonomy** from `EquipmentParserService` (categories,
subcategories, spec schemas incl. `WEIGHT_SET`) so detected items match the
catalog cleanly. Prepend video framing:

> "You are watching a first-person walkthrough video of a gym. Identify every
> **distinct** piece of exercise equipment that appears. List each distinct
> model **once**, even if it appears many times or there are several identical
> units. Ignore people, mirrors, TVs, and decor. For each item emit
> {name, brand, category, subcategory, specSchema, specs, confidence, rawText}
> using ONLY these categories/subcategories/specSchemas: …[reused taxonomy]…
> `confidence` = CERTAIN | LIKELY | UNCERTAIN; `rawText` = a short note of what
> you saw (e.g. 'row of 8 Matrix treadmills, ~00:14')."

Return via the `detect_equipment` tool (structured), not prose.

## Clients

**Shared UX**: capture/upload → progress → `ANALYZING` (poll) → **the existing
preview/confirm review table** → done. The review table is unchanged; only the
*source* stage differs (video instead of paste).

- **Android** (feature-workouts gyms): "Scan gym with video" on the add/edit gym
  screen → CameraX record (or system picker) → enforce D12 limits → `POST scan`
  → resumable PUT to GCS (with retry/resume) → `POST start` → poll `GET scan` →
  render the bulk-import review composable → `confirm`. Uploads run on the durable
  WorkManager op rail (survives app death), mirroring the nutrition capture rail.
- **Web** (`app/me/workouts/gyms/[locationId]`): add a "From video" source to
  `EquipmentImportModal` → `<input type=file accept="video/*">` → `POST scan` →
  resumable PUT → `POST start` → poll → **reuse the existing preview/confirm
  stages** → confirm. `gym-api.ts` gains `scanRegister/scanStart/scanStatus/
  scanConfirm`.

## Configuration & flags

```
app.gym.video-scan:
  enabled: ${GYM_VIDEO_SCAN_ENABLED:false}     # gates GCS+Files beans (like capture.enabled)
  bucket:  ${GYM_VIDEO_BUCKET:health-fitness-160-gym-scans}
  model:   ${GYM_VIDEO_SCAN_MODEL:<GA video-capable flash>}   # Spike S1
  max-seconds: 180
  max-bytes: 209715200                          # 200 MB
```
- Bean-gating pattern matches nutrition `capture.enabled` so unit tests load the
  context without GCS/Gemini creds.
- New GCS bucket via terraform (`infra/`), with a lifecycle rule deleting objects
  after N days as a retention backstop (D10) and **not** public (SEC posture).
- Cloud Tasks queue (reuse or sibling); secret for the `/internal` handler
  mirrors `nutrition-jobs-secret` (trim both sides — see prior newline gotcha).

## Testing

- **Unit**: `EquipmentVideoDetector` with a fake `GeminiFilesService` + canned
  tool response → asserts `ParsedEquipment` mapping; dedup (D9).
- **Reuse**: existing `BulkImportService` matching tests already cover
  match/create/skip — the detector plugs into them unchanged.
- **Service**: `GymVideoScanService` job handler idempotency (READY/FAILED
  early-return), video deletion on terminal state, FAILED path sets a user-safe
  error.
- **Controller**: register enforces D12 limits + per-user scope (foreign
  `locationId`/`scanId` → 404, mirroring `MealPhotoServingTest`); confirm delegates
  to bulk import.
- **Clients**: web modal video-source stage (parse → preview → confirm) with a
  mocked API; Android upload-rail + poll unit tests.

## Rollout

1. Ship behind `GYM_VIDEO_SCAN_ENABLED=false`; land backend + both clients.
2. Provision the bucket + queue + secret (terraform/Secret Manager).
3. Enable in a dev/staging project; run S1 model/Files spike against real Gemini.
4. Turn on in prod; monitor Gemini cost per scan and job failure rate
   (OBS structured logs + the SEC-001 per-user AI rate limit already applies to
   Gemini endpoints — confirm the scan route is covered).

## Spikes / open risks (resolve before/at build)

- **S1 (blocking)**: Confirm `com.google.genai` **1.69.0** Files API surface
  (`client.files().upload`, `.get` for `state`, `Part.fromUri`) and that the
  chosen GA flash id accepts **video** on the Developer API; capture its
  media-resolution/token cost for a ~2-min clip. Pin the model only after this.
- **Cost/latency**: video tokens scale with length × resolution; D12 caps + a
  possible client-side downscale/trim keep this bounded. Measure in S1.
- **Files API limits/TTL**: per-file size + ~48 h expiry; our one-shot flow fits,
  but the job must handle an expired/again-PROCESSING file (re-upload/backoff).
- **Cloud Run request cap**: fully avoided by D4 (direct-to-GCS). Ensure the
  `/internal` handler streams GCS→Files rather than buffering 200 MB in heap.
- **Accuracy**: mirrors, reflections, and partial views cause dup/false detects;
  D3 review is the safety net, and `confidence` surfaces low-certainty items for
  the user to skip.

## Spike S1 — RESULTS (2026-09-15) — the SDK question is RESOLVED ✅

Ran against the actual `com.google.genai` **1.69.0** jar (javap) + current Google
AI docs. The blocking risk (can the pinned SDK do the Files-API video flow?) is
**gone — it fully can**. Details:

**SDK surface (confirmed, exact):**
- Client accessor is a **field, not a method**: `client.files` (so
  `client.files.upload(...)` / `client.files.get(...)` — the spec's `client.files()`
  was wrong; corrected here).
- `Files.upload(InputStream, long, UploadFileConfig)` exists → **stream the GCS
  object straight into the Files API** with no 200 MB heap buffer (resolves the
  Cloud-Run heap risk). Also `upload(byte[]/File/String path, cfg)`.
- `Files.get(String name, GetFileConfig)` → returns `types.File` with
  `state()` (`FileState.Known` = `PROCESSING | ACTIVE | FAILED | STATE_UNSPECIFIED`),
  `uri()`, `name()`, `error()` (`FileStatus`), `expirationTime()`. So the
  **poll-until-ACTIVE** loop and the 48 h-TTL handling are both expressible.
- `Part.fromUri(String uri, String mimeType)` exists → reference the uploaded file
  in `generateContent`. (`fromBytes`, `fromText` also present.)
- `GenerateContentConfig` exposes `tools()` (tool-calling for structured output —
  same as `MealPhotoExtractor`), `responseSchema()`, `systemInstruction()`, **and
  `mediaResolution()`**. `MediaResolution.Known` = `LOW | MEDIUM | HIGH`.
- `types.VideoMetadata` exists (fps / clip start-end) → we can **down-sample fps**
  and/or clip to cut tokens further.

**Cost is a non-issue (quantified):** current Gemini video tokenization is
~**100 tokens/s at LOW media resolution** (≈66 tokens/frame @1 fps), ~300/s at
HIGH, plus ~32 tokens/s audio. Equipment is large/obvious, so **`mediaResolution
= MEDIA_RESOLUTION_LOW`** is the right default, and we can drop fps via
`VideoMetadata` and skip audio. A 2-min walkthrough at LOW ≈ 12k video tokens
(+~4k audio if kept) ≈ **well under a cent** at flash-tier input pricing; even the
3-min cap is trivial. **Recommendation: pin `mediaResolution = LOW`, fps ≈ 1, no
audio need.** (Updates D5.)

**Files API limits (current):** 2 GB/file, **20 GB total per project**, **48 h**
auto-expiry, inline cap now 100 MB (Jan 2026). Our flow uploads transiently and
**must delete the Files-API file right after `generateContent`** (not only the GCS
object) so concurrent scans don't approach the 20 GB project cap. (Adds to D10.)

**One simplification to validate live (non-blocking):** a Jan-2026 update
reportedly added **direct file input from a GCS bucket / HTTPS / signed URL**
(the docs list a "Cloud Storage registration" input and a Java `gs://…` sample),
which — if it works with our **API-key (Developer API)** — would let us pass a
short-lived **signed READ URL** (reusing SEC-012 `SignedUrlService`) straight into
`Part.fromUri(signedUrl, mime)` and **drop the Files-API upload/poll hop entirely**.
The official docs are ambiguous about API-key (vs Vertex) support, so **keep the
Files-API upload as the confirmed path (D5)** and treat the URL shortcut as an
optimization to confirm in the live smoke test below.

**Remaining unknowns — both need a real API key, ~30 min live smoke test (S1b):**
1. Confirm **`gemini-3.8-flash`** (the project's shared flash id, already used for
   image vision in `MealPhotoExtractor`) accepts **video** input and returns a
   clean tool-call equipment list. If not, select the current GA flash that does.
2. Confirm whether the signed-URL / `gs://` direct-input shortcut works under the
   API key (decides D5-vs-shortcut).
   → Cheapest as a tiny throwaway `main()` or a `@Disabled` integration test hitting
   the dev key with a 20–30 s sample clip; not wired into CI. No product code
   depends on the answer — it only chooses D5 vs the shortcut and pins the model.

**Net:** greenlight to build. The SDK supports everything; cost is negligible with
LOW media resolution; the only open items are a quick live model/URL confirmation
that doesn't block starting the backend slice (which uses the confirmed
Files-API-upload path).

## Effort (rough)

Backend ~3–4 d (Files service + detector + scan service/controller + job wiring +
tests), web ~1.5–2 d (modal source stage + api + poll), Android ~2.5–3 d (capture
+ resumable upload rail + poll + review composable), infra ~0.5 d (bucket/queue/
secret/flag), plus the S1 spike ~0.5 d. Most matching/review/confirm logic is
reused, which is where the savings are.
