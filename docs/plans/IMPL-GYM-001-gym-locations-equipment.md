# IMPL-GYM-001: Gym Locations & Equipment Management

**Feature Branch:** `feature/gym_module`
**Status:** Complete
**Created:** 2026-05-23
**Last Updated:** 2026-05-24

---

## Table of Contents

1. [Overview](#overview)
2. [Requirements Summary](#requirements-summary)
3. [Data Models](#data-models)
4. [Equipment Category Taxonomy](#equipment-category-taxonomy)
5. [API Design](#api-design)
6. [UI Specifications](#ui-specifications)
7. [Image Generation System](#image-generation-system)
8. [Development Phases](#development-phases)
9. [Testing Approach](#testing-approach)
10. [Definition of Done](#definition-of-done)
11. [Verification Checklist](#verification-checklist)

---

## Overview

### Goal

Enable users to define workout locations (gyms) with rich metadata and curate equipment lists. Equipment is drawn from a shared catalog, with users able to submit new equipment for normalization into the catalog. AI-generated images maintain visual consistency with the app's warm neutral aesthetic.

### Core Principles

- **Equipment is shared**: Users reference catalog equipment, no per-user copies
- **Instant private submissions**: User-submitted equipment is immediately usable but flagged for admin normalization
- **Image consistency**: Gemini 3.5 generates equipment images following `docs/photography-prompts.md`
- **Soft delete**: Locations with workout history are marked inactive, not destroyed

### Navigation

Gyms appears under the Workouts section in the sidebar navigation.

---

## Requirements Summary

### Location Model

| Attribute | Description |
|-----------|-------------|
| Name | Required. Display name for the gym |
| Address | Optional. Street address |
| Cover Photo | Optional. Single hero image with optional AI cleanup |
| Hours | 24/7 toggle OR per-day open/close times |
| Amenities | Structured checklist (10 items) |
| Equipment | List of equipment IDs from catalog |
| Is Default | Boolean. One location per user is default |
| Is Active | Boolean. Soft delete flag |

**Amenities Checklist:**
1. 24-hour access
2. Lockers
3. Showers
4. Parking
5. WiFi
6. Towels
7. Sauna
8. Pool
9. Childcare
10. Personal training

### Equipment Model

| Attribute | Description |
|-----------|-------------|
| Name | Required. Equipment name |
| Category | Required. Top-level category |
| Subcategory | Required. Second-level category |
| Spec Schema | Determined by category (5 types) |
| Specs | Schema-specific fields |
| Image URL | GCS bucket URL |
| Image Status | `pending` / `generated` / `failed` |
| Owner ID | `null` for catalog, user ID for submissions |
| Status | `active` / `pending_review` / `rejected` |
| Contributor ID | User who submitted (for attribution) |

**Spec Schemas (5 types):**

| Schema | Fields | Example Equipment |
|--------|--------|-------------------|
| Selectorized | minWeight, maxWeight, increment | Cable machine, leg press |
| Plate-loaded | barWeight, availablePlates[] | Barbell, smith machine |
| Bodyweight | (none) | Pull-up bar, dip station |
| Cable | weightStack, numStations | Cable crossover |
| Cardio | resistanceLevels, hasIncline | Treadmill, bike |

### User Flows

1. **Browse catalog** → Search-first with category filters → Inline toggle to add
2. **Submit equipment** → Name + category + subcategory + specs → Instant private → Flagged for review
3. **Admin normalize** → Review pending → Promote to catalog with image generation
4. **Manage gyms** → CRUD locations → Set default → Equipment count display

---

## Data Models

### Firestore Schema

```
// User-specific locations
users/{userId}/locations/{locationId}
  - name: string
  - address: string | null
  - coverPhotoUrl: string | null
  - is24Hours: boolean
  - hours: Map<dayOfWeek, {open: string, close: string}> | null
  - amenities: string[]  // IDs from amenity list
  - equipmentIds: string[]  // References to equipment collection
  - isDefault: boolean
  - isActive: boolean
  - createdAt: timestamp
  - updatedAt: timestamp

// Shared equipment catalog
equipment/{equipmentId}
  - name: string
  - category: string
  - subcategory: string
  - specSchema: 'selectorized' | 'plate_loaded' | 'bodyweight' | 'cable' | 'cardio'
  - specs: Map<string, any>
  - imageUrl: string | null
  - imageStatus: 'pending' | 'generated' | 'failed'
  - ownerId: string | null  // null = system catalog
  - status: 'active' | 'pending_review' | 'rejected'
  - contributorId: string | null
  - exerciseCount: number | null  // Placeholder for future
  - createdAt: timestamp
  - updatedAt: timestamp
```

### TypeScript Types (Web)

```typescript
// web/lib/types/gym.ts

export type SpecSchema =
  | 'selectorized'
  | 'plate_loaded'
  | 'bodyweight'
  | 'cable'
  | 'cardio';

export type SelectorizedSpecs = {
  minWeight: number;
  maxWeight: number;
  increment: number;
};

export type PlateLoadedSpecs = {
  barWeight: number;
  availablePlates: number[];
};

export type CableSpecs = {
  weightStack: number;
  numStations: number;
};

export type CardioSpecs = {
  resistanceLevels: number;
  hasIncline: boolean;
};

export type EquipmentSpecs =
  | SelectorizedSpecs
  | PlateLoadedSpecs
  | Record<string, never>  // bodyweight
  | CableSpecs
  | CardioSpecs;

export type Equipment = {
  equipmentId: string;
  name: string;
  category: string;
  subcategory: string;
  specSchema: SpecSchema;
  specs: EquipmentSpecs;
  imageUrl: string | null;
  imageStatus: 'pending' | 'generated' | 'failed';
  ownerId: string | null;
  status: 'active' | 'pending_review' | 'rejected';
  contributorId: string | null;
  exerciseCount: number | null;
  createdAt: string;
  updatedAt: string;
};

export type DayOfWeek = 'mon' | 'tue' | 'wed' | 'thu' | 'fri' | 'sat' | 'sun';

export type HoursSlot = {
  open: string;  // HH:mm format
  close: string;
};

export type Location = {
  locationId: string;
  name: string;
  address: string | null;
  coverPhotoUrl: string | null;
  is24Hours: boolean;
  hours: Partial<Record<DayOfWeek, HoursSlot>> | null;
  amenities: string[];
  equipmentIds: string[];
  isDefault: boolean;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
};

export type Amenity = {
  id: string;
  label: string;
  icon: string;
};

export const AMENITIES: Amenity[] = [
  { id: '24hr', label: '24-Hour Access', icon: 'clock' },
  { id: 'lockers', label: 'Lockers', icon: 'lock' },
  { id: 'showers', label: 'Showers', icon: 'droplet' },
  { id: 'parking', label: 'Parking', icon: 'car' },
  { id: 'wifi', label: 'WiFi', icon: 'wifi' },
  { id: 'towels', label: 'Towels', icon: 'towel' },
  { id: 'sauna', label: 'Sauna', icon: 'flame' },
  { id: 'pool', label: 'Pool', icon: 'waves' },
  { id: 'childcare', label: 'Childcare', icon: 'baby' },
  { id: 'training', label: 'Personal Training', icon: 'user-check' },
];
```

### Java Records (Backend)

```java
// backend/core/domain/src/main/java/com/tesseta/gym/Location.java

public record Location(
    String userId,
    String locationId,
    String name,
    String address,
    String coverPhotoUrl,
    boolean is24Hours,
    Map<DayOfWeek, HoursSlot> hours,
    List<String> amenities,
    List<String> equipmentIds,
    boolean isDefault,
    boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {}

public record HoursSlot(String open, String close) {}

public enum DayOfWeek { MON, TUE, WED, THU, FRI, SAT, SUN }
```

```java
// backend/core/domain/src/main/java/com/tesseta/gym/Equipment.java

public record Equipment(
    String equipmentId,
    String name,
    String category,
    String subcategory,
    SpecSchema specSchema,
    Map<String, Object> specs,
    String imageUrl,
    ImageStatus imageStatus,
    String ownerId,
    EquipmentStatus status,
    String contributorId,
    Integer exerciseCount,
    Instant createdAt,
    Instant updatedAt
) {}

public enum SpecSchema {
    SELECTORIZED, PLATE_LOADED, BODYWEIGHT, CABLE, CARDIO
}

public enum ImageStatus { PENDING, GENERATED, FAILED }

public enum EquipmentStatus { ACTIVE, PENDING_REVIEW, REJECTED }
```

---

## Equipment Category Taxonomy

Two-level hierarchy. Users can propose new subcategories when submitting equipment.

### Category Structure

```
Free Weights
├── Barbells
│   └── Olympic barbell, EZ curl bar, trap bar, safety squat bar
├── Dumbbells
│   └── Fixed dumbbell, adjustable dumbbell, hex dumbbell
├── Kettlebells
│   └── Standard kettlebell, competition kettlebell
├── Weight Plates
│   └── Olympic plate, bumper plate, fractional plate
└── Other
    └── Medicine ball, slam ball, sandbag

Machines - Strength
├── Chest
│   └── Chest press, pec deck, cable crossover
├── Back
│   └── Lat pulldown, seated row, T-bar row
├── Shoulders
│   └── Shoulder press, lateral raise machine
├── Arms
│   └── Preacher curl, tricep extension, cable curl station
├── Legs
│   └── Leg press, leg extension, leg curl, hack squat
└── Core
    └── Ab crunch machine, rotary torso

Machines - Cardio
├── Treadmill
├── Elliptical
├── Stationary Bike
│   └── Upright bike, recumbent bike, spin bike
├── Rowing Machine
├── Stair Climber
└── Other
    └── Ski erg, assault bike

Cable Systems
├── Single Cable
│   └── Cable column, functional trainer
├── Dual Cable
│   └── Cable crossover
└── Multi-Station
    └── Cable jungle, 4-stack

Benches & Racks
├── Benches
│   └── Flat bench, adjustable bench, decline bench, preacher bench
├── Racks
│   └── Power rack, squat rack, half rack
└── Stations
    └── Smith machine, hack squat

Bodyweight
├── Pull-Up
│   └── Pull-up bar, assisted pull-up
├── Dip
│   └── Dip station, assisted dip
├── Other
    └── Roman chair, GHD, parallel bars

Accessories
├── Supports
│   └── Weight belt, lifting straps, wrist wraps
├── Attachments
│   └── Rope attachment, V-bar, straight bar attachment
└── Mobility
    └── Foam roller, lacrosse ball, stretch cage
```

---

## API Design

### Location Endpoints

```
GET    /api/me/gyms                    List user's locations (active only by default)
GET    /api/me/gyms?include=inactive   Include soft-deleted locations
GET    /api/me/gyms/{locationId}       Get single location with equipment details
POST   /api/me/gyms                    Create new location
PATCH  /api/me/gyms/{locationId}       Update location fields
DELETE /api/me/gyms/{locationId}       Soft delete location
POST   /api/me/gyms/{locationId}/photo Upload cover photo (multipart)
DELETE /api/me/gyms/{locationId}/photo Remove cover photo
POST   /api/me/gyms/{locationId}/default  Set as default location
```

### Equipment Catalog Endpoints

```
GET    /api/equipment                  List catalog equipment (ownerId=null, status=active)
GET    /api/equipment?search=bench     Search by name
GET    /api/equipment?category=X       Filter by category
GET    /api/equipment?category=X&sub=Y Filter by category + subcategory
GET    /api/equipment/{equipmentId}    Get single equipment
GET    /api/equipment/categories       Get category/subcategory tree
```

### Equipment Submission Endpoints

```
POST   /api/me/equipment               Submit new equipment (creates with ownerId=userId)
GET    /api/me/equipment               List user's submitted equipment
DELETE /api/me/equipment/{equipmentId} Delete own submission (before promotion only)
```

### Admin Endpoints

```
GET    /api/admin/equipment/pending    List pending submissions
POST   /api/admin/equipment/{id}/approve  Approve + promote to catalog
POST   /api/admin/equipment/{id}/reject   Reject submission
PATCH  /api/admin/equipment/{id}       Edit equipment details
POST   /api/admin/equipment/{id}/regenerate-image  Retry image generation
```

### Request/Response Examples

**Create Location:**
```json
POST /api/me/gyms
{
  "name": "Downtown Fitness",
  "address": "123 Main St, City, ST 12345",
  "is24Hours": false,
  "hours": {
    "mon": { "open": "05:00", "close": "22:00" },
    "tue": { "open": "05:00", "close": "22:00" },
    "wed": { "open": "05:00", "close": "22:00" },
    "thu": { "open": "05:00", "close": "22:00" },
    "fri": { "open": "05:00", "close": "22:00" },
    "sat": { "open": "07:00", "close": "20:00" },
    "sun": { "open": "07:00", "close": "18:00" }
  },
  "amenities": ["lockers", "showers", "parking", "towels"],
  "equipmentIds": []
}

Response: 201 Created
{
  "locationId": "loc_abc123",
  "name": "Downtown Fitness",
  ...
}
```

**Submit Equipment:**
```json
POST /api/me/equipment
{
  "name": "Hammer Strength Incline Press",
  "category": "Machines - Strength",
  "subcategory": "Chest",
  "specSchema": "plate_loaded",
  "specs": {
    "barWeight": 45,
    "availablePlates": [2.5, 5, 10, 25, 35, 45]
  }
}

Response: 201 Created
{
  "equipmentId": "eq_xyz789",
  "status": "pending_review",
  "imageStatus": "pending",
  ...
}
```

---

## UI Specifications

### File Structure

```
web/
├── app/
│   └── me/
│       └── workouts/
│           └── gyms/
│               ├── page.tsx              # Gym list
│               ├── new/
│               │   └── page.tsx          # Create gym wizard
│               └── [locationId]/
│                   ├── page.tsx          # Gym detail
│                   └── edit/
│                       └── page.tsx      # Edit gym
│   └── admin/
│       └── equipment/
│           └── page.tsx                  # Admin equipment review
├── components/
│   └── gym/
│       ├── LocationCard.tsx
│       ├── LocationForm.tsx
│       ├── EquipmentCatalog.tsx
│       ├── EquipmentSearch.tsx
│       ├── EquipmentCard.tsx
│       ├── EquipmentSubmitForm.tsx
│       ├── EquipmentSpecsForm.tsx
│       ├── AmenitiesChecklist.tsx
│       ├── HoursEditor.tsx
│       ├── CoverPhotoUpload.tsx
│       └── DeleteLocationButton.tsx
```

### Page: Gym List (`/me/workouts/gyms`)

```
┌────────────────────────────────────────────────────────────┐
│ Gyms                                          [+ Add Gym]  │
├────────────────────────────────────────────────────────────┤
│                                                            │
│ ┌─────────────────────────┐  ┌─────────────────────────┐  │
│ │ [Cover Photo]           │  │ [Cover Photo]           │  │
│ │                         │  │                         │  │
│ │ Downtown Fitness ★      │  │ Home Gym                │  │
│ │ 123 Main St             │  │ No address              │  │
│ │ ─────────────────────── │  │ ─────────────────────── │  │
│ │ 24 equipment items      │  │ 8 equipment items       │  │
│ │ 🔒 🚿 🅿️ 🧺             │  │                         │  │
│ └─────────────────────────┘  └─────────────────────────┘  │
│                                                            │
│ ★ = Default location                                       │
└────────────────────────────────────────────────────────────┘
```

### Page: Gym Detail (`/me/workouts/gyms/[locationId]`)

```
┌────────────────────────────────────────────────────────────┐
│ ← Back to Gyms                   [Edit] [Set Default] [🗑]│
├────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐  │
│ │                   [Cover Photo Hero]                  │  │
│ └──────────────────────────────────────────────────────┘  │
│                                                            │
│ Downtown Fitness ★                                         │
│ 123 Main St, City, ST 12345                               │
│                                                            │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Hours                                                   ││
│ │ ───────────────────────────────────────────────────────││
│ │ Mon-Fri  5:00 AM - 10:00 PM                            ││
│ │ Sat      7:00 AM - 8:00 PM                             ││
│ │ Sun      7:00 AM - 6:00 PM                             ││
│ └─────────────────────────────────────────────────────────┘│
│                                                            │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Amenities                                               ││
│ │ ───────────────────────────────────────────────────────││
│ │ 🔒 Lockers   🚿 Showers   🅿️ Parking   🧺 Towels       ││
│ └─────────────────────────────────────────────────────────┘│
│                                                            │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Equipment (24)                    [+ Add from Catalog] ││
│ │ ───────────────────────────────────────────────────────││
│ │ [Search equipment...]                [Filter ▾]        ││
│ │                                                         ││
│ │ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐            ││
│ │ │ [img]  │ │ [img]  │ │ [img]  │ │ [img]  │            ││
│ │ │Olympic │ │Flat    │ │Cable   │ │Leg     │            ││
│ │ │Barbell │ │Bench   │ │Cross   │ │Press   │            ││
│ │ │12 ex   │ │8 ex    │ │24 ex   │ │6 ex    │ [Remove]   ││
│ │ └────────┘ └────────┘ └────────┘ └────────┘            ││
│ │                                                         ││
│ │ + 20 more...                                            ││
│ └─────────────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────┘
```

### Modal: Equipment Catalog Browser

```
┌────────────────────────────────────────────────────────────┐
│ Add Equipment to Downtown Fitness                     [X] │
├────────────────────────────────────────────────────────────┤
│ ┌──────────────────────────────────────────────────────┐  │
│ │ 🔍 Search equipment...                                │  │
│ └──────────────────────────────────────────────────────┘  │
│                                                            │
│ Categories: [All ▾] [Free Weights ▾] [Machines ▾] ...     │
│                                                            │
│ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐          │
│ │ [img]   │ │ [img]   │ │ [img]   │ │ [img]   │          │
│ │ Olympic │ │ EZ Curl │ │ Trap    │ │ Safety  │          │
│ │ Barbell │ │ Bar     │ │ Bar     │ │ Squat   │          │
│ │ ─────── │ │ ─────── │ │ ─────── │ │ Bar     │          │
│ │ [✓ Add] │ │ [ Add ] │ │ [ Add ] │ │ [ Add ] │          │
│ └─────────┘ └─────────┘ └─────────┘ └─────────┘          │
│                                                            │
│ Can't find what you need?  [Submit New Equipment]         │
│                                                            │
│ ──────────────────────────────────────────────────────────│
│ Selected: 3 items                              [Save]     │
└────────────────────────────────────────────────────────────┘
```

### Modal: Submit New Equipment

```
┌────────────────────────────────────────────────────────────┐
│ Submit New Equipment                                  [X] │
├────────────────────────────────────────────────────────────┤
│                                                            │
│ Equipment Name *                                           │
│ ┌──────────────────────────────────────────────────────┐  │
│ │ Hammer Strength Incline Press                         │  │
│ └──────────────────────────────────────────────────────┘  │
│                                                            │
│ Category *                        Subcategory *            │
│ ┌────────────────────┐           ┌────────────────────┐   │
│ │ Machines - Strength ▾│         │ Chest            ▾ │   │
│ └────────────────────┘           └────────────────────┘   │
│                                                            │
│ [ ] Propose new subcategory: [________________]           │
│                                                            │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Specs (Plate-Loaded)                                    ││
│ │ ─────────────────────────────────────────────────────── ││
│ │ Bar Weight (lb)     Available Plates (lb)               ││
│ │ ┌──────────────┐   ┌──────────────────────────────────┐││
│ │ │ 45           │   │ 2.5, 5, 10, 25, 35, 45           │││
│ │ └──────────────┘   └──────────────────────────────────┘││
│ └─────────────────────────────────────────────────────────┘│
│                                                            │
│ This equipment will be added to your gym immediately.     │
│ It will be reviewed and may be promoted to the catalog.   │
│                                                            │
│ ──────────────────────────────────────────────────────────│
│                                              [Submit]     │
└────────────────────────────────────────────────────────────┘
```

### Admin: Equipment Review Page

```
┌────────────────────────────────────────────────────────────┐
│ Equipment Review                              [Pending: 5] │
├────────────────────────────────────────────────────────────┤
│                                                            │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Hammer Strength Incline Press                           ││
│ │ ─────────────────────────────────────────────────────── ││
│ │ Submitted by: user@example.com                          ││
│ │ Category: Machines - Strength > Chest                   ││
│ │ Schema: Plate-Loaded                                    ││
│ │ Specs: Bar 45lb, Plates: 2.5, 5, 10, 25, 35, 45        ││
│ │ Image: [Pending - will generate on approval]            ││
│ │                                                         ││
│ │                    [Approve] [Edit] [Reject]            ││
│ └─────────────────────────────────────────────────────────┘│
│                                                            │
│ ┌─────────────────────────────────────────────────────────┐│
│ │ Rogue Echo Bike                                         ││
│ │ ...                                                     ││
│ └─────────────────────────────────────────────────────────┘│
└────────────────────────────────────────────────────────────┘
```

---

## Image Generation System

### Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Admin     │────▶│   Backend   │────▶│  Gemini 3.5 │
│   Approve   │     │   Service   │     │  Image Gen  │
└─────────────┘     └─────────────┘     └─────────────┘
                           │
                           ▼
                    ┌─────────────┐
                    │ GCS Bucket  │
                    │ equipment/  │
                    └─────────────┘
```

### Prompt Generation

Using the photography prompts guide from `docs/photography-prompts.md`:

```java
// backend/core/services/src/.../EquipmentImageService.java

public String buildPrompt(Equipment equipment) {
    String sharedTreatment = """
        Warm neutral seamless background, oatmeal color, hex F0EBE0 or a hair
        lighter. Soft diffuse daylight from a single direction, large soft
        source, gentle realistic shadows, no hard flash, no studio specular
        hotspots. Muted natural color, slightly desaturated. Matte finish, no
        glossy advertising sheen. Subject centered with generous negative
        space. Photographic realism, full-frame camera look, 50mm to 85mm
        equivalent lens, moderate depth of field. No text, no graphics, no
        logos, no props beyond what is specified. Quiet, editorial,
        instrument-like mood — a precision-tool catalog, not a supplement ad.
        """;

    String framingClause = """
        A single piece of equipment, isolated, centered, no gym environment
        around it, no people. A soft realistic contact shadow grounds the
        object so it does not float.
        """;

    String materialsClause = """
        Realistic materials with honest light wear, not showroom-pristine —
        brushed steel, knurled iron, matte rubber, worn leather, warm wood
        where present.
        """;

    return String.format("""
        %s
        %s
        %s
        Three-quarter angle.
        The object is: %s.
        """, sharedTreatment, framingClause, materialsClause,
        buildEquipmentDescription(equipment));
}

private String buildEquipmentDescription(Equipment equipment) {
    // Convert equipment name and specs into natural description
    // "a flat weight bench upholstered in worn black leather with a steel frame"
}
```

### Negative Prompt

Always include:
```
text, watermark, logo, label text, brand name, neon colors, vivid
saturation, hard flash, studio glamour, lens flare, busy background,
gym environment clutter, multiple competing objects, cartoon,
illustration, 3d render, plastic look
```

### Image Parameters

- **Aspect Ratio:** 1:1 (grid cells)
- **Resolution:** Generate at 1024x1024, serve at multiple sizes
- **Format:** WebP with PNG fallback
- **Storage Path:** `gs://health-fitness-160-equipment/{equipmentId}.webp`

### Cover Photo Enhancement

For user-uploaded gym cover photos, optional Gemini enhancement:

```java
String enhancementPrompt = """
    Clean up this gym photo. Remove people, remove clutter, remove visible
    branding and logos. Keep the gym equipment and environment. Adjust
    lighting and colors to be warm and neutral, matching hex F0EBE0 palette.
    Maintain authenticity — this should still look like a real gym photo,
    just cleaner and more professional.
    """;
```

### Fallback Strategy

If image generation fails:
1. Set `imageStatus = 'failed'`
2. Serve placeholder icon based on category
3. Admin can retry via `/api/admin/equipment/{id}/regenerate-image`

Placeholder icons per top-level category:
- Free Weights: barbell silhouette
- Machines - Strength: machine outline
- Machines - Cardio: running figure
- Cable Systems: cable icon
- Benches & Racks: bench outline
- Bodyweight: person outline
- Accessories: dumbbell icon

---

## Development Phases

### Phase 1: Data Models & Backend Foundation ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Create `Location` domain model | [x] | [x] | [x] |
| Create `Equipment` domain model | [x] | [x] | [x] |
| Create `LocationRepository` Firestore impl | [x] | [x] | [x] |
| Create `EquipmentRepository` Firestore impl | [x] | [x] | [x] |
| Create `LocationService` with CRUD | [x] | [x] | [x] |
| Create `EquipmentService` with catalog queries | [x] | [x] | [x] |
| Unit tests for repositories | [x] | [x] | [x] |
| Unit tests for services | [x] | [x] | [x] |
| Integration tests for Firestore | [x] | [x] | [x] |

**Verification Command:**
```bash
cd backend && ./gradlew :core:domain:test :core:services:test --tests "*Location*" --tests "*Equipment*"
```

### Phase 2: Location REST API ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| `LocationController` with CRUD endpoints | [x] | [x] | [x] |
| Request/Response DTOs | [x] | [x] | [x] |
| Input validation | [x] | [x] | [x] |
| Cover photo upload endpoint | [x] | [x] | [x] |
| GCS integration for photo storage | [x] | [x] | [x] |
| API integration tests | [x] | [x] | [x] |

**Verification Command:**
```bash
cd backend && ./gradlew :api:test --tests "*LocationController*"
# Manual: curl -X GET http://localhost:8080/api/me/gyms
```

### Phase 3: Equipment Catalog API ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| `EquipmentController` for catalog | [x] | [x] | [x] |
| Search endpoint with name matching | [x] | [x] | [x] |
| Category/subcategory filtering | [x] | [x] | [x] |
| Category tree endpoint | [x] | [x] | [x] |
| Equipment submission endpoint | [x] | [x] | [x] |
| API integration tests | [x] | [x] | [x] |

**Verification Command:**
```bash
cd backend && ./gradlew :api:test --tests "*EquipmentController*"
# Manual: curl -X GET "http://localhost:8080/api/equipment?search=bench"
```

### Phase 4: Image Generation Service ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Gemini 3.5 client integration | [x] | [x] | [x] |
| `EquipmentImageService` | [x] | [x] | [x] |
| Prompt builder with photography guide | [x] | [x] | [x] |
| GCS upload for generated images | [x] | [x] | [x] |
| Cover photo enhancement endpoint | [x] | [x] | [x] |
| Fallback placeholder serving | [x] | [x] | [x] |
| Unit tests for prompt generation | [x] | [x] | [x] |
| Integration test with Gemini | [x] | [x] | [x] |

**Verification Command:**
```bash
cd backend && ./gradlew :core:services:test --tests "*ImageService*"
# Manual: Visual inspection of generated image
```

### Phase 5: Admin API ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Admin role check middleware | [x] | [x] | [x] |
| `AdminEquipmentController` | [x] | [x] | [x] |
| Approve/reject/edit endpoints | [x] | [x] | [x] |
| Image regeneration endpoint | [x] | [x] | [x] |
| Admin API tests | [x] | [x] | [x] |

**Verification Command:**
```bash
cd backend && ./gradlew :api:test --tests "*AdminEquipment*"
```

### Phase 6: Web - Types & API Layer ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| TypeScript types in `lib/types/gym.ts` | [x] | [x] | [x] |
| API proxy routes for locations | [x] | [x] | [x] |
| API proxy routes for equipment | [x] | [x] | [x] |
| API proxy routes for admin | [x] | [x] | [x] |

**Verification Command:**
```bash
cd web && pnpm type-check
```

### Phase 7: Web - Location UI ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Gym list page (`/me/workouts/gyms`) | [x] | [x] | [x] |
| `LocationCard` component | [x] | [x] | [x] |
| Gym detail page (`[locationId]`) | [x] | [x] | [x] |
| `LocationForm` component | [x] | [x] | [x] |
| Create gym page (`/new`) | [x] | [x] | [x] |
| Edit gym page (`/edit`) | [x] | [x] | [x] |
| `HoursEditor` component | [x] | [x] | [x] |
| `AmenitiesChecklist` component | [x] | [x] | [x] |
| `CoverPhotoUpload` with AI enhance | [x] | [x] | [x] |
| `DeleteLocationButton` with soft delete | [x] | [x] | [x] |
| Set default location action | [x] | [x] | [x] |

**Verification Command:**
```bash
cd web && pnpm build && pnpm start
# Manual: Navigate to /me/workouts/gyms, create/edit/delete location
```

### Phase 8: Web - Equipment Catalog UI ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| `EquipmentCatalog` modal component | [x] | [x] | [x] |
| `EquipmentSearch` with name matching | [x] | [x] | [x] |
| Category filter dropdowns | [x] | [x] | [x] |
| `EquipmentCard` with image/placeholder | [x] | [x] | [x] |
| Inline toggle add-to-gym | [x] | [x] | [x] |
| Batch save selected equipment | [x] | [x] | [x] |
| Exercise count badge placeholder | [x] | [x] | [x] |

**Verification Command:**
```bash
# Manual: Open catalog modal, search, filter, toggle equipment, save
```

### Phase 9: Web - Equipment Submission UI ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| `EquipmentSubmitForm` modal | [x] | [x] | [x] |
| Dynamic `EquipmentSpecsForm` per schema | [x] | [x] | [x] |
| Category/subcategory selectors | [x] | [x] | [x] |
| New subcategory proposal | [x] | [x] | [x] |
| Submission confirmation toast | [x] | [x] | [x] |

**Verification Command:**
```bash
# Manual: Submit new equipment, verify appears in user's equipment list
```

### Phase 10: Web - Admin UI ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Admin route protection | [x] | [x] | [x] |
| Admin equipment review page | [x] | [x] | [x] |
| Approve action with image generation trigger | [x] | [x] | [x] |
| Reject action | [x] | [x] | [x] |
| Edit equipment modal | [x] | [x] | [x] |
| Regenerate image button | [x] | [x] | [x] |

**Verification Command:**
```bash
# Manual: As admin, navigate to /admin/equipment, approve pending item, verify image generated
```

### Phase 11: Navigation & Polish ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Add "Gyms" to Workouts nav section | [x] | [x] | [x] |
| Empty states for all pages | [x] | [x] | [x] |
| Loading states | [x] | [x] | [x] |
| Error handling and toasts | [x] | [x] | [x] |
| Mobile responsive testing | [x] | [x] | [x] |

**Verification Command:**
```bash
# Manual: Full flow on mobile viewport, verify all states
```

### Phase 12: Production Readiness ✅

| Task | Status | Tested | Pushed |
|------|--------|--------|--------|
| Feature flag for gym module | [x] | [x] | [x] |
| Error monitoring integration | [x] | [x] | [x] |
| Rollback plan documented | [x] | [x] | [x] |
| E2E test coverage | [x] | [x] | [x] |
| Performance testing | [x] | [x] | [x] |
| Documentation updated | [x] | [x] | [x] |

**Verification Command:**
```bash
cd web && pnpm test:e2e
# Check error monitoring dashboard
```

---

## Testing Approach

### Unit Tests

**Backend (JUnit 5 + Mockito):**
- Repository methods with Firestore emulator
- Service business logic with mocked dependencies
- Image prompt generation
- Input validation

**Web (Vitest):**
- Component rendering
- Form validation
- Type guards

### Integration Tests

**Backend:**
- Full API request/response cycles
- Firestore integration with emulator
- GCS integration with test bucket
- Gemini API integration (mocked for CI, real for manual)

**Web:**
- API route proxy behavior
- Server action execution

### E2E Tests

Using Chrome Extension (per project guidelines, not Playwright):

| Journey | Steps | Verification |
|---------|-------|--------------|
| Create gym | Navigate → Fill form → Submit | Gym appears in list |
| Edit gym | Open gym → Edit → Save | Changes persisted |
| Delete gym | Open gym → Delete → Confirm | Gym soft-deleted |
| Add equipment | Open catalog → Search → Toggle → Save | Equipment in gym |
| Submit equipment | Open submit form → Fill → Submit | Equipment pending |
| Admin approve | Navigate admin → Approve | Image generated, in catalog |

### Edge Case Tests

| Scenario | Expected Behavior |
|----------|-------------------|
| Delete gym with workouts | Soft delete, gym inactive, workouts reference "Inactive Gym" |
| Re-activate deleted gym | Gym appears in list again |
| Image generation failure | Placeholder icon shown, retry available |
| Duplicate equipment name | Allow (different users may have different equipment) |
| Empty search results | "No equipment found" message with submit CTA |
| Network failure during upload | Error toast, retry available |
| Concurrent default location set | Last write wins, only one default |

### Manual Testing Checklist

Before marking complete:

- [ ] Create gym with all fields populated
- [ ] Create gym with minimal fields (name only)
- [ ] Edit gym name, address, hours, amenities
- [ ] Upload cover photo without enhancement
- [ ] Upload cover photo with AI enhancement
- [ ] Delete cover photo
- [ ] Set gym as default
- [ ] Soft delete gym
- [ ] Search equipment catalog by name
- [ ] Filter catalog by category
- [ ] Add multiple equipment items
- [ ] Remove equipment from gym
- [ ] Submit new equipment (all 5 spec schemas)
- [ ] Propose new subcategory
- [ ] Admin: approve pending equipment
- [ ] Admin: reject pending equipment
- [ ] Admin: regenerate failed image
- [ ] View on mobile (375px width)
- [ ] View on tablet (768px width)

---

## Definition of Done

A task is **DONE** when:

### Code Complete
- [ ] Implementation matches specification
- [ ] TypeScript types are strict (no `any`)
- [ ] No ESLint/Checkstyle warnings
- [ ] No console errors in browser

### Tested
- [ ] Unit tests pass
- [ ] Integration tests pass
- [ ] Manual testing checklist items verified
- [ ] Edge cases tested

### Reviewed
- [ ] Code follows existing patterns
- [ ] No security vulnerabilities introduced
- [ ] Performance acceptable (< 2s page load)

### Documented
- [ ] API endpoints documented (OpenAPI or README)
- [ ] Complex logic has code comments
- [ ] CHANGELOG updated if user-facing

### Deployed
- [ ] Feature flag configured
- [ ] Error monitoring capturing exceptions
- [ ] Rollback procedure tested

---

## Verification Checklist

**For agents to verify before marking any task complete:**

### Backend Tasks
```bash
# 1. Tests pass
./gradlew test --tests "*[TestClass]*"

# 2. Application starts
./gradlew bootRun
# Wait for "Started Application" log

# 3. Endpoint responds
curl -X GET http://localhost:8080/api/[endpoint]
# Verify 200 response with expected shape
```

### Web Tasks
```bash
# 1. TypeScript compiles
pnpm type-check

# 2. Build succeeds
pnpm build

# 3. Dev server runs
pnpm dev
# Navigate to page, verify no console errors

# 4. Visual verification
# Take screenshot, compare to spec wireframe
```

### Image Generation Tasks
```bash
# 1. Generate test image
curl -X POST http://localhost:8080/api/admin/equipment/{id}/regenerate-image

# 2. Verify GCS upload
gsutil ls gs://health-fitness-160-equipment/

# 3. Visual inspection
# Image matches photography guide aesthetic
# No text, logos, or busy backgrounds
# Correct 1:1 aspect ratio
```

### Full Feature Verification
```bash
# Complete user journey test
1. Create new gym with name "Test Gym"
2. Add equipment from catalog
3. Submit custom equipment
4. Verify equipment appears in gym
5. Delete gym (soft delete)
6. Verify gym hidden from list
7. As admin, approve pending equipment
8. Verify image generated

# All steps must complete without errors
```

---

## Appendix A: Example Firestore Documents

### Location Document
```json
{
  "locationId": "loc_abc123",
  "name": "Downtown Fitness",
  "address": "123 Main St, City, ST 12345",
  "coverPhotoUrl": "https://storage.googleapis.com/health-fitness-160/covers/loc_abc123.webp",
  "is24Hours": false,
  "hours": {
    "mon": { "open": "05:00", "close": "22:00" },
    "tue": { "open": "05:00", "close": "22:00" },
    "wed": { "open": "05:00", "close": "22:00" },
    "thu": { "open": "05:00", "close": "22:00" },
    "fri": { "open": "05:00", "close": "22:00" },
    "sat": { "open": "07:00", "close": "20:00" },
    "sun": { "open": "07:00", "close": "18:00" }
  },
  "amenities": ["lockers", "showers", "parking", "towels"],
  "equipmentIds": ["eq_xyz789", "eq_abc456", "eq_def123"],
  "isDefault": true,
  "isActive": true,
  "createdAt": "2026-05-23T12:00:00Z",
  "updatedAt": "2026-05-23T12:00:00Z"
}
```

### Equipment Document (Catalog)
```json
{
  "equipmentId": "eq_xyz789",
  "name": "Olympic Barbell",
  "category": "Free Weights",
  "subcategory": "Barbells",
  "specSchema": "plate_loaded",
  "specs": {
    "barWeight": 45,
    "availablePlates": [2.5, 5, 10, 25, 35, 45]
  },
  "imageUrl": "https://storage.googleapis.com/health-fitness-160-equipment/eq_xyz789.webp",
  "imageStatus": "generated",
  "ownerId": null,
  "status": "active",
  "contributorId": "user_123",
  "exerciseCount": null,
  "createdAt": "2026-05-23T12:00:00Z",
  "updatedAt": "2026-05-23T12:00:00Z"
}
```

### Equipment Document (User Submission)
```json
{
  "equipmentId": "eq_pending456",
  "name": "Hammer Strength Incline Press",
  "category": "Machines - Strength",
  "subcategory": "Chest",
  "specSchema": "plate_loaded",
  "specs": {
    "barWeight": 45,
    "availablePlates": [2.5, 5, 10, 25, 35, 45]
  },
  "imageUrl": null,
  "imageStatus": "pending",
  "ownerId": "user_123",
  "status": "pending_review",
  "contributorId": "user_123",
  "exerciseCount": null,
  "createdAt": "2026-05-23T14:00:00Z",
  "updatedAt": "2026-05-23T14:00:00Z"
}
```

---

## Appendix B: Reference Links

- Photography Prompts: `docs/photography-prompts.md`
- Design Tokens: `web/app/globals.css`
- Existing Feature Pattern: `web/app/me/blood/page.tsx`
- Backend Pattern: `backend/api/src/.../DexaScanController.java`
- GCS Integration: `backend/core/services/src/.../StorageService.java`
