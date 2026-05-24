# IMPL-05: Medications Tracking — Full-Stack Implementation

## Goal

Build a comprehensive medications tracking system that allows users to manage
their current and historical medications, supplements, and OTC drugs with
AI-generated imagery, dose logging, adherence tracking, and integration with
blood markers and the unified timeline.

This feature supports the typical TRT/longevity user managing 5-15 concurrent
medications across prescriptions, supplements, peptides, and OTC drugs.

---

## Discovery Interview Summary

The following decisions were made during the requirements gathering phase:

| Topic | Decision |
|-------|----------|
| Categories | All: Prescriptions, supplements, peptides, OTC, topicals |
| Scheduling | Hybrid: simple frequency + advanced mode for cycles/protocols |
| Adherence | Binary dose logging with timestamp |
| Image generation | Gemini 2.5 per-drug, web search for physical form classification |
| Image storage | GCS bucket with CDN, fallback to generic form images |
| Drug catalog | Shared system-owned catalog, user customizes dose/schedule |
| History tracking | Full timeline with discontinuation reason |
| Dose changes | Edit current, log history (version trail) |
| Integration | Dashboard "Today's Doses" card + blood marker correlation |
| Blood correlation | AI-suggested relevant markers per medication |
| Protocols | Named grouping only (V1), no coordinated scheduling |
| List UI | Card grid with images (2-3 columns) |
| Past meds | Separate tab/section |
| Navigation | Sidebar nav item "Meds", route `/me/meds` |
| Required fields | Name + Dose + Frequency |
| Units | Flexible with smart defaults (mg, mcg, IU, ml, units) |
| Time windows | Fixed defaults (morning=6-10am, afternoon=12-3pm, evening=5-8pm, bedtime=9-11pm) |
| PRN meds | "As needed" as frequency option, shows in list, not in Today's doses |
| Split dosing | Multiple time slots with different doses per slot |
| Sparkline range | 30 days rolling |
| Import | Manual entry only for V1 |
| Supply tracking | Out of scope for V1 |
| Safety/interactions | Out of scope, explicit disclaimer |
| Mobile | Desktop-focused, Android app handles mobile |
| Backend | Spring Boot owns all business logic, Next.js proxies |

---

## Scope

### In Scope

**Core Medication Management:**
- Add medication via smart search (web lookup + AI classification)
- Edit medication (dose, frequency, schedule, notes)
- Discontinue medication (with reason: completed, side effects, switched, other)
- Delete medication (with confirmation)
- View current medications as card grid with AI-generated images
- View discontinued medications in separate History tab
- Dose change history tracking (version trail)

**Scheduling:**
- Simple frequency: X times per day/week/month
- Specific days: Mon/Wed/Fri, weekends only, etc.
- Time windows: Morning, Afternoon, Evening, Bedtime
- PRN (as needed) frequency option
- Split dosing: multiple time slots with different doses
- Advanced: Cycling protocols (X weeks on, Y weeks off)
- Advanced: Named protocol grouping (e.g., "TRT Stack")

**Adherence Tracking:**
- Binary dose logging (tap to mark taken with timestamp)
- 30-day adherence sparklines on medication cards
- Today's doses list with checkboxes

**AI Image Generation:**
- Web search to determine drug physical form (tablet, capsule, vial, etc.)
- Gemini 2.5 image generation using photography prompt system
- GCS storage with CDN serving
- Fallback to generic form images on generation failure

**Shared Drug Catalog:**
- System-owned canonical drug entries
- User references catalog entry, customizes dose/schedule
- Auto-suggest from catalog when adding medications
- First user to add a new drug creates the catalog entry

**Dashboard Integration:**
- "Today's Doses" card on dashboard
- Mark taken directly from card
- Link to full medications page

**Blood Marker Correlation:**
- AI-suggested relevant markers when adding medication
- Medication start/stop events as vertical markers on blood trend charts
- Unified timeline feed integration

