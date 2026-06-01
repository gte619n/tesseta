# IMPL-13: Add TESTOSTERONE to the canonical blood-marker set

## Goal

Add `TESTOSTERONE` as a first-class canonical `BloodMarker` across the stack so
users can record and track testosterone the same way as the other eight markers
— manual entry, lab-report extraction, marker history, reference-range bar,
dashboard panel — instead of it being a label the clients reference but the
backend cannot store or score.

This closes the **Q10** gap from the Android parity work
(`IMPL-AND-FOLLOWUP-QUESTIONS.md`): the Android dashboard's blood-panel display
order already lists `"TESTOSTERONE"` first and the web surfaces it, but the
backend `BloodMarker` enum — the contract both clients bind to — does not contain
it, so testosterone silently never appears.

## Background / current state

| Surface | File | TESTOSTERONE today |
|---|---|---|
| Backend enum | `backend/core/.../blood/BloodMarker.java` | **Absent** (8 values: TOTAL_CHOLESTEROL, LDL, HDL, TRIGLYCERIDES, APO_B, HBA1C, FASTING_GLUCOSE, HS_CRP). Storage uses the enum name verbatim. |
| Backend ranges | `backend/core/.../blood/BloodReferenceRanges.java` | **Absent.** Nested `Range` record + `Orientation` enum; an 8-entry `Map.of(...)`; `rangeFor(marker)` **throws** if a marker has no range. |
| Backend request | `backend/api/.../blood/BloodController.java` → `record CreateRequest(BloodMarker marker, …)` | `marker` is typed **`BloodMarker`**; Jackson binds the JSON string to the enum **by name**. A `"TESTOSTERONE"` body currently fails to deserialize (no such constant). |
| Backend response | `backend/api/.../blood/BloodReadingResponse.java` | `from(...)` calls `BloodReferenceRanges.rangeFor(r.marker())` and emits `reference{unit, orientation, goodThreshold, displayMin, displayMax}`. Works for any marker that has a range. |
| Web — blood page | `web/app/me/blood/page.tsx` | Label "Testosterone" + copy present; `MARKER_INFO` holds **description/target only** (target: "300–1000 ng/dL (men), 15–70 ng/dL (women)"). The range comes from the API response — so the blood page renders testosterone correctly once the API returns it. |
| Web — dashboard | `web/app/page.tsx` `DEFAULT_REFS` | Has a **fallback** range for extracted markers: `TESTOSTERONE { min: 200, threshold: 300, max: 1200, HIGHER_IS_BETTER }`. |
| Android domain enum | `android/core-domain/.../domain/blood/BloodModels.kt` | **Absent** (mirrors the backend's 8). |
| Android `MarkerCatalog` | `android/core-domain/.../domain/blood/MarkerCatalog.kt` | **Absent** — no `displayName` / `description` / `target` / `DISPLAY_ORDER` entry. |
| Android dashboard mapper | `android/core-data/.../data/dashboard/DashboardData.kt` | Already lists `"TESTOSTERONE"` first in `BloodMarkerSummaryMapper.DISPLAY_ORDER` and maps it to label `"Testosterone"` — resolves to nothing today because no readings carry the marker. |

Consequence today: a `POST /api/me/blood` with `marker: "TESTOSTERONE"` fails
enum deserialization (HTTP 400); a lab-extracted "Testosterone" row falls into
the "Other markers" bucket; and the dashboard panel's first slot is always
empty.

**The backend is — and should remain — the single source of truth for the
range.** The web blood page and the Android clients already read the range off
`BloodReadingResponse.reference`; only the web *dashboard* keeps a local
fallback table. So the fix is to give the backend a testosterone range; every
API-driven surface then renders it identically and automatically.

## Scope

In scope:

- Add `TESTOSTERONE` to the backend `BloodMarker` enum and a matching `Range`
  entry to `BloodReferenceRanges`.
- Add `TESTOSTERONE` to the Android `core-domain` `BloodMarker` enum and wire its
  `displayName` / `description` / `target` / `DISPLAY_ORDER` into `MarkerCatalog`.
- Tests on both sides (backend range + POST round-trip; Android catalog +
  dashboard ordering).

Out of scope (deferred — see Open questions):

- **Sex-specific ranges.** A single male-oriented range is used (matching the
  web). A sex-aware range needs a `Profile.sex` field and a `rangeFor(marker,
  sex)` signature change across all markers — a separate IMPL.
- **Web changes.** The web blood page is already API-driven; no web code is
  required for parity. (One optional dashboard-fallback tidy is flagged below.)
- **Extraction-prompt tuning.** The pipeline emits a canonical
  `ExtractedMarker.name`; once `TESTOSTERONE` is a known enum value, name
  matching should pick it up. No prompt change planned, but verify (Acceptance #5).
- **Unit conversion.** `ng/dL` is stored/displayed verbatim like every other
  marker's unit; no nmol/L conversion.

## Decisions

| Topic | Decision |
|---|---|
| Reference range values | `unit = "ng/dL"`, `orientation = HIGHER_IS_BETTER`, `goodThreshold = 300`, `displayMin = 200`, `displayMax = 1200`. Grounded in the two real web sources: the dashboard `DEFAULT_REFS` fallback (min 200 / threshold 300 / max 1200) and the blood-page target copy "300–1000 ng/dL (men)" (300 = lower bound of the normal male range → the good-side threshold for a higher-is-better marker). |
| Orientation | `HIGHER_IS_BETTER` — same as HDL, so the existing range-bar math (`goodLeftPct` / `goodFillPct`) for this orientation is already exercised on both clients. |
| Enum position | Append `TESTOSTERONE` after `HS_CRP` in both the backend and Android enums. Storage uses the enum **name** (the backend enum's own header comment says so), so ordinal position is irrelevant; appending avoids any ordinal-dependent drift. |
| `Map.of` capacity | `BloodReferenceRanges.RANGES` uses `java.util.Map.of(...)`, whose overloads cap at **10** key/value pairs. 8 → 9 after this change, still fine. **A 10th+ marker must switch the literal to `Map.ofEntries(Map.entry(...), …)`** — add an inline comment so the next author sees it. |
| Request binding | No controller change. `CreateRequest.marker` is typed `BloodMarker`; Jackson maps the JSON string to the enum constant **by name** (uppercase). Adding `TESTOSTERONE` makes `"TESTOSTERONE"` deserialize automatically, and `BloodReadingResponse.from` then resolves its range via `rangeFor(...)`. Android already sends `marker.name` (uppercase) → matches. |
| Android range source | The Android `ReferenceRange` is taken from the wire (`BloodReadingDto.reference`), never hardcoded — so no Android range table changes; the backend supplies the testosterone range on every reading. |
| Android dashboard mapper | No change — `DashboardData.kt` already lists `"TESTOSTERONE"` first and maps its label; it starts resolving once readings exist. |
| Android blood-feature order | Add `TESTOSTERONE` to `MarkerCatalog.DISPLAY_ORDER`, placed **first**, matching the dashboard and the hormone-tracking emphasis. |
| Backwards compatibility | Purely additive. No existing reading/report/document changes shape. No migration. |

## Per-module deliverables

### Backend — `core`

**`backend/core/.../blood/BloodMarker.java`** — append the value:

```java
public enum BloodMarker {
    TOTAL_CHOLESTEROL,
    LDL,
    HDL,
    TRIGLYCERIDES,
    APO_B,
    HBA1C,
    FASTING_GLUCOSE,
    HS_CRP,
    TESTOSTERONE,
}
```

**`backend/core/.../blood/BloodReferenceRanges.java`** — add to the `Map.of(...)`
literal (the type is the nested `Range` record + `Orientation` enum; there is no
separate `ReferenceRange.java`):

```java
BloodMarker.TESTOSTERONE,
    new Range("ng/dL", Orientation.HIGHER_IS_BETTER, 300, 200, 1200)
// NOTE: 9 entries — Map.of caps at 10 pairs. A 10th+ marker must switch this
// literal to Map.ofEntries(Map.entry(...), ...).
```

No change to `Range`, `BloodReading`, `CreateRequest`, `BloodReadingResponse`,
`BloodController`, or the extraction pipeline — they all flow through
`BloodMarker` / `rangeFor(...)` generically.

### Android — `core-domain`

**`android/core-domain/.../domain/blood/BloodModels.kt`** — append `TESTOSTERONE`
to the `BloodMarker` enum (keep name-parity with the backend enum):

```kotlin
enum class BloodMarker {
    TOTAL_CHOLESTEROL,
    LDL,
    HDL,
    TRIGLYCERIDES,
    APO_B,
    HBA1C,
    FASTING_GLUCOSE,
    HS_CRP,
    TESTOSTERONE,
}
```

**`android/core-domain/.../domain/blood/MarkerCatalog.kt`** — add the three
`when` branches and the order entry:
- `displayName(TESTOSTERONE) = "Testosterone"`
- `description(TESTOSTERONE)` — mirror the web copy ("Primary male sex hormone.
  Affects muscle mass, bone density, and energy levels.")
- `target(TESTOSTERONE) = "300–1000 ng/dL (men)"`
- prepend `BloodMarker.TESTOSTERONE` to `DISPLAY_ORDER`.

### Android — `core-data`

No change. `DashboardData.kt`'s `BloodMarkerSummaryMapper.DISPLAY_ORDER` already
contains `"TESTOSTERONE"` and `LABELS` already maps it; it begins resolving once
readings exist.

### Web

No change required for parity (blood page is API-driven). Optional tidy, flagged
below: align the dashboard `DEFAULT_REFS` fallback if desired.

## Tests

### Backend

`backend/core/src/test/.../blood/BloodReferenceRangesTest.java`:
- `rangeFor(TESTOSTERONE)` → non-null; unit `ng/dL`; orientation
  `HIGHER_IS_BETTER`; `goodThreshold == 300`; `displayMin == 200`;
  `displayMax == 1200`.
- Loop over `BloodMarker.values()` asserting **every** value has a non-null range
  (guards `Map.of` from silently missing a future addition).

`backend/api/src/test/.../blood/BloodControllerTest.java` (or existing test):
- `POST /api/me/blood` `{marker:"TESTOSTERONE", value:650, unit:"ng/dL",
  sampleDate:…}` → 201; response `reference.orientation == "HIGHER_IS_BETTER"`,
  `goodThreshold == 300`.
- `GET /api/me/blood` includes the created reading.

### Android

`android/core-domain/src/test/.../blood/MarkerCatalogTest.kt`:
- `displayName(TESTOSTERONE) == "Testosterone"`.
- `DISPLAY_ORDER` contains `TESTOSTERONE` (first), has no duplicates, and covers
  every enum value.

`android/core-data/src/test/.../dashboard/DashboardMapperTest.kt` (extend
existing):
- Given a `TESTOSTERONE` reading (HIGHER_IS_BETTER reference) + an LDL reading,
  `toDashboardMarkers(...)` returns TESTOSTERONE **first**, `tone == Good` when
  value ≥ `goodThreshold`.

## Acceptance criteria

Automated:

1. `./gradlew :core:test :api:test` (backend) passes, incl. the new assertions.
2. `./gradlew :core-domain:test :core-data:test` (android) passes.
3. `./gradlew :app:assembleDebug` (android) still builds.

Manual (against a dev backend):

4. **Manual entry.** Android Blood → Add reading → Testosterone, 650 ng/dL,
   today → 201. Appears on the Testosterone marker detail and the dashboard
   `BloodPanel` (first slot) with a higher-is-better bar: good zone from the 300
   threshold (`goodLeftPct = (300−200)/(1200−200) = 0.1`) rightward; tick at
   `(650−200)/(1200−200) = 0.45`.
5. **Lab extraction.** Upload a lab PDF with a Testosterone row → report detail
   shows it under **known** markers, not "Other markers". (If still "Other", add
   "Testosterone" + synonyms to the extractor's canonical-name list — fast follow.)
6. **Parity.** Web `/me/blood` Testosterone column and the Android panel show the
   same value + range-bar geometry (both read the range from the API).

## Open questions

Resolved:
- **Range values** — `ng/dL`, HIGHER_IS_BETTER, `goodThreshold 300 / displayMin
  200 / displayMax 1200`, from the web dashboard fallback + the "300–1000 (men)"
  target copy.
- **Enum position** — append (storage uses the name; ordinal irrelevant).
- **Request binding** — no controller change; the `BloodMarker`-typed request
  field binds the new constant by name automatically.

Optional (web, out of scope here):
- The web **dashboard** keeps a local `DEFAULT_REFS` fallback for testosterone
  (200/300/1200) used only when the API omits a range. Now that the API supplies
  the range, that fallback is rarely hit; to avoid drift, consider having the web
  dashboard read the range from the API (as the blood page and Android do), or
  keep its fallback numerically in sync with the backend. Flag for the web owner.

Deferred:
- **Sex-specific ranges.** Male/female testosterone ranges differ ~3–5×; this
  spec uses the male range (matching the web). Sex-awareness needs `Profile.sex`
  + a `rangeFor(marker, sex)` change across all markers — a separate IMPL. Until
  then female users see a male-scaled bar (the target copy already notes the
  female range textually).
- **Unit flexibility (`ng/dL` vs `nmol/L`).** Stored/displayed verbatim; no
  conversion. Revisit only if a lab reports nmol/L unconverted.
- **Extraction canonical-name list.** If Acceptance #5 shows "Other markers", add
  "Testosterone" / "Total testosterone" / "Test" to the extractor mapping.

## Dependencies

- **None blocking.** Purely additive to the existing blood domain
  (IMPL-04 / IMPL-AND-04). No migration, no other IMPL required.
