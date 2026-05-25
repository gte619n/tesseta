# IMPL-GYM-002: Bulk Equipment Import with AI Parsing

## Goal

Allow users to paste a raw text list of gym equipment and have Gemini 3.5 Flash
parse it into structured equipment data. The system fuzzy-matches against the
existing equipment catalog and creates new equipment submissions for unmatched
items. This enables rapid gym setup by importing equipment lists from gym
websites, photos, or manual inventories.

Additionally, add a `WEIGHT_SET` spec schema to properly model dumbbell racks
and barbell sets with weight ranges—critical for determining available exercises.

## Scope

In scope:

- Add `WEIGHT_SET` to `SpecSchema` enum with fields: `minWeight`, `maxWeight`,
  `increment`, and optional `weights` array for irregular sets.
- `EquipmentParserService` in integrations module using Gemini 3.5 Flash to
  parse raw text into structured `ParsedEquipment` records.
- `BulkImportService` in core module that orchestrates parsing + catalog matching.
- Fuzzy matching algorithm using Jaccard similarity (no external dependencies).
- Two-phase API: preview (parse + match) and confirm (create + add to location).
- REST endpoints at `POST /api/me/gyms/{locationId}/equipment/import/preview`
  and `POST /api/me/gyms/{locationId}/equipment/import/confirm`.
- Frontend `EquipmentImportModal` component with paste textarea, preview table,
  action selectors, and confirm flow.
- Integration with existing equipment submission flow (new items get
  `status=PENDING_REVIEW`).

Out of scope (deferred):

- Admin bulk approval UI for imported equipment.
- Image generation for bulk-imported items (existing async flow applies).
- OCR from photos of gym equipment lists.
- Equipment deduplication across users' submissions.

## Decisions

| Topic | Decision |
|---|---|
| AI model | Gemini 3.5 Flash (`gemini-2.0-flash`) via existing `GEMINI_API_KEY`. Fast, cost-effective for structured extraction. |
| Prompt strategy | System prompt defines exact JSON array schema with all categories, subcategories, and spec schemas. Request returns JSON only, no prose. |
| JSON parsing | Manual markdown fence cleanup + Jackson ObjectMapper (follows `DrugLookupService` pattern). |
| Matching algorithm | Jaccard similarity on normalized word tokens. Category/subcategory boost. Thresholds: ≥0.85 auto-match, 0.6-0.84 suggested, <0.6 create new. |
| No external libs | Fuzzy matching implemented with standard Java collections—no Lucene, no FuzzyWuzzy. |
| Two-phase flow | Preview shows matches before committing. User can override names, skip items, or accept suggestions. |
| Brand handling | Brands extracted but ignored for matching. Canonical names like "Treadmill" match regardless of "Matrix Treadmill" input. |
| WEIGHT_SET schema | New spec schema for dumbbell/barbell sets. Captures weight range or explicit weight list. |
| Confidence markers | Input text like "[Certain]" or "[Likely]" parsed and preserved for user reference but not used in matching. |

## Data Model Changes

### SpecSchema Enum Addition

```java
public enum SpecSchema {
    SELECTORIZED,   // Pin-select machines: minWeight, maxWeight, increment
    PLATE_LOADED,   // Barbells, smith: barWeight, availablePlates[]
    BODYWEIGHT,     // Pull-ups, dips: (no specs)
    CABLE,          // Cable machines: weightStack, numStations
    CARDIO,         // Treadmill, bike: resistanceLevels, hasIncline
    WEIGHT_SET      // NEW: Dumbbell racks, EZ bars: minWeight, maxWeight, increment, weights[]
}
```

### WEIGHT_SET Specs

```json
{
  "minWeight": 5,
  "maxWeight": 100,
  "increment": 5,
  "weights": [20, 30, 40, 50, 60, 70, 80, 90, 100, 110],
  "notes": "with storage rack"
}
```

- `minWeight`/`maxWeight`/`increment` for regular progressions (5-100 lbs by 5s)
- `weights` array for irregular sets (EZ curl bars at specific weights)
- Both can be present; UI shows whichever is more specific

### ParsedEquipment Record

```java
public record ParsedEquipment(
    String name,           // "Hampton Round Dumbbells"
    String brand,          // "Hampton" (extracted but not used for matching)
    String category,       // "Free Weights"
    String subcategory,    // "Dumbbells"
    SpecSchema specSchema, // WEIGHT_SET
    Map<String, Object> specs,
    String confidence,     // "CERTAIN", "LIKELY", "UNCERTAIN"
    String rawText         // Original line from input
) {}
```

## API Contracts

### POST /api/me/gyms/{locationId}/equipment/import/preview

Request:
```json
{
  "rawText": "Matrix treadmills [Certain].\nHampton round dumbbells up to 100 lbs [Likely].\n..."
}
```

Response:
```json
{
  "items": [
    {
      "index": 0,
      "parsed": {
        "name": "Matrix Treadmill",
        "brand": "Matrix",
        "category": "Machines - Cardio",
        "subcategory": "Treadmill",
        "specSchema": "CARDIO",
        "specs": {},
        "confidence": "CERTAIN",
        "rawText": "Matrix treadmills [Certain]."
      },
      "match": {
        "equipmentId": "eq_abc123",
        "name": "Treadmill",
        "score": 0.87,
        "reason": "fuzzy"
      },
      "action": "MATCH_SUGGESTED"
    },
    {
      "index": 1,
      "parsed": {
        "name": "Hampton Round Dumbbells",
        "category": "Free Weights",
        "subcategory": "Dumbbells",
        "specSchema": "WEIGHT_SET",
        "specs": { "minWeight": 5, "maxWeight": 100, "increment": 5 }
      },
      "match": null,
      "action": "CREATE_NEW"
    }
  ],
  "summary": {
    "total": 7,
    "matched": 3,
    "suggestedMatches": 2,
    "newSubmissions": 2
  }
}
```