### Out of Scope (V1)

- Refill/supply tracking
- Drug interaction warnings
- Prescription image OCR import
- CSV or Apple Health import
- Push notifications / reminders
- Pharmacy API integration
- Coordinated protocol scheduling (relationships between drugs)
- Android-specific implementation (separate IMPL)

---

## Data Model

### Firestore Collections

```
# Shared drug catalog (system-owned)
drugs/{drugId}
  ├── name: string                    # "Testosterone Cypionate"
  ├── aliases: string[]               # ["Test Cyp", "Depo-Testosterone"]
  ├── category: enum                  # PRESCRIPTION | SUPPLEMENT | OTC | PEPTIDE | TOPICAL
  ├── form: enum                      # INJECTABLE_VIAL | TABLET | CAPSULE | SOFTGEL | CREAM | PATCH | LIQUID | POWDER
  ├── defaultUnit: string             # "mg" | "mcg" | "IU" | "ml"
  ├── commonDoses: string[]           # ["100mg", "200mg"]
  ├── imageUrl: string                # GCS CDN URL
  ├── imageFallback: string           # Generic form image URL
  ├── suggestedMarkers: string[]      # ["TESTOSTERONE", "FREE_T", "ESTRADIOL"]
  ├── createdAt: timestamp
  └── updatedAt: timestamp

# User's medication instances
users/{userId}/medications/{medicationId}
  ├── drugId: string                  # Reference to drugs/{drugId}
  ├── customName: string?             # Override name if needed
  ├── status: enum                    # ACTIVE | DISCONTINUED
  ├── dose: number                    # 200
  ├── unit: string                    # "mg"
  ├── frequency: object
  │     ├── type: enum                # DAILY | WEEKLY | MONTHLY | PRN | CYCLE
  │     ├── timesPerPeriod: number?   # 2 (for 2x daily)
  │     ├── specificDays: string[]?   # ["MON", "WED", "FRI"]
  │     └── cycle: object?            # { onWeeks: 4, offWeeks: 2, startDate }
  ├── timeSlots: object[]             # [{ window: "MORNING", dose: 100 }, { window: "EVENING", dose: 100 }]
  ├── protocolId: string?             # Reference to protocol grouping
  ├── notes: string?
  ├── prescribedBy: string?           # Doctor name
  ├── startDate: timestamp
  ├── endDate: timestamp?             # Set when discontinued
  ├── discontinueReason: enum?        # COMPLETED | SIDE_EFFECTS | SWITCHED | COST | OTHER
  ├── discontinueNotes: string?
  ├── correlatedMarkers: string[]     # Blood markers to show on charts
  ├── createdAt: timestamp
  └── updatedAt: timestamp

# Medication version history (dose changes)
users/{userId}/medications/{medicationId}/history/{historyId}
  ├── changeType: enum                # DOSE_CHANGE | FREQUENCY_CHANGE | SCHEDULE_CHANGE
  ├── previousValue: object           # { dose: 100, unit: "mg" }
  ├── newValue: object                # { dose: 200, unit: "mg" }
  ├── changedAt: timestamp
  └── notes: string?

# Adherence logs
users/{userId}/medications/{medicationId}/adherence/{date}
  ├── date: string                    # "2026-05-23"
  ├── doses: object[]                 # [{ window: "MORNING", takenAt: timestamp, dose: 100 }]
  └── notes: string?

# Named protocols (groupings)
users/{userId}/protocols/{protocolId}
  ├── name: string                    # "TRT Stack"
  ├── description: string?
  ├── medicationIds: string[]         # References to medications in this protocol
  ├── createdAt: timestamp
  └── updatedAt: timestamp
```

### Firestore Indexes

```
# Composite indexes for common queries
users/{userId}/medications:
  - status ASC, startDate DESC        # Active medications by start date
  - status ASC, endDate DESC          # Discontinued by end date
  - protocolId ASC, status ASC        # Medications by protocol

users/{userId}/medications/{medicationId}/adherence:
  - date DESC                         # Recent adherence first

drugs:
  - name ASC                          # Catalog search
  - category ASC, name ASC            # Filter by category
```

### TypeScript Types

```typescript
// Enums
type DrugCategory = "PRESCRIPTION" | "SUPPLEMENT" | "OTC" | "PEPTIDE" | "TOPICAL";
type DrugForm = "INJECTABLE_VIAL" | "TABLET" | "CAPSULE" | "SOFTGEL" | "CREAM" | "PATCH" | "LIQUID" | "POWDER";
type MedicationStatus = "ACTIVE" | "DISCONTINUED";
type FrequencyType = "DAILY" | "WEEKLY" | "MONTHLY" | "PRN" | "CYCLE";
type TimeWindow = "MORNING" | "AFTERNOON" | "EVENING" | "BEDTIME";
type DiscontinueReason = "COMPLETED" | "SIDE_EFFECTS" | "SWITCHED" | "COST" | "OTHER";
type DayOfWeek = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";

// Core types
interface Drug {
  drugId: string;
  name: string;
  aliases: string[];
  category: DrugCategory;
  form: DrugForm;
  defaultUnit: string;
  commonDoses: string[];
  imageUrl: string;
  imageFallback: string;
  suggestedMarkers: string[];
}

interface Medication {
  medicationId: string;
  drugId: string;
  drug?: Drug;                        // Populated on read
  customName?: string;
  status: MedicationStatus;
  dose: number;
  unit: string;
  frequency: FrequencyConfig;
  timeSlots: TimeSlot[];
  protocolId?: string;
  notes?: string;
  prescribedBy?: string;
  startDate: string;                  // ISO date
  endDate?: string;
  discontinueReason?: DiscontinueReason;
  discontinueNotes?: string;
  correlatedMarkers: string[];
  adherence?: AdherenceSummary;       // Populated with 30-day stats
}

interface FrequencyConfig {
  type: FrequencyType;
  timesPerPeriod?: number;
  specificDays?: DayOfWeek[];
  cycle?: { onWeeks: number; offWeeks: number; startDate: string };
}

interface TimeSlot {
  window: TimeWindow;
  dose: number;
}

interface AdherenceSummary {
  last30Days: { date: string; taken: boolean }[];
  percentage: number;
}

interface TodaysDose {
  medicationId: string;
  medication: Medication;
  window: TimeWindow;
  dose: number;
  unit: string;
  taken: boolean;
  takenAt?: string;
}
```

---

## API Endpoints

### Backend (Spring Boot)

```
# Drug Catalog
GET    /api/drugs                     # List/search catalog
GET    /api/drugs/{drugId}            # Get drug details
POST   /api/drugs                     # Create drug (triggers image gen)
PUT    /api/drugs/{drugId}            # Update drug

# User Medications
GET    /api/me/medications            # List user's medications (with adherence summary)
GET    /api/me/medications/{id}       # Get medication details with full history
POST   /api/me/medications            # Add medication
PUT    /api/me/medications/{id}       # Update medication (creates history entry if dose changed)
DELETE /api/me/medications/{id}       # Hard delete medication
POST   /api/me/medications/{id}/discontinue  # Discontinue with reason

# Adherence
GET    /api/me/medications/{id}/adherence?from=&to=  # Get adherence logs
POST   /api/me/medications/{id}/adherence            # Log dose taken
DELETE /api/me/medications/{id}/adherence/{date}/{window}  # Undo dose log

# Today's View
GET    /api/me/medications/today      # Get today's doses with status

# Protocols
GET    /api/me/protocols              # List protocols
POST   /api/me/protocols              # Create protocol
PUT    /api/me/protocols/{id}         # Update protocol
DELETE /api/me/protocols/{id}         # Delete protocol

# Drug Lookup (for add flow)
POST   /api/drugs/lookup              # Web search + AI classification
       Body: { query: "testosterone cypionate" }
       Returns: { name, category, form, commonDoses, suggestedMarkers }

# Image Generation
POST   /api/drugs/{drugId}/generate-image  # Trigger Gemini image generation
```