Action types:
- `MATCH_AUTO` — High confidence match (≥0.85), will use catalog item
- `MATCH_SUGGESTED` — Medium confidence (0.6-0.84), user should confirm
- `CREATE_NEW` — No match found, will create new submission

### POST /api/me/gyms/{locationId}/equipment/import/confirm

Request:
```json
{
  "items": [
    { "index": 0, "action": "USE_MATCH", "matchedEquipmentId": "eq_abc123" },
    { "index": 1, "action": "CREATE_NEW", "overrides": { "name": "Dumbbells (5-100 lbs)" } },
    { "index": 2, "action": "SKIP" }
  ]
}
```

Response:
```json
{
  "created": [
    { "equipmentId": "eq_new123", "name": "Dumbbells (5-100 lbs)", "status": "PENDING_REVIEW" }
  ],
  "matched": [
    { "equipmentId": "eq_abc123", "name": "Treadmill" }
  ],
  "addedToLocation": 2,
  "skipped": 1
}
```

## Implementation Phases

### Phase 1: Core Types

| File | Action | Description |
|------|--------|-------------|
| `backend/core/.../equipment/SpecSchema.java` | Modify | Add `WEIGHT_SET` |
| `backend/core/.../equipment/ParsedEquipment.java` | Create | Parsed item record |
| `web/lib/types/gym.ts` | Modify | Add WEIGHT_SET to TypeScript |

### Phase 2: Gemini Parser Service

| File | Action | Description |
|------|--------|-------------|
| `backend/integrations/.../equipment/EquipmentParserService.java` | Create | Gemini parsing logic |
| `backend/integrations/.../equipment/EquipmentProperties.java` | Create | Config properties |
| `backend/app/src/main/resources/application.yml` | Modify | Add equipment gemini config |

### Phase 3: Bulk Import Service

| File | Action | Description |
|------|--------|-------------|
| `backend/core/.../equipment/BulkImportService.java` | Create | Orchestration + fuzzy matching |

### Phase 4: API Layer

| File | Action | Description |
|------|--------|-------------|
| `backend/api/.../equipment/BulkImportController.java` | Create | REST endpoints |
| `backend/api/.../equipment/BulkImportRequest.java` | Create | Request DTOs |
| `backend/api/.../equipment/BulkImportPreviewResponse.java` | Create | Preview response |
| `backend/api/.../equipment/BulkImportConfirmRequest.java` | Create | Confirm request |

### Phase 5: Image Service Update

| File | Action | Description |
|------|--------|-------------|
| `backend/integrations/.../equipment/EquipmentImageService.java` | Modify | Handle WEIGHT_SET in prompts |

### Phase 6: Frontend

| File | Action | Description |
|------|--------|-------------|
| `web/lib/gym-api.ts` | Modify | Add bulkImportPreview/Confirm functions |
| `web/components/gym/EquipmentImportModal.tsx` | Create | Import UI modal |
| `web/components/gym/LocationEquipmentSection.tsx` | Modify | Add "Import List" button |

## Gemini Prompt

```
You are a gym equipment classification assistant. Parse the user-provided list
of gym equipment into structured JSON.

For each item, extract:
- name: Canonical equipment name (e.g., "Treadmill", "Dumbbells", "Leg Press")
- brand: Manufacturer if mentioned (or null)
- category: One of [Free Weights, Machines - Strength, Machines - Cardio,
  Cable Systems, Benches & Racks, Bodyweight, Accessories]
- subcategory: Appropriate subcategory for the category
- specSchema: SELECTORIZED | PLATE_LOADED | BODYWEIGHT | CABLE | CARDIO | WEIGHT_SET
- specs: Weight ranges, counts, features as applicable
- confidence: CERTAIN | LIKELY | UNCERTAIN (from input markers or inferred)
- rawText: The original line

For WEIGHT_SET (dumbbells, barbell sets, EZ bars):
- minWeight, maxWeight, increment for regular progressions
- weights array for specific weight listings

Return ONLY a JSON array. No prose, no code fences.
```

## Verification

1. Start dev servers: `bash infra/scripts/dev.sh`
2. Navigate to an existing gym location
3. Click "Import List" button
4. Paste sample equipment list:
   ```
   Matrix treadmills [Certain].
   Hampton round dumbbells ranging up to 100 lbs [Likely].
   TKO EZ curl barbells 20, 30, 40, 50, 60, 70, 80, 90, 100, 110 lbs [Certain].
   Matrix functional trainer cable machine [Certain].
   ```
5. Verify preview shows:
   - Treadmill matched to catalog (or suggested match)
   - Dumbbells with WEIGHT_SET specs (5-100 lbs)
   - EZ bars with specific weights array
   - Cable machine categorized correctly
6. Confirm import
7. Verify equipment appears in gym's equipment list
8. Check Firestore: new submissions have `status=PENDING_REVIEW`

## Status

- [ ] Planning
- [ ] In Progress
- [ ] Complete