### Next.js API Routes (Proxies)

```
/api/meds/                   → proxy to /api/me/medications
/api/meds/[id]/              → proxy to /api/me/medications/{id}
/api/meds/[id]/adherence/    → proxy to /api/me/medications/{id}/adherence
/api/meds/today/             → proxy to /api/me/medications/today
/api/drugs/                  → proxy to /api/drugs
/api/drugs/lookup/           → proxy to /api/drugs/lookup
/api/protocols/              → proxy to /api/me/protocols
```

---

## UI Components

### Page Structure

```
web/app/me/meds/
  ├── page.tsx                        # Main medications page (Server Component)
  └── loading.tsx                     # Suspense fallback

web/components/medications/
  ├── MedicationCard.tsx              # Card with image, dose, adherence sparkline
  ├── MedicationGrid.tsx              # 2-3 column responsive grid
  ├── AddMedicationButton.tsx         # Opens add flow
  ├── AddMedicationFlow.tsx           # Smart search + form
  ├── MedicationDetail.tsx            # Expanded view with history
  ├── EditMedicationForm.tsx          # Edit form
  ├── DiscontinueDialog.tsx           # Discontinue with reason
  ├── AdherenceSparkline.tsx          # 30-day tick marks
  ├── TimeSlotEditor.tsx              # Configure time slots with doses
  ├── FrequencySelector.tsx           # Simple/advanced frequency picker
  ├── ProtocolBadge.tsx               # Shows protocol membership
  └── DrugImage.tsx                   # Image with fallback handling

web/components/dashboard/
  ├── TodaysDosesCard.tsx             # Dashboard card with checkboxes
  └── ... (existing components)
```

### Component Details

**MedicationCard.tsx**
- Square card with drug image (1:1 aspect ratio)
- Drug name (primary text)
- Dose + frequency (secondary text)
- 30-day adherence sparkline (tick marks)
- Protocol badge if in a protocol
- Click to expand detail view

**TodaysDosesCard.tsx**
- List of doses due today
- Each row: drug name, dose, time window, checkbox
- Mark taken directly with single tap
- "View all" link to /me/meds
- Empty state if no scheduled doses

**AddMedicationFlow.tsx**
- Step 1: Type drug name (autocomplete from catalog)
- Step 2: If not found, create new (web search + AI classification)
- Step 3: Fill form with auto-populated values
- Step 4: Confirm and generate image (async)

---

## Image Generation Pipeline

### Flow

```
1. User types drug name
2. Backend: POST /api/drugs/lookup
   - Web search for drug information
   - AI (Claude) extracts: name, category, form, appearance, common doses
   - Check if drug exists in catalog
   - If exists: return existing entry
   - If not: return classification result

3. User confirms/edits and saves
4. Backend: POST /api/drugs (if new drug)
   - Save drug metadata to Firestore
   - Trigger async image generation

5. Backend: Image generation worker
   - Build prompt from photography-prompts.md template
   - Call Gemini 2.5 image generation API
   - On success: upload to GCS, update drug.imageUrl
   - On failure: use generic fallback image for form type

6. Frontend: Poll or SSE for image readiness
   - Show placeholder while generating
   - Swap to final image when ready
```

### Prompt Construction

Per `docs/photography-prompts.md`, build prompts as:

```
[SHARED TREATMENT BLOCK] + [FRAMING CLAUSE] + [FINISH CLAUSE] + [PRODUCT DESCRIPTION]
```

Example for testosterone cypionate:
```
Warm neutral seamless background, oatmeal color, hex F0EBE0 or a hair
lighter. Soft diffuse daylight from a single direction, large soft
source, gentle realistic shadows, no hard flash, no studio specular
hotspots. Muted natural color, slightly desaturated. Matte finish, no
glossy advertising sheen. Subject centered with generous negative
space. Photographic realism, full-frame camera look, 50mm to 85mm
equivalent lens, moderate depth of field. No text, no graphics, no
logos, no props beyond what is specified. Quiet, editorial,
instrument-like mood — a precision-tool catalog, not a supplement ad.

A single medical product, isolated, centered, macro or near-macro so
it reads clearly at small sizes, the product sharp with a shallow
depth of field, a soft realistic contact shadow.

Clean pharmaceutical-grade realism, matte, no advertising gloss. The
label is blank, plain white, or absent. No printed text of any kind.

The product is: a small clear glass pharmaceutical vial with a metallic
crimp cap and a blank white label, containing a pale amber oil.
```

### Fallback Images

Pre-generate and store in GCS:
- `fallback-injectable-vial.png`
- `fallback-tablet.png`
- `fallback-capsule.png`
- `fallback-softgel.png`
- `fallback-cream.png`
- `fallback-patch.png`
- `fallback-liquid.png`
- `fallback-powder.png`

---

## Blood Marker Correlation

### AI-Suggested Markers

When adding a medication, call AI to suggest relevant blood markers:

```
POST /api/drugs/lookup
Response includes:
{
  "suggestedMarkers": ["TESTOSTERONE", "FREE_TESTOSTERONE", "ESTRADIOL", "HEMATOCRIT"]
}
```

Common mappings (AI will learn/suggest):
- Testosterone → Total T, Free T, Estradiol, SHBG, Hematocrit
- Metformin → HbA1c, Fasting Glucose, Insulin
- Statins → LDL, HDL, Total Cholesterol, Triglycerides
- Thyroid meds → TSH, Free T4, Free T3
- Finasteride → DHT, PSA

### Chart Integration

On blood marker trend charts (`/me/blood`), render medication events:
- Vertical dashed line at medication start date
- Vertical dashed line at discontinue date (if applicable)
- Hover tooltip: "Started Testosterone 200mg"

---

## Development Phases

### Phase 1: Data Model & Backend Core
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Define Firestore schema for drugs, medications, adherence | [ ] | [ ] | [ ] |
| Create domain models in `backend/core` | [ ] | [ ] | [ ] |
| Create repository interfaces | [ ] | [ ] | [ ] |
| Implement Firestore repositories in `backend/persistence` | [ ] | [ ] | [ ] |
| Add composite indexes to Firestore | [ ] | [ ] | [ ] |
| Unit tests for domain logic | [ ] | [ ] | [ ] |

**Verification:**
- [ ] All repository methods have unit tests
- [ ] Firestore emulator tests pass
- [ ] Domain model validates correctly

### Phase 2: Basic CRUD API
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement `GET /api/me/medications` | [ ] | [ ] | [ ] |
| Implement `POST /api/me/medications` | [ ] | [ ] | [ ] |
| Implement `PUT /api/me/medications/{id}` | [ ] | [ ] | [ ] |
| Implement `DELETE /api/me/medications/{id}` | [ ] | [ ] | [ ] |
| Implement `POST /api/me/medications/{id}/discontinue` | [ ] | [ ] | [ ] |
| Implement dose history tracking on update | [ ] | [ ] | [ ] |
| API integration tests | [ ] | [ ] | [ ] |

**Verification:**
- [ ] All endpoints return correct status codes
- [ ] Dose changes create history entries
- [ ] Discontinue sets endDate and reason
- [ ] Integration tests with Firestore emulator pass

### Phase 3: Drug Catalog & Lookup
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement `GET /api/drugs` with search | [ ] | [ ] | [ ] |
| Implement `POST /api/drugs/lookup` (web search + AI) | [ ] | [ ] | [ ] |
| Implement `POST /api/drugs` (create catalog entry) | [ ] | [ ] | [ ] |
| Configure web search integration | [ ] | [ ] | [ ] |
| Configure Claude API for drug classification | [ ] | [ ] | [ ] |
| Test with TRT medications (testosterone, HCG, anastrozole) | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Web search returns relevant drug information
- [ ] AI correctly classifies drug form and category
- [ ] Catalog deduplication works (same drug not created twice)
- [ ] Manual test: add testosterone cypionate, HCG, anastrozole

### Phase 4: Image Generation Pipeline
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Set up GCS bucket for medication images | [ ] | [ ] | [ ] |
| Configure CDN for GCS bucket | [ ] | [ ] | [ ] |
| Implement Gemini 2.5 image generation service | [ ] | [ ] | [ ] |
| Build prompt from photography guide template | [ ] | [ ] | [ ] |
| Implement async image generation on drug create | [ ] | [ ] | [ ] |
| Implement fallback to generic form images | [ ] | [ ] | [ ] |
| Create pre-generated fallback images for each form | [ ] | [ ] | [ ] |
| Test content filter bypass with "still life" reframe | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Generated images match photography guide aesthetic
- [ ] Images stored in GCS and accessible via CDN
- [ ] Fallback images used when generation fails
- [ ] Manual test: generate images for vial, tablet, capsule

### Phase 5: Adherence Tracking
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement `POST /api/me/medications/{id}/adherence` | [ ] | [ ] | [ ] |
| Implement `GET /api/me/medications/{id}/adherence` | [ ] | [ ] | [ ] |
| Implement `DELETE /api/me/medications/{id}/adherence/{date}/{window}` | [ ] | [ ] | [ ] |
| Implement `GET /api/me/medications/today` | [ ] | [ ] | [ ] |
| Calculate 30-day adherence percentage | [ ] | [ ] | [ ] |
| Include adherence summary in medication list response | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Dose logging creates correct Firestore documents
- [ ] Today's doses correctly computed from schedules
- [ ] PRN medications excluded from Today's doses
- [ ] Adherence percentage calculation accurate

### Phase 6: Frontend - Medications Page
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Create `/me/meds/page.tsx` (Server Component) | [ ] | [ ] | [ ] |
| Implement MedicationGrid component | [ ] | [ ] | [ ] |
| Implement MedicationCard component | [ ] | [ ] | [ ] |
| Implement AdherenceSparkline component | [ ] | [ ] | [ ] |
| Implement DrugImage with fallback | [ ] | [ ] | [ ] |
| Add Current/History tabs | [ ] | [ ] | [ ] |
| Add "Meds" to sidebar navigation | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Page loads medications from API
- [ ] Card grid displays correctly (2-3 columns responsive)
- [ ] Adherence sparklines render 30-day data
- [ ] Images load with fallback on error
- [ ] Tab switching works (Current vs History)

### Phase 7: Frontend - Add Medication Flow
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement AddMedicationButton | [ ] | [ ] | [ ] |
| Implement smart search with autocomplete | [ ] | [ ] | [ ] |
| Implement AddMedicationFlow (multi-step) | [ ] | [ ] | [ ] |
| Implement FrequencySelector component | [ ] | [ ] | [ ] |
| Implement TimeSlotEditor component | [ ] | [ ] | [ ] |
| Handle new drug creation with image generation | [ ] | [ ] | [ ] |
| Show image generation progress/placeholder | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Autocomplete shows catalog matches
- [ ] New drug triggers web search + classification
- [ ] Form pre-fills with AI-suggested values
- [ ] Image generation feedback visible to user
- [ ] Manual test: add testosterone, HCG, anastrozole end-to-end

### Phase 8: Frontend - Edit/Discontinue/Delete
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement EditMedicationForm | [ ] | [ ] | [ ] |
| Implement MedicationDetail view with history | [ ] | [ ] | [ ] |
| Implement DiscontinueDialog with reason picker | [ ] | [ ] | [ ] |
| Implement delete with confirmation | [ ] | [ ] | [ ] |
| Show dose change history in detail view | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Edit updates medication correctly
- [ ] Dose change creates history entry
- [ ] Discontinue moves to History tab
- [ ] Delete removes with confirmation
- [ ] History shows change timeline

### Phase 9: Dashboard Integration
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement TodaysDosesCard component | [ ] | [ ] | [ ] |
| Add card to dashboard page | [ ] | [ ] | [ ] |
| Implement mark-taken action on card | [ ] | [ ] | [ ] |
| Style to match existing dashboard cards | [ ] | [ ] | [ ] |
| Handle empty state (no doses today) | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Card shows today's scheduled doses
- [ ] Checkboxes work to mark taken
- [ ] PRN medications not shown in Today's card
- [ ] Empty state displays correctly
- [ ] Card matches design system

### Phase 10: Blood Marker Correlation
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Store correlatedMarkers on medication | [ ] | [ ] | [ ] |
| AI suggests markers during add flow | [ ] | [ ] | [ ] |
| Expose medication events API for blood page | [ ] | [ ] | [ ] |
| Add vertical markers to blood trend charts | [ ] | [ ] | [ ] |
| Add medication events to timeline feed | [ ] | [ ] | [ ] |

**Verification:**
- [ ] AI suggests relevant markers for TRT meds
- [ ] Blood page shows medication start/stop lines
- [ ] Timeline shows "Started Testosterone" events
- [ ] Hover tooltips on chart markers work

### Phase 11: Protocol Grouping
**Status:** [ ] Not Started

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Implement protocol CRUD API | [ ] | [ ] | [ ] |
| Add protocol assignment to medication | [ ] | [ ] | [ ] |
| Implement ProtocolBadge component | [ ] | [ ] | [ ] |
| Group medications by protocol in UI | [ ] | [ ] | [ ] |

**Verification:**
- [ ] Can create "TRT Stack" protocol
- [ ] Can assign medications to protocol
- [ ] Protocol badge displays on cards
- [ ] Filter/group by protocol works

---

## Testing Strategy

### Unit Tests (Backend)

```
backend/core/src/test/
  └── medications/
      ├── MedicationTest.java         # Domain model validation
      ├── FrequencyCalculatorTest.java # Schedule computation
      └── AdherenceCalculatorTest.java # Percentage calculation

backend/persistence/src/test/
  └── medications/
      ├── MedicationRepositoryTest.java
      └── DrugCatalogRepositoryTest.java
```

### Integration Tests (Backend)

```
backend/api/src/test/
  └── medications/
      ├── MedicationControllerIT.java  # Full API tests with Firestore emulator
      ├── DrugLookupIT.java            # Web search + AI classification
      └── ImageGenerationIT.java       # Gemini image gen (mocked)
```

### Frontend Tests

```
web/__tests__/
  └── medications/
      ├── MedicationCard.test.tsx
      ├── AddMedicationFlow.test.tsx
      ├── AdherenceSparkline.test.tsx
      └── TodaysDosesCard.test.tsx
```

### Manual Test Cases

| ID | Description | Steps | Expected | Pass |
|----|-------------|-------|----------|------|
| M1 | Add testosterone cypionate | 1. Click Add, 2. Type "testosterone cypionate", 3. Select from results, 4. Set dose 200mg weekly, 5. Save | Medication appears in grid with generated vial image | [ ] |
| M2 | Add HCG | Same as M1 with HCG 500IU 2x weekly | Medication with vial image, correct schedule | [ ] |
| M3 | Add anastrozole | Same as M1 with anastrozole 0.5mg 2x weekly | Medication with tablet image | [ ] |
| M4 | Log dose | Click checkbox on Today's Doses card | Dose marked taken, sparkline updates | [ ] |
| M5 | Change dose | Edit testosterone to 250mg | History entry created, dose updated | [ ] |
| M6 | Discontinue medication | Discontinue HCG, reason: "Switched" | Moves to History tab with reason | [ ] |
| M7 | View blood correlation | Navigate to Blood page after adding testosterone | See vertical line at testosterone start date | [ ] |
| M8 | Image fallback | Add medication when Gemini API fails | Generic vial image used | [ ] |
| M9 | PRN medication | Add ibuprofen as PRN | Shows in list, not in Today's Doses | [ ] |
| M10 | Protocol grouping | Create "TRT Stack", assign testosterone + HCG | Both show protocol badge | [ ] |

---

## Definition of Done

A phase is **DONE** when:

1. **Code Complete:** All tasks in phase checklist marked complete
2. **Tests Pass:** All unit and integration tests pass locally
3. **Manual Verified:** Relevant manual test cases pass
4. **No Regressions:** Existing tests still pass
5. **Committed:** Code committed with conventional commit message
6. **Pushed:** Code pushed to `feature/drugs` branch

### Agent Verification Protocol

Before marking any phase complete, the implementing agent MUST:

1. Run `./gradlew test` in backend/ — all tests pass
2. Run `pnpm test` in web/ — all tests pass
3. Run `pnpm build` in web/ — build succeeds with no errors
4. Start dev servers (`bash infra/scripts/dev.sh`)
5. Execute relevant manual test cases
6. Capture evidence (test output, screenshot if UI)
7. Only then mark phase complete

### Success Criteria for Full Feature

- [ ] Can add TRT stack (testosterone, HCG, anastrozole) with AI-generated images
- [ ] Can log doses daily and see adherence sparklines
- [ ] Dashboard shows Today's Doses with working checkboxes
- [ ] Blood page shows medication event markers on charts
- [ ] Can discontinue medication with reason
- [ ] Dose changes tracked in history
- [ ] All tests pass
- [ ] Feature deployed to dev environment

---

## Technical Notes

### GCS Bucket Configuration

```bash
# Create bucket
gsutil mb -l us-central1 gs://health-fitness-160-medication-images

# Enable CDN
gcloud compute backend-buckets create medication-images-backend \
  --gcs-bucket-name=health-fitness-160-medication-images \
  --enable-cdn
```

### Environment Variables

```bash
# Backend application.yml
medications:
  gcs-bucket: health-fitness-160-medication-images
  cdn-base-url: https://medication-images.example.com
  gemini-model: gemini-2.5-flash  # or gemini-2.5-pro for higher quality
  fallback-images:
    injectable-vial: /fallbacks/injectable-vial.png
    tablet: /fallbacks/tablet.png
    # ... etc
```

### Gemini API Configuration

```yaml
# backend/app/src/main/resources/application.yml
gemini:
  api-key: ${GEMINI_API_KEY}
  image-generation:
    model: imagen-3.0-generate-001
    aspect-ratio: "1:1"
    number-of-images: 1
```

---

## Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Gemini content filters block medical images | High | Use "still life" prompt reframing per photography guide; fallback images |
| Web search returns poor drug info | Medium | Manual override in add flow; allow user to specify form manually |
| Image generation slow (10-30s) | Low | Async generation, show placeholder immediately |
| Shared catalog data quality | Medium | First-user-creates model with system ownership; admin can edit later |
| Complex scheduling edge cases | Medium | Start with simple frequency; advanced mode is opt-in |

---

## Future Considerations (V2+)

- Push notifications for dose reminders
- Apple Health / Google Fit integration
- Prescription OCR import
- Refill tracking and pharmacy integration
- Drug interaction checking (with appropriate disclaimers)
- Export to PDF for doctor visits
- Family/caregiver medication management
- Coordinated protocol scheduling
- Android native implementation

---

*Document created: 2026-05-23*
*Last updated: 2026-05-23*
*Author: Claude (PM/Sr. Developer)*
*Status: READY FOR IMPLEMENTATION*
